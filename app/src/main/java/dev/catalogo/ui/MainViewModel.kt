package dev.catalogo.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.catalogo.CatalogoApp
import dev.catalogo.core.ai.AiResult
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.model.AppState
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Mode
import dev.catalogo.core.model.Money
import dev.catalogo.core.model.PcLink
import dev.catalogo.core.model.Sale
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.Stage
import dev.catalogo.core.model.VintedAccount
import dev.catalogo.core.pc.PcPayload
import dev.catalogo.core.sales.LabelJob
import dev.catalogo.core.sales.Sales
import dev.catalogo.core.sales.Sales as SalesRules
import dev.catalogo.core.sales.ShippingLabels
import dev.catalogo.core.sales.VintedSales
import dev.catalogo.core.sales.VintedSales.Kind
import dev.catalogo.data.AppLog
import dev.catalogo.data.CatalogRepository
import dev.catalogo.data.pc.PcClient
import dev.catalogo.data.photo.Framing
import dev.catalogo.data.photo.GalleryFolders
import dev.catalogo.data.photo.PhotoCropper
import dev.catalogo.ui.common.GAP
import dev.catalogo.ui.photos.openCamera
import dev.catalogo.ui.sales.SalesView
import dev.catalogo.ui.sales.openInPrinter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface Screen {
    /** Main tabs: articles, status, sales, inventory. */
    data object Home : Screen
    data object Sales : Screen
    data object SalesStats : Screen
    data class Sale(val id: String) : Screen
    data object Lots : Screen
    data object Status : Screen

    data class Article(val number: Int) : Screen
    /** New article when [number] is null. */
    data class Editor(val number: Int?) : Screen
    data object SessionPhotos : Screen
    data object Settings : Screen
    data class Template(val accountId: String) : Screen
    /** Order, cover and crop of [accountId]'s photos of [number]. */
    data class Photos(val number: Int, val accountId: String) : Screen
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CatalogoApp
    val repo = app.repo
    val state: StateFlow<AppState> = repo.store.state

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    /** Photo count per album folder in the current base folder. */
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    /** First photo of each album, for thumbnails. */
    private val _covers = MutableStateFlow<Map<String, Uri>>(emptyMap())
    val covers: StateFlow<Map<String, Uri>> = _covers.asStateFlow()

    /** Article number → cover from any account (gallery album, photos kept in the app or legacy copies). */
    private val _articleCovers = MutableStateFlow<Map<Int, Uri>>(emptyMap())
    val articleCovers: StateFlow<Map<Int, Uri>> = _articleCovers.asStateFlow()

    /** Clean photos kept in the app for the other accounts: account id → number → count. */
    private val _accountPhotos = MutableStateFlow<Map<String, Map<Int, Int>>>(emptyMap())
    val accountPhotos: StateFlow<Map<String, Map<Int, Int>>> = _accountPhotos.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _consent = MutableStateFlow<IntentSender?>(null)
    val consent: StateFlow<IntentSender?> = _consent.asStateFlow()

    private val _review = MutableStateFlow<List<Uri>>(emptyList())
    val review: StateFlow<List<Uri>> = _review.asStateFlow()

    private val _selected = MutableStateFlow<Set<Uri>>(emptySet())
    val selected: StateFlow<Set<Uri>> = _selected.asStateFlow()

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    data class Viewer(val photos: List<Uri>, val start: Int)

    private val _viewer = MutableStateFlow<Viewer?>(null)
    val viewer: StateFlow<Viewer?> = _viewer.asStateFlow()

    fun openViewer(photos: List<Uri>, start: Int) {
        if (photos.isNotEmpty()) _viewer.value = Viewer(photos, start)
    }

    fun closeViewer() {
        _viewer.value = null
    }

    /** Photos of an album on this phone, oldest first. */
    suspend fun albumPhotos(album: String): List<Uri> =
        withContext(Dispatchers.IO) { runCatching { repo.gallery.albumPhotos(repo.base, album) }.getOrDefault(emptyList()) }

    /** Photos for [accountId] kept in the app, then the reduced copies left by earlier versions. */
    suspend fun pendingAccountPhotos(accountId: String, number: Int): List<Uri> = withContext(Dispatchers.IO) {
        (repo.accountPhotos.files(accountId, number) + repo.legacyPreviews.files(number, accountId)).map { Uri.fromFile(it) }
    }

    init {
        // The albums live where this phone's gallery shows them.
        run {
            repo.ensureDistinctStyles()
            repo.adoptAccounts()
            repo.ensureSaleIds()
            reportMoves(repo.adoptFolder(GalleryFolders.auto()))
            refresh()
        }
    }

    // ---------- navigation ----------

    fun go(screen: Screen) {
        if (_stack.value.last() == screen) return
        _stack.value = _stack.value + screen
    }

    fun back(): Boolean {
        val s = _stack.value
        if (s.size <= 1) {
            // Secondary tabs go back to Artículos before leaving the app.
            if (s.last() == Screen.Lots || s.last() == Screen.Status || s.last() == Screen.Sales) {
                tab(Screen.Home)
                return true
            }
            return false
        }
        _stack.value = s.dropLast(1)
        refresh()
        return true
    }

    /** Switches main tab. */
    fun tab(screen: Screen) {
        _stack.value = listOf(screen)
        refresh()
    }

    /** Replaces the top screen; never leaves the same screen twice in a row (back would seem broken). */
    private fun replaceTop(screen: Screen) {
        val rest = _stack.value.dropLast(1)
        _stack.value = if (rest.lastOrNull() == screen) rest else rest + screen
    }

    fun clearMessage() {
        _message.value = null
    }

    fun say(text: String) {
        _message.value = text
    }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CatalogException) {
            _message.value = e.message
        } catch (e: Exception) {
            _message.value = "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun refresh() = viewModelScope.launch {
        app.pcSync.refresh()
        val base = repo.base
        val s = state.value
        val p = s.catalog
        var articleCovers = emptyMap<Int, Uri>()
        val (albums, accountPhotos) = withContext(Dispatchers.IO) {
            val a = runCatching { repo.gallery.albums(base) }.getOrDefault(emptyMap())
            val pending = p?.otherAccounts?.associate { d -> d.id to repo.accountPhotos.pending(d.id) } ?: emptyMap()
            if (p != null) {
                articleCovers = p.articles.filter { !it.released }.mapNotNull { art ->
                    val own = a[Sku.format(p.mainAccount.prefix, art.number, p.digits)]?.cover
                    val cover = own
                        ?: p.otherAccounts.firstNotNullOfOrNull { d -> pending[d.id]?.get(art.number)?.firstOrNull() }?.let { Uri.fromFile(it) }
                        ?: repo.legacyPreviews.files(art.number).firstOrNull()?.let { Uri.fromFile(it) }
                    cover?.let { art.number to it }
                }.toMap()
            }
            a to pending.mapValues { (_, byNumber) -> byNumber.mapValues { it.value.size } }
        }
        _counts.value = albums.mapValues { it.value.count }
        _covers.value = albums.mapValues { it.value.cover }
        _articleCovers.value = articleCovers
        _accountPhotos.value = accountPhotos
    }

    // ---------- deletion consent ----------

    private fun askToDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _consent.value = MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris).intentSender
    }

    fun consentHandled(granted: Boolean) {
        _consent.value = null
        if (!granted) _message.value = "Las fotos originales siguen en la galería"
        refresh()
    }

    // ---------- setup ----------

    fun setupMainPhone(name: String, prefix: String) = run {
        repo.setupMainPhone(name, prefix)
        repo.adoptFolder(GalleryFolders.auto())
    }

    // ---------- articles ----------

    fun saveArticle(number: Int?, listing: Listing, lotId: String?, costText: String, ai: AiResult?, priceText: String = "") = run {
        val cost = if (lotId != null || costText.isBlank()) null
        else Money.parse(costText) ?: throw CatalogException("Coste no válido")
        val price = if (priceText.isBlank()) null
        else Money.parse(priceText)?.takeIf { it > 0 } ?: throw CatalogException("Precio no válido")
        if (number == null) {
            val created = repo.newArticle(listing, lotId, cost, ai)
            repo.setPrice(created, price)
            replaceTop(Screen.Article(created))
        } else {
            repo.updateArticle(number, listing, lotId, cost, ai)
            repo.setPrice(number, price)
            back()
        }
    }

    // ---------- drafts on the PC ----------

    /** The PC program as last seen (null status = not configured or not reached). */
    val pc = app.pcSync.pc
    val incidents = app.pcSync.incidents
    val offers = app.pcSync.offers

    fun incidentReviewed(id: String) = run { app.pcSync.reviewed(id) }

    /** «Generar y preparar borrador»: AI, checks and sending happen in the background. */
    /** «Generar y preparar borrador» with the photos the AI should look at (the PC gets all of them). */
    fun prepareDraft(number: Int, photos: List<Uri>, mode: String = Mode.UPLOAD_PLAY) = run {
        if (repo.state.pc == null) throw CatalogException("Configura el PC en Ajustes")
        if (photos.isEmpty()) throw CatalogException("Elige al menos una foto")
        repo.setPhotos(number, photos.map { it.toString() })
        app.pcSync.prepareNow(number, mode)
        _message.value = "En cola: puedes salir de la app y seguir con otros artículos"
    }

    /** Photos of [number]'s album, and which ones the AI looks at. */
    suspend fun vintedPhotos(number: Int): Pair<List<Uri>, List<Uri>> {
        val p = repo.state.catalog ?: return emptyList<Uri>() to emptyList()
        val album = albumPhotos(Sku.format(p.mainAccount.prefix, number, p.digits))
        val chosen = Catalog.chosenPhotos(album, Catalog.article(p, number)?.photos.orEmpty()) { it.toString() }
        return album to chosen
    }

    fun fixDraft(number: Int, fixes: PcPayload.Fixes) = run {
        app.pcSync.fix(number, fixes)
        _message.value = "Revisado: sigue su camino al PC"
    }

    fun retryDraft(number: Int) = run { app.pcSync.retry(number) }

    /** Where it stands, set by hand (also tells the PC). */
    fun setStage(number: Int, stage: Stage, url: String = "") = run {
        app.pcSync.setStage(number, stage, url)
        _message.value = "Estado: ${stage.label}"
    }

    /** The same status for several articles; those the PC cannot change now are reported. */
    fun setStageMany(numbers: List<Int>, stage: Stage) = run {
        val failed = app.pcSync.setStageMany(numbers, stage)
        _message.value = if (failed.isEmpty()) "${numbers.size} en «${stage.label}»"
        else "${numbers.size - failed.size} cambiados; ${failed.size} no (¿PC sin conexión?)"
    }

    fun setMode(number: Int, mode: String) = run {
        app.pcSync.setMode(number, mode)
        _message.value = Mode.label(mode)
    }

    /** «Solo jugar»: an article already on Vinted goes to the next game with its link. */
    fun playOnly(number: Int, url: String) = run {
        if (!Regex("^https://(www\\.)?vinted\\.[a-z.]{2,8}/items/\\d+").containsMatchIn(url.trim())) {
            throw CatalogException("Pega el link del artículo en Vinted")
        }
        app.pcSync.playOnly(number, url)
        _message.value = "Irá al siguiente juego"
    }

    /** Ask the PC now («Actualizar» in Estado). */
    fun refreshPc() {
        app.pcSync.refresh()
    }

    /** Out of the queue (and off the PC) until the user sends it again. */
    fun removeDraft(number: Int) = run {
        app.pcSync.remove(number)
        _message.value = "Fuera de la cola"
    }

    /** «iniciar», «pausar», «reanudar», «detener» or «seguir» (go on now) on the PC program. */
    fun pcControl(action: String) = run {
        val s = app.pcSync.control(action)
        _message.value = when {
            action == "detener" -> "PC detenido"
            action == "seguir" -> "Sigo ya"
            s.paused -> "PC en pausa"
            else -> "PC ${s.text}"
        }
    }

    /** Switch of one game (the PC never plays one that is off). */
    fun setGame(kind: String, on: Boolean) = run { app.pcSync.setGame(kind, on) }

    /** «mandar» or «parar» for the automatic offers. */
    fun offersControl(action: String) = run {
        app.pcSync.offersControl(action)
        _message.value = if (action == "mandar") "Ofertas: en cuanto haya hueco empiezo" else "Ofertas paradas"
    }

    /** Checks the PC answers with this key before saving it. */
    fun savePc(url: String, token: String, done: () -> Unit) = run {
        val clean = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val link = PcLink(clean, token.trim())
        val s = app.pcSync.test(link)
        repo.setPc(link)
        _message.value = "PC conectado: ${s.text}"
        app.pcSync.kick()
        done()
    }

    fun forgetPc() = run { repo.setPc(null) }

    /** Photos the AI can look at: the main account's album first, then the photos of the other accounts. */
    suspend fun aiPhotos(number: Int): List<Uri> {
        val p = repo.state.catalog ?: return emptyList()
        return Catalog.allAccounts(p).flatMap { d ->
            if (d.id == p.mainAccount.id) albumPhotos(Sku.format(d.prefix, number, p.digits)) else pendingAccountPhotos(d.id, number)
        }.distinct()
    }

    fun setGeminiKey(key: String) = run {
        repo.setGeminiKey(key)
        _message.value = if (key.isBlank()) "Clave borrada" else "Clave guardada"
    }

    fun setUploaded(number: Int, accountId: String, uploaded: Boolean) = run {
        repo.setUploaded(number, accountId, uploaded)
    }

    fun setAccounts(number: Int, accounts: Set<String>) = run {
        repo.setAccounts(number, accounts)
        refresh()
    }

    fun setVintedProfile(id: String, account: String, profile: String, done: () -> Unit) = run {
        repo.setVintedProfile(id, account, profile)
        done()
    }

    // ---------- Vinted sales: the PC reads them, the phone asks and writes them down ----------

    /** How the PC's reading goes, in one line; null before knowing anything. */
    data class SalesReading(val working: Boolean, val text: String)

    private val _reading = MutableStateFlow<SalesReading?>(null)
    val reading: StateFlow<SalesReading?> = _reading.asStateFlow()
    private val readingLock = Mutex()

    /** What the PC has to be told about the sales and has not heard yet: kept until it answers. */
    private var pendingAsk: String? = null

    /** The app has come to the screen: the PC is to read again if its last reading is old. */
    fun salesOpened() {
        if (pendingAsk == null) pendingAsk = "refrescar"
    }

    /** Asks the PC to read the sales in Vinted now, or to stop. Without a connection it is told later. */
    fun readSales(start: Boolean) = run {
        if (repo.state.pc == null) throw CatalogException("Configura el PC en Ajustes")
        val action = if (start) "mandar" else "parar"
        pendingAsk = action
        refreshSales()
        if (pendingAsk == action) _message.value = "Sin conexión con el PC: se lo pediré en cuanto vuelva"
    }

    /**
     * How often to ask the PC for the sales on the screen shown now; null while photos are
     * being taken or something is being edited, so that nothing changes under the user.
     */
    fun salesEvery(): Long? {
        val top = stack.value.last()
        return when {
            repo.state.catalog?.session != null || top is Screen.Editor || top is Screen.Photos || top is Screen.Template -> null
            reading.value?.working == true -> 8_000
            top == Screen.Sales || top is Screen.Sale || pendingAsk != null -> 30_000
            else -> 5 * 60_000
        }
    }

    /** Tells the PC what is pending and takes what it has read so far. */
    suspend fun refreshSales() {
        val link = repo.state.pc ?: return
        if (repo.state.catalog == null) return
        val ask = pendingAsk
        try {
            val read = if (ask != null) tellSales(link, ask)
            else VintedSales.parsePc(withContext(Dispatchers.IO) { repo.pcClient.sales(link) })
            if (pendingAsk == ask) pendingAsk = null
            takeSales(read)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            val offline = e.message == PcClient.UNREACHABLE
            // An answer, even a bad one, means it heard: asking again would not change it.
            if (!offline && pendingAsk == ask) pendingAsk = null
            _reading.value = SalesReading(false, if (offline) offlineText() else e.message.orEmpty())
        } catch (e: Exception) {
            AppLog.w("ventas", "error: ${e.message ?: e.javaClass.simpleName}")
            _reading.value = SalesReading(false, "No se pudieron apuntar las ventas (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private suspend fun tellSales(link: PcLink, action: String): VintedSales.Reading {
        val p = repo.state.catalog ?: throw CatalogException("No hay catálogo")
        return VintedSales.parsePc(withContext(Dispatchers.IO) {
            repo.pcClient.salesControl(link, action, VintedSales.prefixes(p))
        })
    }

    private suspend fun takeSales(read: VintedSales.Reading) = readingLock.withLock {
        val before = repo.state.catalog ?: return@withLock
        if (read.lastRead > before.salesReadAt) repo.salesRead(read.lastRead)
        _reading.value = readingText(read)
        val current = repo.state.catalog ?: return@withLock
        val now = System.currentTimeMillis()
        val (after, counts) = VintedSales.applyAll(current, current.mainAccount.id, read.orders, now)
        if (after == current) return@withLock
        // Never cut between deleting photos and saving the state.
        val originals = withContext(NonCancellable) {
            repo.changeSales { VintedSales.applyAll(it, it.mainAccount.id, read.orders, now).first }
        }
        AppLog.i("ventas", "del PC ${read.orders.size}, cambios $counts, quedan ${read.pending}")
        summary(counts)?.let { _message.value = it }
        askToDelete(originals)
        app.pcSync.kick()
        refresh()
        viewModelScope.launch { app.pcSync.tellPc() }
    }

    private fun updated(): String =
        repo.state.catalog?.salesReadAt?.takeIf { it > 0 }?.let { "Actualizado ${shortTime(it)}" } ?: "Aún sin leer"

    private fun offlineText(): String = "${updated()}$GAP· PC sin conexión"

    private fun readingText(read: VintedSales.Reading): SalesReading = SalesReading(
        read.working,
        when {
            !read.working -> updated()
            read.waiting.isNotBlank() -> "PC: ${read.waiting}"
            read.pending > 0 -> "Leyendo en el PC$GAP· quedan ${read.pending}"
            else -> "Leyendo en el PC…"
        },
    )

    /** «hoy 22:10», or «26/11 22:10». */
    private fun shortTime(at: Long): String {
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return if (t.toLocalDate() == LocalDate.now()) "hoy ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        else t.format(DateTimeFormatter.ofPattern("d/M HH:mm"))
    }

    private fun summary(counts: Map<Kind, Int>): String? {
        fun n(kind: Kind, one: String, many: String) = counts[kind]?.let { "$it ${if (it == 1) one else many}" }
        val changes = listOfNotNull(
            n(Kind.NEW_PENDING, "venta nueva en curso", "ventas nuevas en curso"),
            n(Kind.NEW_COMPLETED, "venta completada nueva", "ventas completadas nuevas"),
            n(Kind.COMPLETED, "completada", "completadas"),
            n(Kind.UNPAID, "vuelve a pendiente de cobro", "vuelven a pendiente de cobro"),
            n(Kind.CANCELLED, "anulada por Vinted", "anuladas por Vinted"),
            n(Kind.REOPENED, "recuperada", "recuperadas"),
            n(Kind.CONFLICT, "para que la revises", "para que las revises"),
        )
        return if (changes.isEmpty()) null else "Ventas: ${changes.joinToString(", ")}"
    }

    // ---------- shipping labels: the PC takes them out, the phone keeps the PDF ----------

    /** How the labels asked for are going, in one line, while the PC is on them. */
    private val _labelsText = MutableStateFlow("")
    val labelsText: StateFlow<String> = _labelsText.asStateFlow()
    private val labelsLock = Mutex()

    /** Asks the PC for the labels of every sale still to be sent, or of the order [only]. */
    fun printLabels(only: String? = null) = run {
        val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
        val p = repo.state.catalog ?: throw CatalogException("No hay catálogo")
        val requests = ShippingLabels.requests(p, only)
        if (requests.isEmpty()) throw CatalogException("No hay ventas pendientes de enviar")
        labelsLock.withLock {
            val reply = ShippingLabels.parse(withContext(Dispatchers.IO) { repo.pcClient.labelsControl(link, "mandar", requests) })
            _labelsText.value = "Pedidas al PC…"
            repo.setLabels(LabelJob(lot = reply.lot))
            takeLabels(link, reply)
        }
    }

    fun stopLabels() = run {
        labelsLock.withLock {
            // Forgotten here even if the PC does not hear it: the user can always ask again.
            repo.setLabels(LabelJob())
            val link = repo.state.pc ?: return@withLock
            try {
                withContext(Dispatchers.IO) { repo.pcClient.labelsControl(link, "parar") }
            } catch (e: CatalogException) {
                if (e.message != PcClient.UNREACHABLE) throw e
                _message.value = "Sin conexión: el PC no sabe que has parado"
            }
        }
    }

    /** Puts away the finished PDF or the warnings; the file stays in Descargas. */
    fun dismissLabels() = run { repo.setLabels(LabelJob()) }

    /** Asks the PC how the pending labels go, and brings the PDF when they are done. */
    suspend fun refreshLabels() {
        val link = repo.state.pc ?: return
        if (repo.state.labels.lot == 0L) return
        try {
            labelsLock.withLock {
                if (repo.state.labels.lot == 0L) return@withLock
                takeLabels(link, ShippingLabels.parse(withContext(Dispatchers.IO) { repo.pcClient.labels(link) }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            _labelsText.value = if (e.message == PcClient.UNREACHABLE) "PC sin conexión: las recojo cuando vuelva" else e.message.orEmpty()
        } catch (e: Exception) {
            AppLog.w("etiquetas", "error: ${e.message ?: e.javaClass.simpleName}")
            _labelsText.value = "No se pudo guardar el PDF (${e.message ?: e.javaClass.simpleName})"
        }
    }

    /** Only inside [labelsLock]. */
    private suspend fun takeLabels(link: PcLink, reply: ShippingLabels.Reply) {
        val lot = repo.state.labels.lot
        when (val step = ShippingLabels.step(lot, reply)) {
            is ShippingLabels.Step.Wait -> _labelsText.value = step.text
            is ShippingLabels.Step.Fetch -> {
                _labelsText.value = "Trayendo el PDF…"
                val file = withContext(Dispatchers.IO) {
                    val pdf = ShippingLabels.parsePdf(repo.pcClient.labelsPdf(link))
                    // Another lot took its place meanwhile: the next question tells.
                    if (pdf.lot != lot) null else repo.labelFiles.save(pdf.name, pdf.bytes)
                } ?: return
                repo.keepLabels(LabelJob(file = file.toString(), count = step.done.labels, warnings = step.done.warnings), reply.ready)
                AppLog.i("etiquetas", "PDF con ${step.done.labels}, avisos ${step.done.warnings.size}")
                _message.value = "PDF de etiquetas guardado en Descargas"
            }
            is ShippingLabels.Step.Failed -> {
                repo.setLabels(LabelJob(warnings = step.warnings.ifEmpty { listOf("El PC no ha podido sacar ninguna etiqueta") }))
                _message.value = "No ha salido ninguna etiqueta"
            }
            ShippingLabels.Step.Dropped -> {
                repo.setLabels(LabelJob())
                _message.value = "El PC ha parado las etiquetas"
            }
        }
    }

    /** Opens the PDF in the label printer's app. */
    fun openLabels() = run {
        val file = repo.state.labels.file
        if (file.isBlank()) return@run
        openInPrinter(getApplication<Application>(), Uri.parse(file))?.let { _message.value = it }
    }

    // ---------- sales ----------

    private val _salesView = MutableStateFlow(SalesView())
    val salesView: StateFlow<SalesView> = _salesView.asStateFlow()
    /** Where the sales list was left. */
    val salesScroll = androidx.compose.foundation.lazy.LazyListState()

    fun showSales(view: SalesView) {
        _salesView.value = view
    }

    fun mergeSale(id: String) = sale("Unidas en una sola venta") { SalesRules.merge(it, id) }
    fun completeSale(id: String) = sale("Venta completada") { SalesRules.complete(it, id, System.currentTimeMillis()) }
    fun cancelSale(id: String) = sale("Venta anulada") { SalesRules.cancel(it, id, System.currentTimeMillis()) }
    fun releaseSale(id: String) = sale("Número libre: la venta sigue en curso") { SalesRules.release(it, id) }
    fun unpaySale(id: String) = sale("Venta en curso: pendiente de cobro") { SalesRules.unpay(it, id) }
    fun reopenSale(id: String) = sale("Venta recuperada: en curso") { SalesRules.reopen(it, id) }
    fun acceptSale(id: String) = sale("Venta completada") { SalesRules.accept(it, id, System.currentTimeMillis()) }
    fun assignSale(id: String, number: Int) = sale("Artículo asignado") { SalesRules.assign(it, id, number) }

    /** The picture kept from a sold article, if any. */
    suspend fun saleImage(sale: Sale): java.io.File? = repo.saleImages.get(sale.id)

    private fun sale(done: String, change: (CatalogState) -> CatalogState) = run {
        askToDelete(repo.changeSales(change))
        _message.value = done
        app.pcSync.kick()
        refresh()
        app.pcSync.tellPc()
    }

    // ---------- photo editing ----------

    /** Photos of [accountId] for [number] that can be edited on this phone, in order. */
    suspend fun editablePhotos(number: Int, accountId: String): List<Uri> {
        val p = repo.state.catalog ?: return emptyList()
        return if (accountId == p.mainAccount.id) albumPhotos(Sku.format(p.mainAccount.prefix, number, p.digits))
        else withContext(Dispatchers.IO) { repo.accountPhotos.files(accountId, number).map { Uri.fromFile(it) } }
    }

    /** The framed photo as JPEG bytes, made off the main thread. */
    suspend fun renderCrop(uri: Uri, framing: Framing): ByteArray = withContext(Dispatchers.Default) {
        PhotoCropper.render(getApplication<Application>().contentResolver, uri, framing)
    }

    private val _savingPhotos = MutableStateFlow(false)
    val savingPhotos: StateFlow<Boolean> = _savingPhotos.asStateFlow()

    fun savePhotos(number: Int, accountId: String, photos: List<CatalogRepository.EditedPhoto>) = run {
        if (photos.isEmpty()) throw CatalogException("Deja al menos una foto")
        _savingPhotos.value = true
        try {
            askToDelete(repo.rewritePhotos(number, accountId, photos))
            _message.value = "Fotos guardadas"
            back()
        } finally {
            _savingPhotos.value = false
            refresh()
        }
    }

    fun saveLot(id: String?, name: String, priceText: String, quantityText: String, done: () -> Unit) = run {
        val price = Money.parse(priceText) ?: throw CatalogException("Precio no válido")
        val quantity = quantityText.trim().toIntOrNull() ?: throw CatalogException("Número de prendas no válido")
        repo.saveLot(id, name, price, quantity)
        done()
    }

    fun deleteLot(id: String) = run { repo.deleteLot(id) }

    fun saveStyle(accountId: String, style: Int, template: String) = run {
        repo.setStyle(accountId, style, template)
        back()
    }

    // ---------- camera session ----------

    fun startSession(number: Int, accountId: String, openCamera: () -> Unit) = run {
        repo.startSession(number, accountId)
        openCamera()
    }

    fun cancelSession() = run { repo.endSession() }

    fun finishSession() = run {
        val photos = repo.sessionPhotos()
        if (photos.isEmpty()) {
            _message.value = "No hay fotos nuevas"
            return@run
        }
        _review.value = photos.map { it.uri }
        _selected.value = photos.map { it.uri }.toSet()
        go(Screen.SessionPhotos)
    }

    fun toggle(uri: Uri) {
        val s = _selected.value
        _selected.value = if (uri in s) s - uri else s + uri
    }

    fun saveReview() = run {
        val chosen = _review.value.filter { it in _selected.value }
        if (chosen.isEmpty()) return@run
        val number = state.value.catalog?.session?.number
        _progress.value = 0 to chosen.size
        try {
            val originals = repo.saveSession(chosen) { done -> _progress.value = done to chosen.size }
            _review.value = emptyList()
            _selected.value = emptySet()
            if (number != null) replaceTop(Screen.Article(number)) else back()
            askToDelete(originals)
        } finally {
            _progress.value = null
            refresh()
        }
    }

    /** [account]: the Vinted account that sold it (the main one when null). */
    fun registerSale(number: Int, priceText: String, costText: String, account: String?, paid: Boolean, freeNumber: Boolean) = run {
        val price = Money.parse(priceText) ?: throw CatalogException("Precio de venta no válido")
        val cost = if (costText.isBlank()) null else Money.parse(costText) ?: throw CatalogException("Coste no válido")
        askToDelete(repo.registerSale(number, price, cost, account, paid, freeNumber))
        _message.value = when {
            paid -> "Venta completada: número libre"
            freeNumber -> "Venta en curso y número libre. Márcala como cobrada cuando llegue el dinero"
            else -> "Venta en curso. Márcala como cobrada cuando llegue el dinero"
        }
        back()
        app.pcSync.tellPc()
    }

    /** «Resubir»: new photos, new title and hashtags; the rest of the article stays. */
    fun relist(number: Int, keepPhotos: Boolean = false) = run {
        askToDelete(app.pcSync.relist(number, keepPhotos))
        _message.value = if (keepPhotos) "Resubido con las fotos que ya tiene. La IA cambiará el título y los hashtags al preparar el borrador. Borra el anuncio antiguo en Vinted"
        else "Resubir: haz fotos nuevas. La IA cambiará el título y los hashtags al preparar el borrador. Borra el anuncio antiguo en Vinted"
        refresh()
    }

    fun deleteArticle(number: Int) = run {
        askToDelete(repo.deleteArticle(number))
        back()
        app.pcSync.tellPc()
    }

    // ---------- settings ----------

    private fun reportMoves(failed: Int) {
        if (failed > 0) _message.value = "$failed fotos no se pudieron mover (no las creó esta app)"
    }

    fun renameAccount(id: String, name: String, prefix: String, done: () -> Unit) = run {
        reportMoves(repo.renameAccount(id, name, prefix))
        done()
        refresh()
    }

    /** Adds a Vinted account (the one a second PC publishes in). Returns an error to show, or null when done. */
    fun addAccount(name: String, prefix: String): String? {
        val p = state.value.catalog ?: return "Configura primero este móvil"
        val cleanName = name.trim()
        val cleanPrefix = prefix.trim().uppercase()
        return try {
            val id = java.util.UUID.randomUUID().toString()
            Catalog.validateAccount(p, id, cleanName, cleanPrefix)
            run { repo.addAccount(VintedAccount(id, cleanName, cleanPrefix)); refresh() }
            null
        } catch (e: Exception) {
            e.message ?: "No válido"
        }
    }

    fun removeAccount(id: String) = run {
        repo.removeAccount(id)
        refresh()
    }

    fun setDigits(digits: Int) = run {
        reportMoves(repo.setDigits(digits))
        refresh()
    }

    fun stage(number: Int) = run {
        val n = repo.stage(number)
        _message.value = "$n fotos listas en «0 Subir»"
    }
}
