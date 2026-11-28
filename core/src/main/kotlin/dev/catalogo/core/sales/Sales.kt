package dev.catalogo.core.sales

import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.catalog.Ledger
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.model.Sale
import dev.catalogo.core.model.SaleStatus

/**
 * The life of a sale. Pure transitions of the parent's state; deleting photos and
 * telling the PC is left to the caller ([freed], [CatalogState.retired] and the
 * article's pending desk state say what is to be done).
 *
 *  - PENDING: sold, but the order can still be cancelled. The article stays, out of
 *    stock, with its number, data and photos, unless the user frees the number early.
 *  - COMPLETED: final. The article is gone and its number is free.
 *  - CANCELLED: the article is back in stock.
 */
object Sales {

    /** PcTask states of a job that is already in Vinted: the only ones the PC takes back by hand. */
    private val IN_VINTED = setOf("borrador", "publicado", "jugado")

    /** PcTask states of a job that has not left this phone («enviando» may have reached the PC already). */
    private val ON_PHONE = setOf(PcTask.PREPARING, PcTask.REVIEW)

    private const val OFF = "fuera"

    fun sale(s: CatalogState, id: String): Sale? = s.sales.firstOrNull { it.id == id }

    /** The article [sale] is about, if it is still here (its number may belong to another one by now). */
    fun article(s: CatalogState, sale: Sale): Article? {
        val listedAt = sale.listedAt ?: return null
        return Catalog.article(s, sale.number)?.takeIf { it.createdAt == listedAt && !it.released }
    }

    /** A sale registered by hand: [account] sold [number], and it stays in progress until said otherwise. */
    fun register(s: CatalogState, number: Int, id: String, account: String, price: Long, cost: Long?, now: Long): CatalogState {
        val a = Catalog.article(s, number)?.takeIf { !it.released } ?: throw CatalogException("Artículo no disponible")
        if (a.held) throw CatalogException("Ya tiene una venta en curso")
        val sale = link(s, Sale(number, account, price, cost, null, now, id = id, status = SaleStatus.PENDING), a)
        return hold(s.copy(sales = s.sales + sale), a.number, id)
    }

    /** The money is in: the sale is final and the number becomes free. */
    fun complete(s: CatalogState, id: String, now: Long): CatalogState {
        val sale = required(s, id)
        if (sale.status != SaleStatus.PENDING) throw CatalogException("Solo se completa una venta en curso")
        val done = replace(s, sale.copy(status = SaleStatus.COMPLETED, closedAt = now, conflict = false))
        val a = article(s, sale)?.takeIf { it.soldIn == id } ?: return done
        return free(done, a)
    }

    /** The garment has left before the money is in: its number is free now and the sale stays in progress. */
    fun release(s: CatalogState, id: String): CatalogState {
        val sale = required(s, id)
        val a = article(s, sale)?.takeIf { sale.status == SaleStatus.PENDING && it.soldIn == id }
            ?: throw CatalogException("Esa venta no guarda ningún número")
        return free(replace(s, sale.copy(numberFreed = true)), a)
    }

    /** A sale registered by hand and given as paid too soon. Its article, gone already, does not come back. */
    fun unpay(s: CatalogState, id: String): CatalogState {
        val sale = required(s, id)
        if (sale.status != SaleStatus.COMPLETED || sale.order.isNotEmpty()) {
            throw CatalogException("Solo una venta apuntada a mano y cobrada puede volver a pendiente")
        }
        return replace(s, sale.copy(status = SaleStatus.PENDING, closedAt = null, numberFreed = sale.listedAt != null))
    }

    /** The order fell through (cancelled, lost, returned): everything goes back to how it was. */
    fun cancel(s: CatalogState, id: String, now: Long, byHand: Boolean = true): CatalogState {
        val sale = required(s, id)
        if (sale.status == SaleStatus.COMPLETED) throw CatalogException("Una venta completada ya no se puede anular")
        if (sale.status == SaleStatus.CANCELLED) return s
        val done = replace(s, sale.copy(status = SaleStatus.CANCELLED, byHand = byHand, closedAt = now))
        val a = article(s, sale)?.takeIf { it.soldIn == id } ?: return done
        return restore(done, a)
    }

