package dev.catalogo.core

/**
 * The life of a sale. Pure transitions of the parent's state; deleting photos and
 * telling the PC is left to the caller ([freed], [ParentState.retired] and the
 * article's pending desk state say what is to be done).
 *
 *  - PENDING: sold, but the order can still be cancelled. The article stays, out of
 *    stock, with its number, data and photos.
 *  - COMPLETED: final. The article is gone and its number is free.
 *  - CANCELLED: the article is back in stock.
 */
object Sales {

    /** Desk states of a job that is already in Vinted: the only ones the PC takes back by hand. */
    private val IN_VINTED = setOf("borrador", "publicado", "jugado")

    /** Desk states of a job that has not left this phone («enviando» may have reached the PC already). */
    private val ON_PHONE = setOf(Desk.PREPARING, Desk.REVIEW)

    private const val OFF = "fuera"

    fun sale(s: ParentState, id: String): Sale? = s.sales.firstOrNull { it.id == id }

    /** The article [sale] is about, if it is still here (its number may belong to another one by now). */
    fun article(s: ParentState, sale: Sale): Article? {
        val listedAt = sale.listedAt ?: return null
        return Catalog.article(s, sale.number)?.takeIf { it.createdAt == listedAt && !it.released }
    }

    /** A sale registered by hand: [account] sold [number], and it stays in progress until said otherwise. */
    fun register(s: ParentState, number: Int, id: String, account: String, price: Long, cost: Long?, now: Long): ParentState {
        val a = Catalog.article(s, number)?.takeIf { !it.released } ?: throw CatalogException("Artículo no disponible")
        if (a.held) throw CatalogException("Ya tiene una venta en curso")
        val sale = link(s, Sale(number, account, price, cost, null, now, id = id, status = SaleStatus.PENDING), a)
        return hold(s.copy(sales = s.sales + sale), a.number, id)
    }

    /** The money is in: the sale is final and the number becomes free. */
    fun complete(s: ParentState, id: String, now: Long): ParentState {
        val sale = required(s, id)
        if (sale.status != SaleStatus.PENDING) throw CatalogException("Solo se completa una venta en curso")
        val done = replace(s, sale.copy(status = SaleStatus.COMPLETED, closedAt = now, conflict = false))
        val a = article(s, sale)?.takeIf { it.soldIn == id } ?: return done
        return free(done, a)
    }

    /** The order fell through (cancelled, lost, returned): everything goes back to how it was. */
    fun cancel(s: ParentState, id: String, now: Long, byHand: Boolean = true): ParentState {
        val sale = required(s, id)
        if (sale.status == SaleStatus.COMPLETED) throw CatalogException("Una venta completada ya no se puede anular")
        if (sale.status == SaleStatus.CANCELLED) return s
        val done = replace(s, sale.copy(status = SaleStatus.CANCELLED, byHand = byHand, closedAt = now))
        val a = article(s, sale)?.takeIf { it.soldIn == id } ?: return done
        return restore(done, a)
    }

    /** Whether a cancelled sale can go back to «in progress»: its article, if it had one, is still free. */
    fun canReopen(s: ParentState, sale: Sale): Boolean =
        sale.status == SaleStatus.CANCELLED && (sale.listedAt == null || article(s, sale)?.held == false)

    fun reopen(s: ParentState, id: String): ParentState {
        val sale = required(s, id)
        if (!canReopen(s, sale)) throw CatalogException("Esta venta ya no se puede recuperar")
        val next = replace(s, sale.copy(status = SaleStatus.PENDING, byHand = false, conflict = false, closedAt = null))
        val a = article(s, sale) ?: return next
        return hold(next, a.number, id)
    }

    /** The user agrees with Vinted that a sale cancelled here did finish. */
    fun accept(s: ParentState, id: String, now: Long): ParentState {
        val sale = required(s, id)
        if (!sale.conflict) return s
        if (canReopen(s, sale)) return complete(reopen(s, id), id, now)
        // Its article is busy elsewhere or gone: the money is recorded, the article is not touched.
        return replace(s, sale.copy(status = SaleStatus.COMPLETED, conflict = false, byHand = false, closedAt = now))
    }

