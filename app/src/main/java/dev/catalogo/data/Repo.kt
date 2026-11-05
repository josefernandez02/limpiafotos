package dev.catalogo.data

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every operation that touches state, gallery and outbox together. */
class Repo(context: Context) {
    private val resolver = context.contentResolver
    val store = Store(context.filesDir)
    val gallery = Gallery(context)
    val outbox = Outbox(context.filesDir)

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

    suspend fun newArticle(listing: Listing?, lotId: String?, cost: Long?): Int {
        var number = -1
        store.update { s ->
            val (created, n) = Catalog.create(s.parent!!, System.currentTimeMillis())
            number = n
            s.copy(parent = Catalog.updateArticle(created, n, listing, lotId, cost))
        }
        return number
    }

    suspend fun updateArticle(number: Int, listing: Listing?, lotId: String?, cost: Long?) {
        store.update { s -> s.copy(parent = Catalog.updateArticle(s.parent!!, number, listing, lotId, cost)) }
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
        return photos
    }

    fun ownCount(number: Int): Int {
        val p = state.parent ?: return 0
        return gallery.albumPhotos(base, sku(p.self, number, p.digits)).size
    }

    // ---------- releases ----------

    /** Sold on the parent: record the sale, delete everything local, children follow on next sync. */
    suspend fun sellOnParent(number: Int, price: Long, cost: Long?): List<Uri> {
        val consent = deleteLocalForParent(number)
        store.update { s -> s.copy(parent = Ledger.sell(s.parent!!, number, s.deviceId, price, cost, System.currentTimeMillis())) }
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
        withContext(Dispatchers.IO) { outbox.delete(childId, number) }
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
