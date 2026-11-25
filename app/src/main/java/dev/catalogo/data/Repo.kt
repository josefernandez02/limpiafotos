package dev.catalogo.data

import android.content.Context
import android.net.Uri
import dev.catalogo.core.AiResult
import dev.catalogo.core.AiSuggestion
import dev.catalogo.core.AppState
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogException
import dev.catalogo.core.Device
import dev.catalogo.core.Ledger
import dev.catalogo.core.Listing
import dev.catalogo.core.ListingText
import dev.catalogo.core.Ids
import dev.catalogo.core.ParentState
import dev.catalogo.core.Session
import dev.catalogo.core.Sku
import dev.catalogo.core.VintedWardrobe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every operation that touches state, gallery and outbox together. */
class Repo(context: Context) {
    private val resolver = context.contentResolver
    val store = Store(context.filesDir)
    val gallery = Gallery(context)
    val outbox = Outbox(context.filesDir)
    val gemini = Gemini(context.contentResolver)
    val previews = Previews(context.filesDir)
    val desk = Desk(context.contentResolver)

    val state: AppState get() = store.value
    val base: String get() = state.baseFolder

    private fun parent(): ParentState = state.parent ?: throw CatalogException("Este móvil no es el principal")

    fun sku(device: Device, number: Int, digits: Int) = Sku.format(device.prefix, number, digits)

    // ---------- setup ----------

    suspend fun setupParent(name: String, prefix: String) {
        store.update { s ->
            val self = Device(s.deviceId, name.trim(), prefix.trim().uppercase())
            Catalog.validateDevice(ParentState(self = self), self.id, self.name, self.prefix)
            s.copy(parent = ParentState(self = self))
        }
    }

    // ---------- parent: articles and photo sessions ----------

    suspend fun newArticle(listing: Listing?, lotId: String?, cost: Long?, ai: AiResult?): Int {
        var number = -1
        store.update { s ->
            val (created, n) = Catalog.create(s.parent!!, System.currentTimeMillis())
            number = n
            s.copy(parent = Catalog.updateArticle(created, n, listing, lotId, cost, ai))
        }
        return number
    }

    suspend fun updateArticle(number: Int, listing: Listing?, lotId: String?, cost: Long?, ai: AiResult?) {
        store.update { s -> s.copy(parent = Catalog.updateArticle(s.parent!!, number, listing, lotId, cost, ai)) }
    }

    suspend fun applyAi(number: Int, suggestion: AiSuggestion, model: String, seconds: Int) {
        store.update { s -> s.copy(parent = Catalog.applyAi(s.parent!!, number, suggestion, model, seconds)) }
    }

    suspend fun setPhotos(number: Int, photos: List<String>) {
        store.update { s -> s.copy(parent = Catalog.setPhotos(s.parent!!, number, photos)) }
    }

    suspend fun setPrice(number: Int, price: Long?) {
        store.update { s -> s.copy(parent = Catalog.setPrice(s.parent!!, number, price)) }
    }

    /** Updates [number]'s draft status with [block] (it receives the current one). */
    suspend fun updateDesk(number: Int, block: (dev.catalogo.core.Desk?) -> dev.catalogo.core.Desk?) {
        store.update { s ->
            val p = s.parent ?: return@update s
            val a = Catalog.article(p, number) ?: return@update s
            s.copy(parent = Catalog.setDesk(p, number, block(a.desk)))
        }
    }

    suspend fun setPc(link: dev.catalogo.core.PcLink?) {
        store.update { it.copy(pc = link) }
    }

    suspend fun setGeminiKey(key: String) {
        store.update { it.copy(geminiKey = key.trim()) }
    }

    suspend fun setUploaded(number: Int, deviceId: String, uploaded: Boolean) {
        store.update { s -> s.copy(parent = Catalog.setUploaded(s.parent!!, number, deviceId, uploaded)) }
    }

