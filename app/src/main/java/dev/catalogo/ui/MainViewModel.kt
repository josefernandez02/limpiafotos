package dev.catalogo.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.catalogo.CatalogoApp
import dev.catalogo.core.AiResult
import dev.catalogo.core.AppState
import dev.catalogo.core.Catalog
import dev.catalogo.core.Sku
import dev.catalogo.core.CatalogException
import dev.catalogo.core.ParentState
import dev.catalogo.data.AppLog
import dev.catalogo.core.SalesSync
import dev.catalogo.core.VintedSales.Kind
import dev.catalogo.core.Sales as SalesRules
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import dev.catalogo.core.Device
import dev.catalogo.core.Listing
import dev.catalogo.core.Money
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

sealed interface Screen {
    /** Main tabs: articles, status, sales, inventory. */
    data object Home : Screen
    data object Sales : Screen
    data class Sale(val id: String) : Screen
    data object Lots : Screen
    data object Status : Screen

    data class Article(val number: Int) : Screen
    /** New article when [number] is null. */
    data class Editor(val number: Int?) : Screen
    data object Review : Screen
    data object Settings : Screen
    data class Template(val deviceId: String) : Screen
    /** Order, cover and crop of [deviceId]'s photos of [number]. */
    data class Photos(val number: Int, val deviceId: String) : Screen
    /** Reads sales and wardrobes in Vinted; with [auto] it starts as soon as the page is ready. */
    data class Vinted(val auto: Boolean = false) : Screen
}