    /** Whether a cancelled sale can go back to «in progress»: its article, if it still keeps one, is still free. */
    fun canReopen(s: CatalogState, sale: Sale): Boolean =
        sale.status == SaleStatus.CANCELLED && (sale.listedAt == null || sale.numberFreed || article(s, sale)?.held == false)

    fun reopen(s: CatalogState, id: String): CatalogState {
        val sale = required(s, id)
        if (!canReopen(s, sale)) throw CatalogException("Esta venta ya no se puede recuperar")
        val next = replace(s, sale.copy(status = SaleStatus.PENDING, byHand = false, conflict = false, closedAt = null))
        val a = article(s, sale) ?: return next
        return hold(next, a.number, id)
    }

    /** The user agrees with Vinted that a sale cancelled here did finish. */
    fun accept(s: CatalogState, id: String, now: Long): CatalogState {
        val sale = required(s, id)
        if (!sale.conflict) return s
        if (canReopen(s, sale)) return settle(complete(reopen(s, id), id, now), id)
        // Its article is busy elsewhere or gone: the money is recorded, the article is not touched.
        return replace(s, sale.copy(status = SaleStatus.COMPLETED, conflict = false, byHand = false, closedAt = now, settled = true))
    }

    /** Says which article an unlinked sale was. */
    fun assign(s: CatalogState, id: String, number: Int): CatalogState {
        val sale = required(s, id)
        if (sale.status == SaleStatus.CANCELLED) throw CatalogException("La venta está anulada")
        if (article(s, sale) != null || sale.listedAt != null) throw CatalogException("Esa venta ya tiene artículo")
        val a = Catalog.article(s, number)?.takeIf { it.inStock } ?: throw CatalogException("Artículo no disponible")
        val held = hold(replace(s, link(s, sale, a)), number, id)
        // A finished sale takes its article away at once.
        return if (sale.status == SaleStatus.PENDING) held else free(held, Catalog.article(held, number)!!)
    }

    /** The PC confirmed it dropped the job [ref]. */
    fun heard(s: CatalogState, ref: String): CatalogState = s.copy(retired = s.retired.filter { it.ref != ref })

    /**
     * Orders about a garment already sold by hand. Hand records are sales outside Vinted, so
     * this should not happen: nothing is joined or cancelled here, the user is only told.
     */
    fun doubts(s: CatalogState): List<Pair<Sale, Sale>> {
        val byHand = s.sales.filter { it.order.isEmpty() && it.status != SaleStatus.CANCELLED }
        return s.sales.filter { it.order.isNotEmpty() && it.status != SaleStatus.CANCELLED }
            .mapNotNull { order -> byHand.firstOrNull { sameGarment(s, order, it) }?.let { order to it } }
    }

    /** Only certainties: the same listing, or the very same article. Codes and prices repeat. */
    private fun sameGarment(s: CatalogState, order: Sale, hand: Sale): Boolean {
        val listing = order.itemId.isNotBlank() &&
            (order.itemId == hand.itemId || order.itemId in hand.listings || article(s, hand)?.listings()?.contains(order.itemId) == true)
        return listing || (order.listedAt != null && order.listedAt == hand.listedAt && order.number == hand.number)
    }

    /** The user says that order and that hand record are one sale: Vinted's data, the hand record's cost. */
    fun merge(s: CatalogState, id: String): CatalogState {
        val (order, hand) = doubts(s).firstOrNull { it.first.id == id } ?: throw CatalogException("Esa venta no tiene posible repetida")
        // The article assigned to the order, if any; else the one the hand record was about.
        val about = if (order.listedAt != null || hand.listedAt == null) order else hand
        val joined = order.copy(
            id = hand.id,
            number = about.number,
            listedAt = about.listedAt,
            cost = hand.cost ?: order.cost,
            lotId = hand.lotId ?: order.lotId,
            brand = hand.brand.ifBlank { order.brand },
            type = hand.type.ifBlank { order.type },
            title = order.title.ifBlank { hand.title },
            listings = (hand.listings + order.listings).distinct(),
            numberFreed = about.listedAt != null && article(s, about)?.soldIn.let { it != hand.id && it != id },
        )
        val next = s.copy(
            sales = s.sales.mapNotNull { if (it.id == hand.id) joined else it.takeIf { v -> v.id != id } },
            articles = s.articles.map { if (it.soldIn == id) it.copy(soldIn = hand.id) else it },
        )
        // A finished order takes its article away.
        val kept = article(next, joined)?.takeIf { it.soldIn == joined.id }
        return if (joined.status == SaleStatus.COMPLETED && kept != null) free(next, kept) else next
    }