    /**
     * Accounts [number] will be published in. The photos are always taken on
     * this phone: a chosen account of another phone gets a copy of them, sent
     * on the next connection; an account no longer chosen loses the copy that
     * was still waiting.
     */
    suspend fun setAccounts(number: Int, accounts: Set<String>) {
        store.update { s -> s.copy(parent = Catalog.setAccounts(s.parent!!, number, accounts)) }
        refreshCopies(number)
    }

    /**
     * Keeps the copies waiting for other phones equal to this phone's album
     * (after new photos, a new order or a crop). Only copies are touched:
     * photos taken for another account, or already delivered, are left alone.
     */
    suspend fun refreshCopies(number: Int) = withContext(Dispatchers.IO) {
        val p = parent()
        val a = Catalog.article(p, number) ?: return@withContext
        val own = gallery.albumPhotos(base, sku(p.self, number, p.digits))
        for (c in p.children) {
            if (outbox.hasOriginals(c.id, number)) continue
            val wanted = c.id in a.accounts
            val delivered = (a.delivered[c.id] ?: 0) > 0
            when {
                delivered && wanted -> Unit
                !wanted || delivered || own.isEmpty() -> outbox.deleteCopies(c.id, number)
                else -> outbox.replaceCopies(c.id, number, own.mapNotNull { uri -> resolver.openInputStream(uri)?.use { it.readBytes() } })
            }
        }
    }

    /**
     * Articles from before accounts were chosen get the accounts they really
     * use: where they are uploaded, where photos went or are waiting to go,
     * and this phone if its album has photos. Runs once per article.
     */
    suspend fun adoptAccounts() {
        val p = state.parent ?: return
        val legacy = p.articles.filter { !it.released && it.accounts.isEmpty() }
        if (legacy.isEmpty()) return
        val found = withContext(Dispatchers.IO) {
            val counts = runCatching { gallery.counts(base) }.getOrDefault(emptyMap())
            legacy.associate { a ->
                val own = if ((counts[sku(p.self, a.number, p.digits)] ?: 0) > 0) setOf(p.self.id) else emptySet()
                val waiting = p.children.filter { outbox.count(it.id, a.number) > 0 }.map { it.id }.toSet()
                a.number to (a.targets + own + waiting)
            }
        }
        store.update { s ->
            var next = s.parent ?: return@update s
            for ((n, accounts) in found) {
                if (accounts.isEmpty() || Catalog.article(next, n)?.accounts?.isNotEmpty() == true) continue
                next = Catalog.setAccounts(next, n, accounts)
            }
            s.copy(parent = next)
        }
    }

    suspend fun setAccount(id: String, account: String, profile: String) {
        store.update { s -> s.copy(parent = Catalog.setAccount(s.parent!!, id, account, profile)) }
    }

    /** Applies one account's Vinted wardrobe to the catalogue. */
    suspend fun applyWardrobe(deviceId: String, items: List<VintedWardrobe.Remote>): VintedWardrobe.Result {
        var result = VintedWardrobe.Result(0, 0, emptyList(), emptyList())
        store.update { s ->
            val (next, r) = VintedWardrobe.apply(s.parent!!, deviceId, items, System.currentTimeMillis())
            result = r
            s.copy(parent = next)
        }
        return result
    }

    // ---------- photo editing (order, cover, crop) ----------

    /** One photo of the edited list: kept as it is, or replaced by new JPEG bytes (a crop). */
    data class EditedPhoto(val source: Uri, val bytes: ByteArray? = null)

    /** Whether [deviceId]'s photos of [number] are on this phone and can be edited here. */
    fun editable(number: Int, deviceId: String): Boolean {
        val p = state.parent ?: return false
        return if (deviceId == p.self.id) true else outbox.count(deviceId, number) > 0
    }