private const val VINTED_PACKAGE = "fr.vinted"

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

    /** Main phone: article number → cover from any account (own album, outbox or sent copies). */
    private val _articleCovers = MutableStateFlow<Map<Int, Uri>>(emptyMap())
    val articleCovers: StateFlow<Map<Int, Uri>> = _articleCovers.asStateFlow()

    /** Clean photos waiting in the outbox: deviceId → number → count. */
    private val _outbox = MutableStateFlow<Map<String, Map<Int, Int>>>(emptyMap())
    val outbox: StateFlow<Map<String, Map<Int, Int>>> = _outbox.asStateFlow()

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

    /** Photos for [deviceId]'s account kept on this phone: waiting to be sent, then reduced copies of the sent ones. */
    suspend fun outboxPhotos(deviceId: String, number: Int): List<Uri> = withContext(Dispatchers.IO) {
        (repo.outbox.files(deviceId, number) + repo.previews.files(number, deviceId)).map { Uri.fromFile(it) }
    }

    init {
        // Each phone keeps its albums where its own gallery shows them.
        run {
            repo.ensureDistinctStyles()
            repo.adoptAccounts()
            repo.ensureSaleIds()
            reportMoves(repo.adoptFolder(dev.catalogo.data.Folders.auto()))
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
        app.drafts.refresh()
        val base = repo.base
        val s = state.value
        val p = s.parent
        var articleCovers = emptyMap<Int, Uri>()
        val (albums, outbox) = withContext(Dispatchers.IO) {
            val a = runCatching { repo.gallery.albums(base) }.getOrDefault(emptyMap())
            val pending = p?.children?.associate { d -> d.id to repo.outbox.pending(d.id) } ?: emptyMap()
            if (p != null) {
                articleCovers = p.articles.filter { !it.released }.mapNotNull { art ->
                    val own = a[Sku.format(p.self.prefix, art.number, p.digits)]?.cover
                    val cover = own
                        ?: p.children.firstNotNullOfOrNull { d -> pending[d.id]?.get(art.number)?.firstOrNull() }?.let { Uri.fromFile(it) }
                        ?: repo.previews.files(art.number).firstOrNull()?.let { Uri.fromFile(it) }
                    cover?.let { art.number to it }
                }.toMap()
            }
            a to pending.mapValues { (_, byNumber) -> byNumber.mapValues { it.value.size } }
        }
        _counts.value = albums.mapValues { it.value.count }
        _covers.value = albums.mapValues { it.value.cover }
        _articleCovers.value = articleCovers
        _outbox.value = outbox
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

    fun setupParent(name: String, prefix: String) = run {
        repo.setupParent(name, prefix)
        repo.adoptFolder(dev.catalogo.data.Folders.auto())
    }

    // ---------- parent ----------

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
    val pc = app.drafts.pc
    val incidents = app.drafts.incidents
    val offers = app.drafts.offers

    fun incidentReviewed(id: String) = run { app.drafts.reviewed(id) }

    /** «Generar y preparar borrador»: AI, checks and sending happen in the background. */
    /** «Generar y preparar borrador» with the photos the AI should look at (the PC gets all of them). */
    fun prepareDraft(number: Int, photos: List<Uri>, mode: String = dev.catalogo.core.Mode.UPLOAD_PLAY) = run {
        if (repo.state.pc == null) throw CatalogException("Configura el PC en Ajustes")
        if (photos.isEmpty()) throw CatalogException("Elige al menos una foto")
        repo.setPhotos(number, photos.map { it.toString() })
        app.drafts.prepareNow(number, mode)
        _message.value = "En cola: puedes salir de la app y seguir con otros artículos"
    }

    /** Photos of [number]'s album, and which ones the AI looks at. */
    suspend fun vintedPhotos(number: Int): Pair<List<Uri>, List<Uri>> {
        val p = repo.state.parent ?: return emptyList<Uri>() to emptyList()
        val album = albumPhotos(Sku.format(p.self.prefix, number, p.digits))
        val chosen = Catalog.chosenPhotos(album, Catalog.article(p, number)?.photos.orEmpty()) { it.toString() }
        return album to chosen
    }

    fun fixDraft(number: Int, fixes: dev.catalogo.core.Draft.Fixes) = run {
        app.drafts.fix(number, fixes)
        _message.value = "Revisado: sigue su camino al PC"
    }

    fun retryDraft(number: Int) = run { app.drafts.retry(number) }

    /** Where it stands, set by hand (also tells the PC). */
    fun setStage(number: Int, stage: dev.catalogo.core.Stage, url: String = "") = run {
        app.drafts.setStage(number, stage, url)
        _message.value = "Estado: ${stage.label}"
    }

    /** The same status for several articles; those the PC cannot change now are reported. */
    fun setStageMany(numbers: List<Int>, stage: dev.catalogo.core.Stage) = run {
        val failed = app.drafts.setStageMany(numbers, stage)
        _message.value = if (failed.isEmpty()) "${numbers.size} en «${stage.label}»"
        else "${numbers.size - failed.size} cambiados; ${failed.size} no (¿PC sin conexión?)"
    }

    fun setMode(number: Int, mode: String) = run {
        app.drafts.setMode(number, mode)
        _message.value = dev.catalogo.core.Mode.label(mode)
    }

    /** «Solo jugar»: an article already on Vinted goes to the next game with its link. */
    fun playOnly(number: Int, url: String) = run {
        if (!Regex("^https://(www\\.)?vinted\\.[a-z.]{2,8}/items/\\d+").containsMatchIn(url.trim())) {
            throw CatalogException("Pega el link del artículo en Vinted")
        }
        app.drafts.playOnly(number, url)
        _message.value = "Irá al siguiente juego"
    }

    /** Ask the PC now («Actualizar» in Estado). */
    fun refreshPc() {
        app.drafts.refresh()
    }

    /** Out of the queue (and off the PC) until the user sends it again. */
    fun removeDraft(number: Int) = run {
        app.drafts.remove(number)
        _message.value = "Fuera de la cola"
    }

    /** «iniciar», «pausar», «reanudar», «detener» or «seguir» (go on now) on the PC program. */
    fun pcControl(action: String) = run {
        val s = app.drafts.control(action)
        _message.value = when {
            action == "detener" -> "PC detenido"
            action == "seguir" -> "Sigo ya"
            s.paused -> "PC en pausa"
            else -> "PC ${s.text}"
        }
    }

    /** Switch of one game (the PC never plays one that is off). */
    fun setGame(kind: String, on: Boolean) = run { app.drafts.setGame(kind, on) }

    /** «mandar» or «parar» for the automatic offers. */
    fun offersControl(action: String) = run {
        app.drafts.offersControl(action)
        _message.value = if (action == "mandar") "Ofertas: en cuanto haya hueco empiezo" else "Ofertas paradas"
    }

    /** Checks the PC answers with this key before saving it. */
    fun savePc(url: String, token: String, done: () -> Unit) = run {
        val clean = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val link = dev.catalogo.core.PcLink(clean, token.trim())
        val s = app.drafts.test(link)
        repo.setPc(link)
        _message.value = "PC conectado: ${s.text}"
        app.drafts.kick()
        done()
    }

    fun forgetPc() = run { repo.setPc(null) }

    /** Photos the AI can look at: main phone's album first, then photos waiting for other phones. */
    suspend fun aiPhotos(number: Int): List<Uri> {
        val p = repo.state.parent ?: return emptyList()
        return Catalog.devices(p).flatMap { d ->
            if (d.id == p.self.id) albumPhotos(Sku.format(d.prefix, number, p.digits)) else outboxPhotos(d.id, number)
        }.distinct()
    }

    fun setGeminiKey(key: String) = run {
        repo.setGeminiKey(key)
        _message.value = if (key.isBlank()) "Clave borrada" else "Clave guardada"
    }

    fun setUploaded(number: Int, deviceId: String, uploaded: Boolean) = run {
        repo.setUploaded(number, deviceId, uploaded)
    }

    fun setAccounts(number: Int, accounts: Set<String>) = run {
        repo.setAccounts(number, accounts)
        refresh()
    }

    fun setAccount(id: String, account: String, profile: String, done: () -> Unit) = run {
        repo.setAccount(id, account, profile)
        done()
    }

    // ---------- Vinted sales ----------

    /** Progress and result of the synchronisation, one line each. */
    data class VintedCheck(val running: Boolean, val lines: List<String>)

    private val _vinted = MutableStateFlow<VintedCheck?>(null)
    val vinted: StateFlow<VintedCheck?> = _vinted.asStateFlow()
    private var syncing: kotlinx.coroutines.Job? = null

    /** Brings in the sales of the account signed in to the [page], with requests made by that page. */
    fun syncSales(page: SalesSync.Page) {
        if (syncing?.isActive == true) return
        syncing = viewModelScope.launch {
            if (repo.state.parent == null) return@launch
            // Gallery originals of finished sales: one question for all of them at the end.
            val originals = ArrayList<Uri>()
            try {
                val book = object : SalesSync.Book {
                    override fun state() = repo.catalog()
                    override suspend fun change(block: (ParentState) -> ParentState) {
                        // Never cut between deleting photos and saving the state.
                        withContext(NonCancellable) { originals += repo.changeSales(block) }
                    }
                }
                _vinted.value = VintedCheck(true, listOf("Leyendo tus ventas…"))
                val lines = try {
                    val report = SalesSync(page, book) { System.currentTimeMillis() }.run { _vinted.value = VintedCheck(true, listOf(it)) }
                    AppLog.i("ventas", "leídas ${report.read}, cambios ${report.counts}, quedan ${report.left}, fallos ${report.failed}, parada ${report.stop}")
                    summary(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CatalogException) {
                    listOf(e.message.orEmpty())
                } catch (e: Exception) {
                    AppLog.w("ventas", "error: ${e.message ?: e.javaClass.simpleName}")
                    listOf("Error al leer las ventas (${e.message ?: e.javaClass.simpleName})")
                }
                _vinted.value = VintedCheck(false, lines)
            } finally {
                // Left half way: no half-way progress stays behind.
                if (!isActive && syncing === coroutineContext[kotlinx.coroutines.Job]) _vinted.value = null
                askToDelete(originals)
                app.drafts.kick()
                refresh()
                viewModelScope.launch { app.drafts.tellPc() }
            }
        }
    }

    private fun summary(r: SalesSync.Report): List<String> {
        fun n(count: Int, one: String, many: String) = if (count == 0) null else "$count ${if (count == 1) one else many}"
        val changes = listOfNotNull(
            n(r.count(Kind.NEW_PENDING), "venta nueva en curso", "ventas nuevas en curso"),
            n(r.count(Kind.NEW_COMPLETED), "venta nueva completada", "ventas nuevas completadas"),
            n(r.count(Kind.COMPLETED), "completada", "completadas"),
            n(r.count(Kind.CANCELLED), "anulada por Vinted", "anuladas por Vinted"),
            n(r.count(Kind.NEW_CANCELLED), "cancelada antigua", "canceladas antiguas"),
            n(r.count(Kind.REOPENED), "recuperada", "recuperadas"),
            n(r.count(Kind.CONFLICT), "para que la revises", "para que las revises"),
        )
        val lines = ArrayList<String>()
        lines += "Ventas de ${r.account}: ${if (changes.isEmpty()) "sin novedades" else changes.joinToString(", ")}"
        if (r.left > 0) {
            val why = when (r.stop) {
                SalesSync.Stop.LIMIT -> "Vinted pide ir más despacio: vuelve a pulsar en unos minutos"
                SalesSync.Stop.LOGIN -> "inicia sesión arriba y vuelve a pulsar"
                else -> "vuelve a pulsar para reintentarlo"
            }
            lines += "Faltan ${r.left} por revisar: $why"
        }
        return lines
    }

    /** Leaving the screen ends the synchronisation: its requests need the page. */
    fun leaveVinted() {
        syncing?.cancel()
        _vinted.value = null
    }

    // ---------- sales ----------

    private val _salesView = MutableStateFlow(SalesView())
    val salesView: StateFlow<SalesView> = _salesView.asStateFlow()
    /** Where the sales list was left. */
    val salesScroll = androidx.compose.foundation.lazy.LazyListState()

    fun showSales(view: SalesView) {
        _salesView.value = view
    }

    fun completeSale(id: String) = sale("Venta completada") { SalesRules.complete(it, id, System.currentTimeMillis()) }
    fun cancelSale(id: String) = sale("Venta anulada: todo vuelve como estaba") { SalesRules.cancel(it, id, System.currentTimeMillis()) }
    fun reopenSale(id: String) = sale("Venta recuperada: en curso") { SalesRules.reopen(it, id) }
    fun acceptSale(id: String) = sale("Venta completada") { SalesRules.accept(it, id, System.currentTimeMillis()) }
    fun assignSale(id: String, number: Int) = sale("Artículo asignado") { SalesRules.assign(it, id, number) }

    /** A sale picture: the one kept from the article, or Vinted's, downloaded once. */
    suspend fun saleImage(sale: dev.catalogo.core.Sale): java.io.File? = repo.saleImages.get(sale.id, sale.photo)

    private fun sale(done: String, change: (ParentState) -> ParentState) = run {
        askToDelete(repo.changeSales(change))
        _message.value = done
        app.drafts.kick()
        refresh()
        app.drafts.tellPc()
    }

    // ---------- photo editing ----------

    /** Photos of [deviceId] for [number] that can be edited on this phone, in order. */
    suspend fun editablePhotos(number: Int, deviceId: String): List<Uri> {
        val p = repo.state.parent ?: return emptyList()
        return if (deviceId == p.self.id) albumPhotos(Sku.format(p.self.prefix, number, p.digits))
        else withContext(Dispatchers.IO) { repo.outbox.files(deviceId, number).map { Uri.fromFile(it) } }
    }

    /** The framed photo as JPEG bytes, made off the main thread. */
    suspend fun renderCrop(uri: Uri, framing: dev.catalogo.data.Framing): ByteArray = withContext(Dispatchers.Default) {
        dev.catalogo.data.Crop.render(getApplication<Application>().contentResolver, uri, framing)
    }

    private val _savingPhotos = MutableStateFlow(false)
    val savingPhotos: StateFlow<Boolean> = _savingPhotos.asStateFlow()

    fun savePhotos(number: Int, deviceId: String, photos: List<dev.catalogo.data.Repo.EditedPhoto>) = run {
        if (photos.isEmpty()) throw CatalogException("Deja al menos una foto")
        _savingPhotos.value = true
        try {
            askToDelete(repo.rewritePhotos(number, deviceId, photos))
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

    fun saveStyle(deviceId: String, style: Int, template: String) = run {
        repo.setStyle(deviceId, style, template)
        back()
    }

    // ---------- publishing helpers ----------

    fun copy(label: String, text: String) {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        _message.value = "$label copiado"
    }

    fun copyDescription(text: String) {
        copy("Descripción", text)
        _message.value = "Descripción copiada"
    }

    fun openVinted() {
        val context = getApplication<Application>()
        val intent = context.packageManager.getLaunchIntentForPackage(VINTED_PACKAGE)
        if (intent == null) {
            _message.value = "Vinted no está instalada"
            return
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun startSession(number: Int, deviceId: String, openCamera: () -> Unit) = run {
        repo.startSession(number, deviceId)
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
        go(Screen.Review)
    }

    fun toggle(uri: Uri) {
        val s = _selected.value
        _selected.value = if (uri in s) s - uri else s + uri
    }

    fun saveReview() = run {
        val chosen = _review.value.filter { it in _selected.value }
        if (chosen.isEmpty()) return@run
        val number = state.value.parent?.session?.number
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

    /** [account]: the phone whose Vinted account sold it (the main phone when null). */
    fun registerSale(number: Int, priceText: String, costText: String, account: String? = null) = run {
        val price = Money.parse(priceText) ?: throw CatalogException("Precio de venta no válido")
        val cost = if (costText.isBlank()) null else Money.parse(costText) ?: throw CatalogException("Coste no válido")
        askToDelete(repo.registerSale(number, price, cost, account))
        _message.value = "Venta en curso. Cuando llegue el dinero quedará completada"
        back()
        app.drafts.tellPc()
    }

    /** «Resubir»: new photos, new title and hashtags; the rest of the article stays. */
    fun relist(number: Int, keepPhotos: Boolean = false) = run {
        askToDelete(app.drafts.relist(number, keepPhotos))
        _message.value = if (keepPhotos) "Resubido con las fotos que ya tiene. La IA cambiará el título y los hashtags al preparar el borrador. Borra el anuncio antiguo en Vinted"
        else "Resubir: haz fotos nuevas. La IA cambiará el título y los hashtags al preparar el borrador. Borra el anuncio antiguo en Vinted"
        refresh()
    }

    fun deleteArticle(number: Int) = run {
        askToDelete(repo.deleteArticle(number))
        back()
    }

    // ---------- settings ----------

    private fun reportMoves(failed: Int) {
        if (failed > 0) _message.value = "$failed fotos no se pudieron mover (no las creó esta app)"
    }

    fun renameDevice(id: String, name: String, prefix: String, done: () -> Unit) = run {
        reportMoves(repo.renameDevice(id, name, prefix))
        done()
        refresh()
    }

    /** Adds a Vinted account (the one a second PC publishes in). Returns an error to show, or null when done. */
    fun addAccount(name: String, prefix: String): String? {
        val p = state.value.parent ?: return "Configura primero este móvil"
        val cleanName = name.trim()
        val cleanPrefix = prefix.trim().uppercase()
        return try {
            val id = java.util.UUID.randomUUID().toString()
            Catalog.validateDevice(p, id, cleanName, cleanPrefix)
            run { repo.addChild(Device(id, cleanName, cleanPrefix)); refresh() }
            null
        } catch (e: Exception) {
            e.message ?: "No válido"
        }
    }

    fun removeChild(id: String) = run {
        repo.removeChild(id)
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
