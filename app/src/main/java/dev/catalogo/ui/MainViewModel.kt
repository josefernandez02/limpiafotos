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
import dev.catalogo.core.model.Money
import dev.catalogo.core.model.Sale
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.VintedAccount
import dev.catalogo.core.sales.Sales
import dev.catalogo.data.CatalogRepository
import dev.catalogo.data.pc.relist
import dev.catalogo.data.pc.tellPc
import dev.catalogo.data.photo.Framing
import dev.catalogo.data.photo.GalleryFolders
import dev.catalogo.data.photo.PhotoCropper
import dev.catalogo.ui.photos.openCamera
import dev.catalogo.ui.sales.SalesView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    internal val app = application as CatalogoApp
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

    internal val _message = MutableStateFlow<String?>(null)
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

    internal fun run(block: suspend () -> Unit) = viewModelScope.launch {
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

    internal fun askToDelete(uris: List<Uri>) {
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

    internal val _reading = MutableStateFlow<SalesReading?>(null)
    val reading: StateFlow<SalesReading?> = _reading.asStateFlow()
    internal val readingLock = Mutex()

    /** What the PC has to be told about the sales and has not heard yet: kept until it answers. */
    internal var pendingAsk: String? = null

    // ---------- shipping labels: the PC takes them out, the phone keeps the PDF ----------

    /** How the labels asked for are going, in one line, while the PC is on them. */
    internal val _labelsText = MutableStateFlow("")
    val labelsText: StateFlow<String> = _labelsText.asStateFlow()
    internal val labelsLock = Mutex()

    // ---------- sales ----------

    internal val _salesView = MutableStateFlow(SalesView())
    val salesView: StateFlow<SalesView> = _salesView.asStateFlow()
    /** Where the sales list was left. */
    val salesScroll = androidx.compose.foundation.lazy.LazyListState()

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