    /**
     * Rewrites the photos of [number] for [deviceId] in the given order, with
     * the crops applied and without the ones left out. New copies are written
     * first and the old ones deleted after, so nothing is lost if it stops
     * halfway. Returns photos only the user can allow deleting.
     */
    suspend fun rewritePhotos(number: Int, deviceId: String, photos: List<EditedPhoto>): List<Uri> = withContext(Dispatchers.IO) {
        val p = parent()
        val bytes = photos.map { e ->
            e.bytes ?: resolver.openInputStream(e.source)?.use { it.readBytes() } ?: throw CatalogException("No se pudo leer una foto")
        }
        if (deviceId == p.self.id) {
            val album = sku(p.self, number, p.digits)
            val old = gallery.albumPhotos(base, album)
            for (b in bytes) gallery.save(base, album, b)
            gallery.delete(old).also { refreshCopies(number) }
        } else {
            outbox.replace(deviceId, number, bytes)
            emptyList()
        }
    }

    // ---------- lots ----------

    suspend fun saveLot(id: String?, name: String, price: Long, quantity: Int) {
        store.update { s ->
            val p = s.parent!!
            s.copy(parent = if (id == null) Ledger.addLot(p, Ids.newId(), name, price, quantity, System.currentTimeMillis())
            else Ledger.updateLot(p, id, name, price, quantity))
        }
    }

    suspend fun deleteLot(id: String) {
        store.update { s -> s.copy(parent = Ledger.deleteLot(s.parent!!, id)) }
    }

    suspend fun setStyle(id: String, style: Int, template: String) {
        store.update { s -> s.copy(parent = Catalog.styleAndTemplate(s.parent!!, id, style, template)) }
    }

    suspend fun startSession(number: Int, deviceId: String) {
        store.update { s ->
            val p = s.parent!!
            if (Catalog.article(p, number)?.released != false) throw CatalogException("Artículo no disponible")
            s.copy(parent = p.copy(session = Session(number, deviceId, System.currentTimeMillis())))
        }
    }

    suspend fun endSession() {
        store.update { s -> s.copy(parent = s.parent?.copy(session = null)) }
    }

    suspend fun sessionPhotos(): List<Gallery.Photo> {
        val session = parent().session ?: return emptyList()
        return withContext(Dispatchers.IO) { gallery.cameraPhotosSince(session.startedAt) }
    }

    /**
     * Cleans every selected photo into its destination (own album or outbox).
     * Returns the originals, which only the user can allow deleting.
     */
    suspend fun saveSession(photos: List<Uri>, progress: (Int) -> Unit): List<Uri> {
        val p = parent()
        val session = p.session ?: throw CatalogException("No hay sesión")
        val target = Catalog.device(p, session.deviceId) ?: throw CatalogException("Dispositivo no encontrado")
        withContext(Dispatchers.IO) {
            photos.forEachIndexed { i, uri ->
                val clean = Cleaner.clean(resolver, uri)
                if (target.id == p.self.id) gallery.save(base, sku(target, session.number, p.digits), clean)
                else outbox.add(target.id, session.number, clean)
                withContext(Dispatchers.Main) { progress(i + 1) }
            }
        }
        endSession()
        if (target.id == p.self.id) refreshCopies(session.number)
        return photos
    }

    fun ownCount(number: Int): Int {
        val p = state.parent ?: return 0
        return gallery.albumPhotos(base, sku(p.self, number, p.digits)).size
    }

    // ---------- releases ----------

    /** Sold on the parent: record the sale, delete everything local, its accounts are released with it. */
    suspend fun sellOnParent(number: Int, price: Long, cost: Long?, account: String? = null): List<Uri> {
        val consent = deleteLocalForParent(number)
        store.update { s ->
            s.copy(parent = Ledger.sell(s.parent!!, number, s.deviceId, price, cost, System.currentTimeMillis(), account ?: s.deviceId))
        }
        return consent
    }

    /**
     * Lists it again with new photos: the old ones go (the original files in the gallery need the user's allowance, as in a
     * sale) and the article keeps everything else. See [Catalog.relist].
     */
    suspend fun relist(number: Int, keepPhotos: Boolean = false): List<Uri> {
        // keepPhotos: the new photos were already taken (without pressing «Resubir»): they stay, only the state changes.
        val consent = if (keepPhotos) emptyList() else deleteLocalForParent(number)
        store.update { s -> s.copy(parent = Catalog.relist(s.parent!!, number)) }
        return consent
    }

