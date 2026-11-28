package dev.catalogo.data

import android.content.Context
import android.net.Uri
import dev.catalogo.core.AiResult
import dev.catalogo.core.AiSuggestion
import dev.catalogo.core.AppState
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogException
import dev.catalogo.core.VintedAccount
import dev.catalogo.core.Ledger
import dev.catalogo.core.Listing
import dev.catalogo.core.Ids
import dev.catalogo.core.ShippingLabels
import dev.catalogo.core.CatalogState
import dev.catalogo.core.Sales
import dev.catalogo.core.Session
import dev.catalogo.core.Sku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every operation that touches state, gallery and outbox together. */
class CatalogRepository(context: Context) {
    private val resolver = context.contentResolver
    val store = StateStore(context.filesDir)
    val gallery = Gallery(context)
    val accountPhotos = AccountPhotoStore(context.filesDir)
    val gemini = GeminiClient(context.contentResolver)
    val legacyPreviews = LegacyPreviewStore(context.filesDir)
    val pcClient = PcClient(context.contentResolver)
    val saleImages = SaleImageStore(context.filesDir, context.contentResolver)
    val labelFiles = LabelFileStore(context.contentResolver)

    val state: AppState get() = store.value
    val base: String get() = state.baseFolder

    private fun catalog(): CatalogState = state.catalog ?: throw CatalogException("Este móvil no es el principal")


    fun sku(vintedAccount: VintedAccount, number: Int, digits: Int) = Sku.format(vintedAccount.prefix, number, digits)

    // ---------- setup ----------

    suspend fun setupMainPhone(name: String, prefix: String) {
        store.update { s ->
            val mainAccount = VintedAccount(s.deviceId, name.trim(), prefix.trim().uppercase())
            Catalog.validateAccount(CatalogState(mainAccount = mainAccount), mainAccount.id, mainAccount.name, mainAccount.prefix)
            s.copy(catalog = CatalogState(mainAccount = mainAccount))
        }
    }

    // ---------- parent: articles and photo sessions ----------

    suspend fun newArticle(listing: Listing?, lotId: String?, cost: Long?, ai: AiResult?): Int {
        var number = -1
        store.update { s ->
            val (created, n) = Catalog.create(s.catalog!!, System.currentTimeMillis())
            number = n
            s.copy(catalog = Catalog.updateArticle(created, n, listing, lotId, cost, ai))
        }
        return number
    }

    suspend fun updateArticle(number: Int, listing: Listing?, lotId: String?, cost: Long?, ai: AiResult?) {
        store.update { s -> s.copy(catalog = Catalog.updateArticle(s.catalog!!, number, listing, lotId, cost, ai)) }
    }

    suspend fun applyAi(number: Int, suggestion: AiSuggestion, model: String, seconds: Int) {
        store.update { s -> s.copy(catalog = Catalog.applyAi(s.catalog!!, number, suggestion, model, seconds)) }
    }

    suspend fun setPhotos(number: Int, photos: List<String>) {
        store.update { s -> s.copy(catalog = Catalog.setPhotos(s.catalog!!, number, photos)) }
    }

    suspend fun setPrice(number: Int, price: Long?) {
        store.update { s -> s.copy(catalog = Catalog.setPrice(s.catalog!!, number, price)) }
    }

    /** Updates [number]'s draft status with [block] (it receives the current one). */
    suspend fun updatePcTask(number: Int, block: (dev.catalogo.core.PcTask?) -> dev.catalogo.core.PcTask?) {
        store.update { s ->
            val p = s.catalog ?: return@update s
            val a = Catalog.article(p, number) ?: return@update s
            s.copy(catalog = Catalog.setPcTask(p, number, block(a.pcTask)))
        }
    }

    suspend fun setGamesOff(off: Set<String>) {
        store.update { it.copy(gamesOff = off) }
    }

    suspend fun setLabels(job: dev.catalogo.core.LabelJob) {
        store.update { it.copy(labels = job) }
    }

    /** The finished [job] with the PDF just saved, and the [orders] inside it. */
    suspend fun keepLabels(job: dev.catalogo.core.LabelJob, orders: List<String>) {
        store.update { ShippingLabels.saved(it, job, orders) }
    }

    /** Deletes from Descargas the label PDFs whose sales are paid. One that resists is tried again later. */
    suspend fun discardSpentLabels() {
        val p = state.catalog ?: return
        val spent = ShippingLabels.spent(p, state.labelPdfs)
        if (spent.isEmpty()) return
        val gone = withContext(Dispatchers.IO) { spent.filter { labelFiles.delete(Uri.parse(it.file)) } }
        if (gone.isEmpty()) return
        store.update { ShippingLabels.discard(it, gone) }
        AppLog.i("etiquetas", "borrados ${gone.size} PDF de ventas completadas")
    }

