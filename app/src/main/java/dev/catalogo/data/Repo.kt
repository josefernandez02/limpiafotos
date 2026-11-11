package dev.catalogo.data

import android.content.Context
import android.net.Uri
import dev.catalogo.core.AiResult
import dev.catalogo.core.AiSuggestion
import dev.catalogo.core.AppState
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogException
import dev.catalogo.core.ChildState
import dev.catalogo.core.Device
import dev.catalogo.core.Ledger
import dev.catalogo.core.Listing
import dev.catalogo.core.ListingText
import dev.catalogo.core.Auth
import dev.catalogo.core.PairAccept
import dev.catalogo.core.ParentState
import dev.catalogo.core.Session
import dev.catalogo.core.Sku
import dev.catalogo.core.Sync
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

    val state: AppState get() = store.value
    val base: String get() = state.baseFolder

    private fun parent(): ParentState = state.parent ?: throw CatalogException("Este móvil no es el principal")
    private fun child(): ChildState = state.child ?: throw CatalogException("Este móvil no está vinculado")

    fun sku(device: Device, number: Int, digits: Int) = Sku.format(device.prefix, number, digits)

    // ---------- setup ----------

    suspend fun setupParent(name: String, prefix: String) {
        store.update { s ->
            val self = Device(s.deviceId, name.trim(), prefix.trim().uppercase())
            Catalog.validateDevice(ParentState(self = self), self.id, self.name, self.prefix)
            s.copy(parent = ParentState(self = self), child = null, secondary = false)
        }
    }

    suspend fun setupChild() {
        store.update { it.copy(secondary = true, parent = null) }
    }

    suspend fun resetRole() {
        store.update { it.copy(parent = null, child = null, secondary = false) }
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
            s.copy(parent = if (id == null) Ledger.addLot(p, Auth.newId(), name, price, quantity, System.currentTimeMillis())
            else Ledger.updateLot(p, id, name, price, quantity))
        }
    }

    suspend fun deleteLot(id: String) {
        store.update { s -> s.copy(parent = Ledger.deleteLot(s.parent!!, id)) }
    }

    suspend fun setStyle(id: String, style: Int, template: String) {
        store.update { s -> s.copy(parent = Catalog.styleAndTemplate(s.parent!!, id, style, template)) }
    }

    suspend fun setListings(listings: Map<Int, ListingText>) {
        store.update { s -> s.copy(child = s.child?.copy(listings = listings)) }
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

    /** Sold on the parent: record the sale, delete everything local, children follow on next sync. */
    suspend fun sellOnParent(number: Int, price: Long, cost: Long?, account: String? = null): List<Uri> {
        val consent = deleteLocalForParent(number)
        store.update { s ->
            s.copy(parent = Ledger.sell(s.parent!!, number, s.deviceId, price, cost, System.currentTimeMillis(), account ?: s.deviceId))
        }
        return consent
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

    suspend fun releaseFromChild(number: Int, childId: String, price: Long?): List<Uri> {
        if (Catalog.article(parent(), number) == null) return emptyList()
        val consent = deleteLocalForParent(number)
        store.update { s ->
            val p = s.parent!!
            s.copy(parent = if (price != null) Ledger.sell(p, number, childId, price, null, System.currentTimeMillis())
            else Catalog.release(p, number, confirmedBy = childId))
        }
        return consent
    }

    suspend fun confirmReleases(childId: String, numbers: List<Int>) {
        if (numbers.isEmpty()) return
        store.update { s -> s.copy(parent = numbers.fold(s.parent!!) { p, n -> Catalog.confirmRelease(p, n, childId) }) }
    }

    private suspend fun deleteLocalForParent(number: Int): List<Uri> = withContext(Dispatchers.IO) {
        val p = parent()
        outbox.deleteNumber(number)
        previews.deleteNumber(number)
        gallery.deleteAlbum(base, sku(p.self, number, p.digits))
    }

    /** Sold on a child: delete its album now, tell the parent on next sync. */
    /** Sold ([price] set) or removed by mistake ([price] null) on a secondary phone. */
    suspend fun releaseOnChild(number: Int, price: Long?): List<Uri> {
        val c = child()
        val consent = withContext(Dispatchers.IO) { gallery.deleteAlbum(base, sku(c.self, number, c.digits)) }
        store.update { s ->
            val current = s.child!!
            s.copy(child = current.copy(
                pendingReleases = current.pendingReleases + number,
                salePrices = if (price != null) current.salePrices + (number to price) else current.salePrices - number,
                listings = current.listings - number,
            ))
        }
        return consent
    }

    /** Releases asked by the parent; returns the numbers handled. */
    suspend fun applyRequestedReleases(numbers: List<Int>): List<Int> {
        val c = child()
        withContext(Dispatchers.IO) { numbers.forEach { gallery.deleteAlbum(base, sku(c.self, it, c.digits)) } }
        return numbers
    }

    suspend fun ackChildReleases(numbers: List<Int>) {
        store.update { s ->
            s.copy(child = s.child?.let { it.copy(pendingReleases = it.pendingReleases - numbers.toSet(), salePrices = it.salePrices - numbers.toSet()) })
        }
    }

    // ---------- delivery ----------

    suspend fun delivered(childId: String, number: Int, count: Int) {
        withContext(Dispatchers.IO) {
            previews.keep(number, childId, outbox.files(childId, number))
            outbox.delete(childId, number)
        }
        store.update { s -> s.copy(parent = Catalog.markDelivered(s.parent!!, number, childId, count)) }
    }

    fun childAlbums(): Map<Int, Int> {
        val c = state.child ?: return emptyMap()
        return gallery.counts(base).mapNotNull { (album, count) ->
            Sku.parse(album, c.self.prefix, c.digits)?.let { it to count }
        }.toMap().toSortedMap()
    }

    // ---------- pairing ----------

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

    suspend fun pairChild(pair: PairAccept) {
        store.update { s ->
            val current = s.child
            if (current != null && current.parentId != pair.parentId) throw CatalogException("Ya está vinculado a otro principal")
            val self = Device(s.deviceId, pair.name, pair.prefix)
            val next = ChildState(
                self = self,
                parentId = pair.parentId,
                secret = pair.secret,
                digits = pair.digits,
                configVersion = pair.configVersion,
                pendingReleases = current?.pendingReleases ?: emptySet(),
            )
            s.copy(child = next, secondary = true)
        }
    }

    suspend fun unpair() {
        store.update { it.copy(child = null, secondary = true) }
    }

    /** Applies the name/prefix/digits the parent assigned, renaming albums if needed. */
    suspend fun applyChildConfig(sync: Sync): Int {
        val c = child()
        val changedFormat = sync.prefix != c.self.prefix || sync.digits != c.digits
        var failed = 0
        if (changedFormat) {
            withContext(Dispatchers.IO) {
                for (number in childAlbums().keys) {
                    failed += gallery.move(base, sku(c.self, number, c.digits), base, Sku.format(sync.prefix, number, sync.digits))
                }
            }
        }
        store.update { s ->
            val current = s.child!!
            s.copy(child = current.copy(
                self = current.self.copy(name = sync.name, prefix = sync.prefix),
                digits = sync.digits,
                configVersion = sync.configVersion,
            ))
        }
        return failed
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
        val (prefix, digits) = s.parent?.let { it.self.prefix to it.digits }
            ?: s.child?.let { it.self.prefix to it.digits }
            ?: (null to 0)
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
            ?: s.child?.let { sku(it.self, number, it.digits) }
            ?: throw CatalogException("Este móvil no está configurado")
        val count = withContext(Dispatchers.IO) { gallery.stage(base, album) }
        if (count == 0) throw CatalogException("$album no tiene fotos")
        return count
    }

    private suspend fun moveOwnAlbums(p: ParentState, newName: (Int) -> String): Int = withContext(Dispatchers.IO) {
        p.articles.sumOf { a -> gallery.move(base, sku(p.self, a.number, p.digits), base, newName(a.number)) }
    }
}