    /** The AI wrote a title and hashtags that differ from the old listing's. */
    suspend fun finishRelist(number: Int) {
        store.update { s -> s.copy(parent = Catalog.finishRelist(s.parent!!, number)) }
    }

    /** Removed by mistake: delete it everywhere without recording a sale. */
    suspend fun deleteArticle(number: Int): List<Uri> {
        val consent = deleteLocalForParent(number)
        store.update { s -> s.copy(parent = Catalog.release(s.parent!!, number)) }
        return consent
    }

    suspend fun ensureDistinctStyles() {
        store.update { s -> s.parent?.let { s.copy(parent = Catalog.ensureDistinctStyles(it)) } ?: s }
    }

    private suspend fun deleteLocalForParent(number: Int): List<Uri> = withContext(Dispatchers.IO) {
        val p = parent()
        outbox.deleteNumber(number)
        previews.deleteNumber(number)
        gallery.deleteAlbum(base, sku(p.self, number, p.digits))
    }

    // ---------- accounts ----------

    /** Another Vinted account (the one a second PC publishes in). */
    suspend fun addChild(device: Device): ParentState {
        val s = store.update { s ->
            val p = s.parent!!
            s.copy(parent = Catalog.addChild(p, device.copy(style = Catalog.nextStyle(p))))
        }
        return s.parent!!
    }

    suspend fun removeChild(id: String) {
        withContext(Dispatchers.IO) { outbox.deleteDevice(id) }
        store.update { s -> s.copy(parent = Catalog.removeChild(s.parent!!, id)) }
    }

    // ---------- settings ----------

    /** Returns how many photos could not be moved (not created by this app). */
    suspend fun renameDevice(id: String, name: String, prefix: String): Int {
        val p = parent()
        val old = Catalog.device(p, id) ?: return 0
        val next = Catalog.renameDevice(p, id, name, prefix)
        var failed = 0
        if (id == p.self.id && old.prefix != next.self.prefix) {
            failed = moveOwnAlbums(p) { n -> sku(next.self, n, p.digits) }
        }
        store.update { it.copy(parent = next) }
        return failed
    }

    suspend fun setDigits(digits: Int): Int {
        val p = parent()
        val next = Catalog.setDigits(p, digits)
        val failed = if (next.digits != p.digits) moveOwnAlbums(p) { n -> sku(p.self, n, next.digits) } else 0
        store.update { it.copy(parent = next) }
        return failed
    }

    /**
     * Uses [folder] for albums and moves this phone's albums found in any
     * other known folder into it. Returns photos that could not be moved.
     */
    suspend fun adoptFolder(folder: String): Int {
        val s = state
        val (prefix, digits) = s.parent?.let { it.self.prefix to it.digits } ?: (null to 0)
        var failed = 0
        if (prefix != null) {
            withContext(Dispatchers.IO) {
                for (other in (AppState.BASE_FOLDERS + s.baseFolder).distinct()) {
                    if (other == folder) continue
                    for (album in gallery.counts(other).keys) {
                        if (Sku.parse(album, prefix, digits) != null) failed += gallery.move(other, album, folder, album)
                    }
                }
            }
        }
        if (s.baseFolder != folder) store.update { it.copy(baseFolder = folder) }
        return failed
    }

    /** Fills the fixed "0 Subir" folder with this phone's album of [number]. */
    suspend fun stage(number: Int): Int {
        val s = state
        val album = s.parent?.let { sku(it.self, number, it.digits) }
            ?: throw CatalogException("Este móvil no está configurado")
        val count = withContext(Dispatchers.IO) { gallery.stage(base, album) }
        if (count == 0) throw CatalogException("$album no tiene fotos")
        return count
    }

    private suspend fun moveOwnAlbums(p: ParentState, newName: (Int) -> String): Int = withContext(Dispatchers.IO) {
        p.articles.sumOf { a -> gallery.move(base, sku(p.self, a.number, p.digits), base, newName(a.number)) }
    }
}