    suspend fun setPc(link: dev.catalogo.core.PcLink?) {
        store.update { it.copy(pc = link) }
    }

    suspend fun setGeminiKey(key: String) {
        store.update { it.copy(geminiKey = key.trim()) }
    }

    suspend fun setUploaded(number: Int, accountId: String, uploaded: Boolean) {
        store.update { s -> s.copy(catalog = Catalog.setUploaded(s.catalog!!, number, accountId, uploaded)) }
    }

    /**
     * Accounts [number] will be published in. The photos are always taken on
     * this phone: a chosen account of another phone gets a copy of them, sent
     * on the next connection; an account no longer chosen loses the copy that
     * was still waiting.
     */
    suspend fun setAccounts(number: Int, accounts: Set<String>) {
        store.update { s -> s.copy(catalog = Catalog.setAccounts(s.catalog!!, number, accounts)) }
        refreshCopies(number)
    }

    /**
     * Keeps the copies waiting for other phones equal to this phone's album
     * (after new photos, a new order or a crop). Only copies are touched:
     * photos taken for another account, or already delivered, are left alone.
     */
    suspend fun refreshCopies(number: Int) = withContext(Dispatchers.IO) {
        val p = catalog()
        val a = Catalog.article(p, number) ?: return@withContext
        val own = gallery.albumPhotos(base, sku(p.mainAccount, number, p.digits))
        for (c in p.otherAccounts) {
            if (accountPhotos.hasOriginals(c.id, number)) continue
            val wanted = c.id in a.accounts
            val delivered = (a.delivered[c.id] ?: 0) > 0
            when {
                delivered && wanted -> Unit
                !wanted || delivered || own.isEmpty() -> accountPhotos.deleteCopies(c.id, number)
                else -> accountPhotos.replaceCopies(c.id, number, own.mapNotNull { uri -> resolver.openInputStream(uri)?.use { it.readBytes() } })
            }
        }
    }

    /**
     * Articles from before accounts were chosen get the accounts they really
     * use: where they are uploaded, where photos went or are waiting to go,
     * and this phone if its album has photos. Runs once per article.
     */
    suspend fun adoptAccounts() {
        val p = state.catalog ?: return
        val legacy = p.articles.filter { !it.released && it.accounts.isEmpty() }
        if (legacy.isEmpty()) return
        val found = withContext(Dispatchers.IO) {
            val counts = runCatching { gallery.counts(base) }.getOrDefault(emptyMap())
            legacy.associate { a ->
                val own = if ((counts[sku(p.mainAccount, a.number, p.digits)] ?: 0) > 0) setOf(p.mainAccount.id) else emptySet()
                val waiting = p.otherAccounts.filter { accountPhotos.count(it.id, a.number) > 0 }.map { it.id }.toSet()
                a.number to (a.targets + own + waiting)
            }
        }
        store.update { s ->
            var next = s.catalog ?: return@update s
            for ((n, accounts) in found) {
                if (accounts.isEmpty() || Catalog.article(next, n)?.accounts?.isNotEmpty() == true) continue
                next = Catalog.setAccounts(next, n, accounts)
            }
            s.copy(catalog = next)
        }
    }

    suspend fun setVintedProfile(id: String, account: String, profile: String) {
        store.update { s -> s.copy(catalog = Catalog.setVintedProfile(s.catalog!!, id, account, profile)) }
    }

    // ---------- photo editing (order, cover, crop) ----------

    /** One photo of the edited list: kept as it is, or replaced by new JPEG bytes (a crop). */
    data class EditedPhoto(val source: Uri, val bytes: ByteArray? = null)

    /**
     * Rewrites the photos of [number] for [accountId] in the given order, with
     * the crops applied and without the ones left out. New copies are written
     * first and the old ones deleted after, so nothing is lost if it stops
     * halfway. Returns photos only the user can allow deleting.
     */
    suspend fun rewritePhotos(number: Int, accountId: String, photos: List<EditedPhoto>): List<Uri> = withContext(Dispatchers.IO) {
        val p = catalog()
        val bytes = photos.map { e ->
            e.bytes ?: resolver.openInputStream(e.source)?.use { it.readBytes() } ?: throw CatalogException("No se pudo leer una foto")
        }
        if (accountId == p.mainAccount.id) {
            val album = sku(p.mainAccount, number, p.digits)
            val old = gallery.albumPhotos(base, album)
            for (b in bytes) gallery.save(base, album, b)
            gallery.delete(old).also { refreshCopies(number) }
        } else {
            accountPhotos.replace(accountId, number, bytes)
            emptyList()
        }
    }