    /** Says which article an unlinked sale was. */
    fun assign(s: ParentState, id: String, number: Int): ParentState {
        val sale = required(s, id)
        if (sale.status == SaleStatus.CANCELLED) throw CatalogException("La venta está anulada")
        if (article(s, sale) != null || sale.listedAt != null) throw CatalogException("Esa venta ya tiene artículo")
        val a = Catalog.article(s, number)?.takeIf { it.inStock } ?: throw CatalogException("Artículo no disponible")
        val held = hold(replace(s, link(s, sale, a)), number, id)
        // A finished sale takes its article away at once.
        return if (sale.status == SaleStatus.PENDING) held else free(held, Catalog.article(held, number)!!)
    }

    /** The PC confirmed it dropped the job [ref]. */
    fun heard(s: ParentState, ref: String): ParentState = s.copy(retired = s.retired.filter { it.ref != ref })

    /** Gives an id to the sales of earlier versions. */
    fun ensureIds(s: ParentState): ParentState {
        if (s.sales.none { it.id.isBlank() }) return s
        return s.copy(sales = s.sales.mapIndexed { i, sale -> if (sale.id.isBlank()) sale.copy(id = "a$i-${sale.soldAt}") else sale })
    }

    /** Article numbers in use [before] that are free [after]: their photos must go. */
    fun freed(before: ParentState, after: ParentState): List<Int> {
        val kept = after.articles.mapTo(HashSet()) { it.number to it.createdAt }
        return before.articles.filter { (it.number to it.createdAt) !in kept }.map { it.number }
    }

    /** [sale] with everything it takes from its article. */
    internal fun link(s: ParentState, sale: Sale, a: Article): Sale {
        val l = a.listing
        return sale.copy(
            number = a.number,
            listedAt = a.createdAt,
            cost = sale.cost ?: Ledger.costOf(s, a),
            lotId = a.lotId,
            brand = if (l == null || l.noBrand) "" else l.brand.trim(),
            type = l?.type.orEmpty(),
        )
    }

    /** Takes the article out of stock for the sale [id]; the PC, if it has it, must leave it alone. */
    internal fun hold(s: ParentState, number: Int, id: String): ParentState = change(s, number) { a ->
        val desk = a.desk
        a.copy(soldIn = id, desk = if (desk == null || desk.status in ON_PHONE) desk else desk.copy(pending = OFF))
    }

    private fun restore(s: ParentState, article: Article): ParentState = change(s, article.number) { a ->
        val desk = a.desk
        // The PC had not created it and has dropped it for good: it starts over as a new job.
        val dropped = desk != null && desk.status !in ON_PHONE && desk.status !in IN_VINTED
        a.copy(
            soldIn = "",
            vinted = a.vinted.mapValues { (_, v) -> if (v.status == "sold") v.copy(status = "active") else v },
            desk = when {
                desk == null || desk.status in ON_PHONE -> desk
                dropped -> null
                else -> desk.copy(pending = desk.status)
            },
            round = if (dropped) a.round + 1 else a.round,
        )
    }

    private fun free(s: ParentState, a: Article): ParentState {
        val job = a.desk?.takeIf { it.status !in ON_PHONE }?.copy(pending = OFF)
        return s.copy(
            articles = s.articles.filter { it.number != a.number },
            retired = if (job == null) s.retired else s.retired.filter { it.ref != job.ref } + job,
            session = s.session?.takeIf { it.number != a.number },
        )
    }

    private fun required(s: ParentState, id: String): Sale = sale(s, id) ?: throw CatalogException("Venta no encontrada")

    internal fun replace(s: ParentState, sale: Sale): ParentState = s.copy(sales = s.sales.map { if (it.id == sale.id) sale else it })

    private fun change(s: ParentState, number: Int, block: (Article) -> Article): ParentState =
        s.copy(articles = s.articles.map { if (it.number == number) block(it) else it })
}