    /** Vinted finished it: nothing changes it any more. */
    internal fun settle(s: CatalogState, id: String): CatalogState = replace(s, required(s, id).copy(settled = true))

    /** Gives an id to the sales of earlier versions. */
    fun ensureIds(s: CatalogState): CatalogState {
        if (s.sales.none { it.id.isBlank() }) return s
        return s.copy(sales = s.sales.mapIndexed { i, sale -> if (sale.id.isBlank()) sale.copy(id = "a$i-${sale.soldAt}") else sale })
    }

    /** Article numbers in use [before] that are free [after]: their photos must go. */
    fun freed(before: CatalogState, after: CatalogState): List<Int> {
        val kept = after.articles.mapTo(HashSet()) { it.number to it.createdAt }
        return before.articles.filter { (it.number to it.createdAt) !in kept }.map { it.number }
    }

    /** [sale] with everything it takes from its article. */
    internal fun link(s: CatalogState, sale: Sale, a: Article): Sale {
        val l = a.listing
        val account = Catalog.allAccounts(s).firstOrNull { it.id == sale.accountId } ?: s.mainAccount
        return sale.copy(
            // The listing is only certain when the article has a single one.
            itemId = sale.itemId.ifBlank { a.listings().distinct().singleOrNull().orEmpty() },
            listings = (sale.listings + a.listings()).distinct(),
            title = sale.title.ifBlank { Catalog.listingText(s, a.number, account)?.title.orEmpty() },
            number = a.number,
            listedAt = a.createdAt,
            cost = sale.cost ?: Ledger.costOf(s, a),
            lotId = a.lotId,
            brand = if (l == null || l.noBrand) "" else l.brand.trim(),
            type = l?.type.orEmpty(),
        )
    }

    /** Takes the article out of stock for the sale [id]; the PC, if it has it, must leave it alone. */
    internal fun hold(s: CatalogState, number: Int, id: String): CatalogState = change(s, number) { a ->
        val pcTask = a.pcTask
        a.copy(soldIn = id, pcTask = if (pcTask == null || pcTask.status in ON_PHONE) pcTask else pcTask.copy(pending = OFF))
    }

    private fun restore(s: CatalogState, article: Article): CatalogState = change(s, article.number) { a ->
        val pcTask = a.pcTask
        // The PC had not created it and has dropped it for good: it starts over as a new job.
        val dropped = pcTask != null && pcTask.status !in ON_PHONE && pcTask.status !in IN_VINTED
        a.copy(
            soldIn = "",
            vinted = a.vinted.mapValues { (_, v) -> if (v.status == "sold") v.copy(status = "active") else v },
            pcTask = when {
                pcTask == null || pcTask.status in ON_PHONE -> pcTask
                dropped -> null
                else -> pcTask.copy(pending = pcTask.status)
            },
            round = if (dropped) a.round + 1 else a.round,
        )
    }

    /** The article goes: its number is free and the PC, if it knows the job, is to drop it. */
    internal fun free(s: CatalogState, a: Article): CatalogState {
        val job = a.pcTask?.takeIf { it.status !in ON_PHONE }?.copy(pending = OFF)
        return s.copy(
            articles = s.articles.filter { it.number != a.number },
            retired = if (job == null) s.retired else s.retired.filter { it.ref != job.ref } + job,
            session = s.session?.takeIf { it.number != a.number },
        )
    }

    /** The listings known to be [this] article's. */
    internal fun Article.listings(): List<String> = (vinted.values.map { it.id } + pcTask?.itemId.orEmpty()).filter { it.isNotBlank() }

    private fun required(s: CatalogState, id: String): Sale = sale(s, id) ?: throw CatalogException("Venta no encontrada")

    internal fun replace(s: CatalogState, sale: Sale): CatalogState = s.copy(sales = s.sales.map { if (it.id == sale.id) sale else it })

    private fun change(s: CatalogState, number: Int, block: (Article) -> Article): CatalogState =
        s.copy(articles = s.articles.map { if (it.number == number) block(it) else it })
}