    // ---------- lots ----------

    suspend fun saveLot(id: String?, name: String, price: Long, quantity: Int) {
        store.update { s ->
            val p = s.catalog!!
            s.copy(catalog = if (id == null) Ledger.addLot(p, Ids.newId(), name, price, quantity, System.currentTimeMillis())
            else Ledger.updateLot(p, id, name, price, quantity))
        }
    }

    suspend fun deleteLot(id: String) {
        store.update { s -> s.copy(catalog = Ledger.deleteLot(s.catalog!!, id)) }
    }

    suspend fun setStyle(id: String, style: Int, template: String) {
        store.update { s -> s.copy(catalog = Catalog.styleAndTemplate(s.catalog!!, id, style, template)) }
    }

    suspend fun startSession(number: Int, accountId: String) {
        store.update { s ->
            val p = s.catalog!!
            if (Catalog.article(p, number)?.inStock != true) throw CatalogException("Artículo no disponible")
            s.copy(catalog = p.copy(session = Session(number, accountId, System.currentTimeMillis())))
        }
    }

    suspend fun endSession() {
        store.update { s -> s.copy(catalog = s.catalog?.copy(session = null)) }
    }

    suspend fun sessionPhotos(): List<Gallery.Photo> {
        val session = catalog().session ?: return emptyList()
        return withContext(Dispatchers.IO) { gallery.cameraPhotosSince(session.startedAt) }
    }

    /**
     * Cleans every selected photo into its destination (own album or outbox).
     * Returns the originals, which only the user can allow deleting.
     */
    suspend fun saveSession(photos: List<Uri>, progress: (Int) -> Unit): List<Uri> {
        val p = catalog()
        val session = p.session ?: throw CatalogException("No hay sesión")
        val target = Catalog.findAccount(p, session.accountId) ?: throw CatalogException("Dispositivo no encontrado")
        withContext(Dispatchers.IO) {
            photos.forEachIndexed { i, uri ->
                val clean = PhotoCleaner.clean(resolver, uri)
                if (target.id == p.mainAccount.id) gallery.save(base, sku(target, session.number, p.digits), clean)
                else accountPhotos.add(target.id, session.number, clean)
                withContext(Dispatchers.Main) { progress(i + 1) }
            }
        }
        endSession()
        if (target.id == p.mainAccount.id) refreshCopies(session.number)
        return photos
    }

    // ---------- sales ----------

    /**
     * Applies [block] (a sale transition) to the catalogue. The photos of the articles it
     * takes away are deleted first, so their numbers can be used again with nothing left
     * behind; a picture of each is kept for its sale. Returns the originals only the user
     * can allow deleting.
     */
    suspend fun changeSales(block: (CatalogState) -> CatalogState): List<Uri> {
        val before = catalog()
        val after = block(before) // an impossible change throws here, before anything is touched
        val gone = Sales.freed(before, after)
        val consent = ArrayList<Uri>()
        for (number in gone) {
            val listedAt = Catalog.article(before, number)?.createdAt
            cover(before, number)?.let { uri ->
                for (sale in after.sales.filter { it.number == number && it.listedAt == listedAt }) saleImages.keep(sale.id, uri)
            }
            consent += deleteLocalPhotos(number)
        }
        store.update { s -> s.copy(catalog = block(s.catalog!!)) }
        discardSpentLabels()
        return consent
    }

    /**
     * A sale registered by hand. Not [paid] yet, it stays in progress until completed or
     * cancelled, keeping the article unless [freeNumber] gives its number away at once.
     */
    suspend fun registerSale(number: Int, price: Long, cost: Long?, account: String?, paid: Boolean, freeNumber: Boolean): List<Uri> {
        val id = Ids.newId()
        val now = System.currentTimeMillis()
        return changeSales {
            val sold = Sales.register(it, number, id, account ?: it.mainAccount.id, price, cost, now)
            when {
                paid -> Sales.complete(sold, id, now)
                freeNumber -> Sales.release(sold, id)
                else -> sold
            }
        }
    }

    /** When the PC last read the sales: shown even when it cannot be reached. */
    suspend fun salesRead(at: Long) {
        store.update { s -> s.catalog?.let { s.copy(catalog = it.copy(salesReadAt = at)) } ?: s }
    }

    suspend fun ensureSaleIds() {
        store.update { s -> s.catalog?.let { s.copy(catalog = Sales.ensureIds(it)) } ?: s }
    }

    /** The PC dropped the job [ref] of an article that is gone. */
    suspend fun heard(ref: String) {
        store.update { s -> s.catalog?.let { s.copy(catalog = Sales.heard(it, ref)) } ?: s }
    }

