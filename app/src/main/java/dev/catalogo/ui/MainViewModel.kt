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
    /** Main tabs on the parent: articles, lots, connect. */
    data object Home : Screen
    data object Lots : Screen
    data object Connect : Screen

    data class Article(val number: Int) : Screen
    /** New article when [number] is null. */
    data class Editor(val number: Int?) : Screen
    data object Review : Screen
    data object Settings : Screen
    data class Template(val deviceId: String) : Screen
    /** A child's album. */
    data class Album(val number: Int) : Screen
    /** Order, cover and crop of [deviceId]'s photos of [number]. */
    data class Photos(val number: Int, val deviceId: String) : Screen
    /** Reads every account's Vinted wardrobe. */
    data object Vinted : Screen
}

private const val VINTED_PACKAGE = "fr.vinted"

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CatalogoApp
    val repo = app.repo
    val sync = app.sync
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
            if (s.last() == Screen.Lots || s.last() == Screen.Connect) {
                tab(Screen.Home)
                return true
            }
            return false
        }
        if (s.last() == Screen.Connect) sync.stop()
        _stack.value = s.dropLast(1)
        refresh()
        return true
    }

    /** Switches main tab; leaving Connect stops the link. */
    fun tab(screen: Screen) {
        if (_stack.value.last() == Screen.Connect && screen != Screen.Connect) sync.stop()
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

    fun setupChild() = run { repo.setupChild() }

    fun resetRole() = run {
        repo.resetRole()
        _stack.value = listOf(Screen.Home)
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

    /** «Generar y preparar borrador»: AI, checks and sending happen in the background. */
    /** «Generar y preparar borrador» with the photos the AI should look at (the PC gets all of them). */
    fun prepareDraft(number: Int, photos: List<Uri>) = run {
        if (repo.state.pc == null) throw CatalogException("Configura el PC en Ajustes")
        if (photos.isEmpty()) throw CatalogException("Elige al menos una foto")
        repo.setPhotos(number, photos.map { it.toString() })
        app.drafts.prepareNow(number)
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

    /** «iniciar», «pausar» or «reanudar» the PC program. */
    fun pcControl(action: String) = run {
        val s = app.drafts.control(action)
        _message.value = if (s.paused) "PC en pausa" else "PC: ${s.text}"
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

    // ---------- Vinted wardrobes ----------

    /** Progress and result of reading the wardrobes, one line per account. */
    data class VintedCheck(val running: Boolean, val lines: List<String>, val sold: List<Int> = emptyList())

    private val _vinted = MutableStateFlow<VintedCheck?>(null)
    val vinted: StateFlow<VintedCheck?> = _vinted.asStateFlow()

    /**
     * Reads every account that has a profile link, page by page, through
     * [fetch] (a request made by the Vinted page itself) and links the
     * listings found to their articles.
     */
    fun checkVinted(fetch: suspend (String) -> Pair<Int, String>) {
        if (_vinted.value?.running == true) return
        viewModelScope.launch {
            val p = repo.state.parent ?: return@launch
            val accounts = Catalog.devices(p).filter { it.vintedId.isNotBlank() }
            if (accounts.isEmpty()) {
                _vinted.value = VintedCheck(false, listOf("Añade el enlace del perfil de Vinted de cada cuenta en Ajustes"))
                return@launch
            }
            val lines = ArrayList<String>()
            val sold = ArrayList<Int>()
            for (d in accounts) {
                _vinted.value = VintedCheck(true, lines + "${d.label}: leyendo…")
                val line = try {
                    val items = ArrayList<dev.catalogo.core.VintedWardrobe.Remote>()
                    for (page in 1..dev.catalogo.core.VintedWardrobe.MAX_PAGES) {
                        val (code, body) = fetch(dev.catalogo.core.VintedWardrobe.path(d.vintedId, page))
                        when (code) {
                            200 -> Unit
                            401, 403 -> throw CatalogException("Vinted pide iniciar sesión o una comprobación: hazlo arriba y vuelve a pulsar")
                            0 -> throw CatalogException("Sin conexión con Vinted")
                            else -> throw CatalogException("Vinted ha respondido $code")
                        }
                        val got = dev.catalogo.core.VintedWardrobe.parse(body)
                        if (got.isEmpty()) break
                        items += got
                    }
                    val r = repo.applyWardrobe(d.id, items)
                    sold += r.sold
                    buildString {
                        append("${d.label}: ${items.size} anuncios, ${r.found} enlazados")
                        if (r.newlyUploaded > 0) append(", ${r.newlyUploaded} marcados como subidos")
                        if (r.unmatched.isNotEmpty()) append(", ${r.unmatched.size} sin reconocer")
                    }
                } catch (e: CatalogException) {
                    "${d.label}: ${e.message}"
                } catch (e: Exception) {
                    "${d.label}: error (${e.message ?: e.javaClass.simpleName})"
                }
                lines += line
            }
            _vinted.value = VintedCheck(false, lines, sold.distinct())
            refresh()
        }
    }

    fun clearVinted() {
        if (_vinted.value?.running != true) _vinted.value = null
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
    fun sellOnParent(number: Int, priceText: String, costText: String, account: String? = null) = run {
        val price = Money.parse(priceText) ?: throw CatalogException("Precio de venta no válido")
        val cost = if (costText.isBlank()) null else Money.parse(costText) ?: throw CatalogException("Coste no válido")
        askToDelete(repo.sellOnParent(number, price, cost, account))
        back()
    }

    // ---------- child ----------

    fun deleteArticle(number: Int) = run {
        askToDelete(repo.deleteArticle(number))
        back()
    }

    fun deleteOnChild(number: Int) = run {
        askToDelete(repo.releaseOnChild(number, null))
        if (_stack.value.last() is Screen.Album) back() else refresh()
    }

    fun sellOnChild(number: Int, priceText: String) = run {
        val price = Money.parse(priceText) ?: throw CatalogException("Precio de venta no válido")
        askToDelete(repo.releaseOnChild(number, price))
        if (_stack.value.last() is Screen.Album) back() else refresh()
    }

    fun unpair() = run {
        sync.stop()
        repo.unpair()
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
