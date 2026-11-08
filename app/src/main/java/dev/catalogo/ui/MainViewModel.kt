package dev.catalogo.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.catalogo.CatalogoApp
import dev.catalogo.core.Ai
import dev.catalogo.core.AiException
import dev.catalogo.core.AiResult
import dev.catalogo.core.AiSuggestion
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
import kotlinx.coroutines.launch
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

    /** Clean photos still waiting to be sent to [deviceId]. */
    suspend fun outboxPhotos(deviceId: String, number: Int): List<Uri> =
        withContext(Dispatchers.IO) { repo.outbox.files(deviceId, number).map { Uri.fromFile(it) } }

    init {
        // Each phone keeps its albums where its own gallery shows them.
        run {
            repo.ensureDistinctStyles()
            reportMoves(repo.adoptFolder(dev.catalogo.data.Folders.auto()))
            refresh()
        }
    }

    // ---------- navigation ----------

    fun go(screen: Screen) {
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

    private fun replaceTop(screen: Screen) {
        _stack.value = _stack.value.dropLast(1) + screen
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
        val (albums, outbox) = withContext(Dispatchers.IO) {
            val a = runCatching { repo.gallery.albums(base) }.getOrDefault(emptyMap())
            val o = s.parent?.children?.associate { d ->
                d.id to repo.outbox.pending(d.id).mapValues { it.value.size }
            } ?: emptyMap()
            a to o
        }
        _counts.value = albums.mapValues { it.value.count }
        _covers.value = albums.mapValues { it.value.cover }
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

    fun saveArticle(number: Int?, listing: Listing, lotId: String?, costText: String, ai: AiResult?) = run {
        val cost = if (lotId != null || costText.isBlank()) null
        else Money.parse(costText) ?: throw CatalogException("Coste no válido")
        if (number == null) {
            val created = repo.newArticle(listing, lotId, cost, ai)
            replaceTop(Screen.Article(created))
        } else {
            repo.updateArticle(number, listing, lotId, cost, ai)
            back()
        }
    }

    /** Photos the AI can look at: main phone's album first, then photos waiting for other phones. */
    suspend fun aiPhotos(number: Int): List<Uri> {
        val p = repo.state.parent ?: return emptyList()
        return Catalog.devices(p).flatMap { d ->
            if (d.id == p.self.id) albumPhotos(Sku.format(d.prefix, number, p.digits)) else outboxPhotos(d.id, number)
        }.distinct()
    }

    fun suggestWithAi(number: Int, listing: Listing, photos: List<Uri>, done: (AiSuggestion?, String) -> Unit) = viewModelScope.launch {
        try {
            val p = repo.state.parent ?: throw CatalogException("Solo desde el móvil principal")
            if (photos.isEmpty() || photos.size > Ai.PHOTOS) throw CatalogException("Elige de 1 a ${Ai.PHOTOS} fotos")
            val answer = repo.gemini.suggest(repo.state.geminiKey, listing, Catalog.aiAccounts(p), photos)
            done(answer.suggestion, answer.model)
        } catch (e: AiException) {
            _message.value = e.message
            done(null, "")
        } catch (e: CatalogException) {
            _message.value = e.message
            done(null, "")
        } catch (e: Exception) {
            _message.value = "Error con la IA: ${e.message ?: e.javaClass.simpleName}"
            done(null, "")
        }
    }

    fun setGeminiKey(key: String) = run {
        repo.setGeminiKey(key)
        _message.value = if (key.isBlank()) "Clave borrada" else "Clave guardada"
    }

    fun setUploaded(number: Int, deviceId: String, uploaded: Boolean) = run {
        repo.setUploaded(number, deviceId, uploaded)
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

    fun sellOnParent(number: Int, priceText: String, costText: String) = run {
        val price = Money.parse(priceText) ?: throw CatalogException("Precio de venta no válido")
        val cost = if (costText.isBlank()) null else Money.parse(costText) ?: throw CatalogException("Coste no válido")
        askToDelete(repo.sellOnParent(number, price, cost))
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
