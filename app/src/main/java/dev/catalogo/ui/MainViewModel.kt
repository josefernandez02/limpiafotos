package dev.catalogo.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.catalogo.CatalogoApp
import dev.catalogo.core.AppState
import dev.catalogo.core.CatalogException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Article(val number: Int) : Screen
    data object Review : Screen
    data object Connect : Screen
    data object Settings : Screen
}

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
        if (s.size <= 1) return false
        if (s.last() == Screen.Connect) sync.stop()
        _stack.value = s.dropLast(1)
        refresh()
        return true
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

    fun newArticle() = run {
        val number = repo.newArticle()
        go(Screen.Article(number))
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

    fun releaseOnParent(number: Int) = run {
        askToDelete(repo.releaseOnParent(number))
        back()
    }

    // ---------- child ----------

    fun releaseOnChild(number: Int) = run {
        askToDelete(repo.releaseOnChild(number))
        refresh()
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