    private suspend fun cover(p: CatalogState, number: Int): Uri? = withContext(Dispatchers.IO) {
        runCatching { gallery.albumPhotos(base, sku(p.mainAccount, number, p.digits)).firstOrNull() }.getOrNull()
            ?: p.otherAccounts.firstNotNullOfOrNull { d -> accountPhotos.files(d.id, number).firstOrNull() }?.let { Uri.fromFile(it) }
            ?: legacyPreviews.files(number).firstOrNull()?.let { Uri.fromFile(it) }
    }

    /**
     * Lists it again with new photos: the old ones go (the original files in the gallery need the user's allowance, as in a
     * sale) and the article keeps everything else. See [Catalog.relist].
     */
    suspend fun relist(number: Int, keepPhotos: Boolean = false): List<Uri> {
        if (Catalog.article(catalog(), number)?.inStock != true) throw CatalogException("Artículo no disponible")
        // keepPhotos: the new photos were already taken (without pressing «Resubir»): they stay, only the state changes.
        val consent = if (keepPhotos) emptyList() else deleteLocalPhotos(number)
        store.update { s -> s.copy(catalog = Catalog.relist(s.catalog!!, number)) }
        return consent
    }

    /** The AI wrote a title and hashtags that differ from the old listing's. */
    suspend fun finishRelist(number: Int) {
        store.update { s -> s.copy(catalog = Catalog.finishRelist(s.catalog!!, number)) }
    }

    /** Removed by mistake: delete it everywhere without recording a sale. */
    suspend fun deleteArticle(number: Int): List<Uri> = changeSales { Catalog.delete(it, number) }

    suspend fun ensureDistinctStyles() {
        store.update { s -> s.catalog?.let { s.copy(catalog = Catalog.ensureDistinctStyles(it)) } ?: s }
    }

    private suspend fun deleteLocalPhotos(number: Int): List<Uri> = withContext(Dispatchers.IO) {
        val p = catalog()
        accountPhotos.deleteNumber(number)
        legacyPreviews.deleteNumber(number)
        gallery.deleteAlbum(base, sku(p.mainAccount, number, p.digits))
    }

    // ---------- accounts ----------

    /** Another Vinted account (the one a second PC publishes in). */
    suspend fun addAccount(vintedAccount: VintedAccount): CatalogState {
        val s = store.update { s ->
            val p = s.catalog!!
            s.copy(catalog = Catalog.addAccount(p, vintedAccount.copy(style = Catalog.nextStyle(p))))
        }
        return s.catalog!!
    }

    suspend fun removeAccount(id: String) {
        withContext(Dispatchers.IO) { accountPhotos.deleteAccount(id) }
        store.update { s -> s.copy(catalog = Catalog.removeAccount(s.catalog!!, id)) }
    }

    // ---------- settings ----------

    /** Returns how many photos could not be moved (not created by this app). */
    suspend fun renameAccount(id: String, name: String, prefix: String): Int {
        val p = catalog()
        val old = Catalog.findAccount(p, id) ?: return 0
        val next = Catalog.renameAccount(p, id, name, prefix)
        var failed = 0
        if (id == p.mainAccount.id && old.prefix != next.mainAccount.prefix) {
            failed = moveOwnAlbums(p) { n -> sku(next.mainAccount, n, p.digits) }
        }
        store.update { it.copy(catalog = next) }
        return failed
    }

    suspend fun setDigits(digits: Int): Int {
        val p = catalog()
        val next = Catalog.setDigits(p, digits)
        val failed = if (next.digits != p.digits) moveOwnAlbums(p) { n -> sku(p.mainAccount, n, next.digits) } else 0
        store.update { it.copy(catalog = next) }
        return failed
    }

    /**
     * Uses [folder] for albums and moves this phone's albums found in any
     * other known folder into it. Returns photos that could not be moved.
     */
    suspend fun adoptFolder(folder: String): Int {
        val s = state
        val (prefix, digits) = s.catalog?.let { it.mainAccount.prefix to it.digits } ?: (null to 0)
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
        val album = s.catalog?.let { sku(it.mainAccount, number, it.digits) }
            ?: throw CatalogException("Este móvil no está configurado")
        val count = withContext(Dispatchers.IO) { gallery.stage(base, album) }
        if (count == 0) throw CatalogException("$album no tiene fotos")
        return count
    }

    private suspend fun moveOwnAlbums(p: CatalogState, newName: (Int) -> String): Int = withContext(Dispatchers.IO) {
        p.articles.sumOf { a -> gallery.move(base, sku(p.mainAccount, a.number, p.digits), base, newName(a.number)) }
    }
}
