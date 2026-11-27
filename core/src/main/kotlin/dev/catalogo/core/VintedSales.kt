package dev.catalogo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Bringing in what the PC read in «Mis pedidos › Ventas». The phone never talks to
 * Vinted: it asks the PC, which reads the pages, and takes the result from it. An
 * order is a sale; the PC only sends those of listings made by this system (or with
 * its code). What Vinted says about an order beats what was written here by hand.
 * The article it was is the one with that listing; without one known, the one named
 * by the last hashtag of its description (SH0012 = article 0012 of the account with
 * prefix SH). Numbers are reused, so two orders may well carry the same code.
 * Nothing about the buyer or the chat comes.
 */
object VintedSales {
    private const val DAY = 86_400_000L

    /** One line of the sales list. [date] is when the order was opened. */
    data class Order(val id: String, val title: String, val price: Long, val status: SaleStatus, val detail: String, val date: Long, val photo: String)

    /** A garment of an order. [description] is null when its listing was not read. */
    data class Item(val id: String, val price: Long?, val title: String, val description: String?)

    /** What an order was: who sold it, when it was paid and which garments (several in a bundle). */
    data class Deal(val sellerId: String, val soldAt: Long?, val items: List<Item>)

    /**
     * What the PC says: whether it is reading ([working]), why it has not started ([waiting]),
     * how many orders it still has to look into ([pending]) and the ones already read, oldest first.
     * [lastRead] and [next] are when it read and will read on its own (0 = unknown).
     */
    data class Reading(
        val working: Boolean, val waiting: String, val pending: Int, val orders: List<Pair<Order, Deal?>>,
        val lastRead: Long = 0, val next: Long = 0,
    )

    enum class Kind { NEW_PENDING, NEW_COMPLETED, NEW_CANCELLED, COMPLETED, CANCELLED, REOPENED, CONFLICT, UNPAID, UNCHANGED }

    private val json = Json { ignoreUnknownKeys = true }

    /** The answer of the PC to `/ventas`. */
    fun parsePc(body: String): Reading {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
        val list = root?.get("pedidos") as? JsonArray
            ?: throw CatalogException("El PC ha respondido algo inesperado: actualiza su programa")
        val orders = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val price = o.str("precio")?.toLongOrNull() ?: return@mapNotNull null
            val status = when (o.str("estado")) {
                "completada" -> SaleStatus.COMPLETED
                "cancelada" -> SaleStatus.CANCELLED
                // Anything else, known or not, can still change.
                else -> SaleStatus.PENDING
            }
            val date = o.str("fecha")?.toLongOrNull() ?: 0
            val order = Order(id, o.str("titulo").orEmpty(), price, status, o.str("detalle").orEmpty(), date, "")
            val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { i ->
                val item = i as? JsonObject ?: return@mapNotNull null
                val itemId = item.str("id") ?: return@mapNotNull null
                val code = item.str("codigo").orEmpty()
                Item(itemId, null, "", if (code.isEmpty()) "" else "#$code")
            }
            order to items.takeIf { it.isNotEmpty() }?.let { Deal("", date, it) }
        }.sortedBy { it.first.date }
        fun time(key: String) = root.str(key)?.toLongOrNull() ?: 0
        return Reading(
            root.str("modo") == "trabajando", root.str("esperando").orEmpty(), root.str("pendientes")?.toIntOrNull() ?: 0, orders,
            time("leida"), time("proxima"),
        )
    }

    /** The article prefixes the PC must recognise in the hashtags. */
    fun prefixes(s: ParentState): List<String> = Catalog.devices(s).map { it.prefix }

    /**
     * Brings in a whole reading and counts what happened to the orders that changed. One
     * already cancelled when first seen was never a sale.
     */
    fun applyAll(s: ParentState, accountId: String, orders: List<Pair<Order, Deal?>>, now: Long): Pair<ParentState, Map<Kind, Int>> {
        val counts = LinkedHashMap<Kind, Int>()
        val state = orders.fold(s) { state, (order, deal) ->
            if (order.status == SaleStatus.CANCELLED && state.sales.none { it.order == order.id }) return@fold state
            val (next, kind) = apply(state, accountId, order, deal, now)
            if (kind != Kind.UNCHANGED) counts[kind] = (counts[kind] ?: 0) + 1
            next
        }
        return state to counts
    }

    private fun byListing(s: ParentState, itemId: String): Article? =
        if (itemId.isBlank()) null else s.articles.firstOrNull { !it.released && itemId in with(Sales) { it.listings() } }

    /** An order kept apart takes its article as soon as the listing is known to be one of the app's. */
    private fun relink(s: ParentState, id: String): ParentState {
        val sale = Sales.sale(s, id) ?: return s
        if (sale.status != SaleStatus.PENDING || sale.listedAt != null) return s
        // A cancelled hand sale may be the only one that knows which article the listing was.
        val dropped = s.sales.filter { it.order.isEmpty() && it.status == SaleStatus.CANCELLED && sale.itemId in it.listings }
        val a = (byListing(s, sale.itemId) ?: dropped.firstNotNullOfOrNull { Sales.article(s, it) })?.takeIf { it.inStock } ?: return s
        return Sales.assign(s, id, a.number)
    }

    /** The account and article number of the last hashtag, if it is one of the app's codes. */
    fun code(description: String, s: ParentState): Pair<Device, Int>? {
        val tag = Regex("#([\\p{L}\\p{N}_]+)").findAll(description).lastOrNull()?.groupValues?.get(1)?.uppercase() ?: return null
        return Catalog.devices(s).firstNotNullOfOrNull { d -> Sku.parse(tag, d.prefix, s.digits)?.let { d to it } }
    }

    /** Brings one order up to date. [accountId] is used when [deal] does not say who sold it. */
    fun apply(s: ParentState, accountId: String, order: Order, deal: Deal?, now: Long): Pair<ParentState, Kind> {
        val known = s.sales.filter { it.order == order.id }
        if (known.isNotEmpty()) {
            var state = s
            var kind = Kind.UNCHANGED
            for (sale in known) {
                val (next, k) = update(relink(state, sale.id), sale.id, order, now)
                state = next
                if (k != Kind.UNCHANGED) kind = k
            }
            return state to kind
        }
        val items = deal?.items ?: listOf(Item("", order.price, order.title, null))
        val prices = share(order.price, items.map { it.price })
        var state = s
        var kind = Kind.UNCHANGED
        items.forEachIndexed { i, item ->
            val id = if (items.size == 1) "v${order.id}" else "v${order.id}-$i"
            val (next, k) = add(state, id, accountId, order, deal, item, prices[i], now)
            state = next
            kind = k
        }
        return state to kind
    }

    private fun add(
        s: ParentState, id: String, accountId: String, order: Order, deal: Deal?, item: Item, price: Long, now: Long,
    ): Pair<ParentState, Kind> {
        val soldAt = deal?.soldAt ?: order.date
        val code = item.description?.let { code(it, s) }
        // The listing says which article it was for certain. The code only says its number, and
        // numbers are reused: not an article made after the sale, nor one with another listing.
        val found = if (deal == null) null else byListing(s, item.id) ?: code?.let { (_, n) ->
            Catalog.article(s, n)?.takeIf { !it.released && it.createdAt <= soldAt + DAY && with(Sales) { it.listings() }.isEmpty() }
        }
        val account = Catalog.devices(s).firstOrNull { it.vintedId.isNotBlank() && it.vintedId == deal?.sellerId }?.id
            ?: code?.first?.id ?: accountId
        val codeText = code?.let { (d, n) -> Sku.format(d.prefix, n, s.digits) }.orEmpty()

        var sale = Sale(
            found?.number ?: code?.second ?: -1, account, price, null, null, soldAt, id = id, status = order.status,
            order = order.id, itemId = item.id, code = codeText, title = item.title.ifBlank { order.title },
            photo = order.photo, detail = order.detail,
            closedAt = if (order.status == SaleStatus.PENDING) null else order.date,
            settled = order.status == SaleStatus.COMPLETED,
        )
        // An article already held by another sale is not taken from it.
        val free = found?.takeIf { it.inStock }
        val kind = when (order.status) {
            SaleStatus.PENDING -> Kind.NEW_PENDING
            SaleStatus.COMPLETED -> Kind.NEW_COMPLETED
            SaleStatus.CANCELLED -> Kind.NEW_CANCELLED
        }
        if (free == null) return heard(s, found, item.id).let { it.copy(sales = it.sales + sale) } to kind
        // A cancelled order only remembers which article it was about.
        if (order.status == SaleStatus.CANCELLED) return s.copy(sales = s.sales + Sales.link(s, sale, free)) to kind
        sale = Sales.link(s, sale, free).copy(status = SaleStatus.PENDING, closedAt = null)
        var next = Sales.hold(s.copy(sales = s.sales + sale), free.number, id)
        if (order.status == SaleStatus.COMPLETED) next = Sales.settle(Sales.complete(next, id, now), id)
        return next to kind
    }

    private fun update(s: ParentState, id: String, order: Order, now: Long): Pair<ParentState, Kind> {
        val before = Sales.sale(s, id) ?: return s to Kind.UNCHANGED
        // Finished in Vinted is finished: nothing it says later changes it.
        if (before.settled) return s to Kind.UNCHANGED
        val sale = before.copy(detail = order.detail, photo = before.photo.ifBlank { order.photo })
        val state = if (sale == before) s else Sales.replace(s, sale)
        return when (sale.status) {
            // Given as paid here: Vinted knows whether the money is really in.
            SaleStatus.COMPLETED -> when (order.status) {
                SaleStatus.COMPLETED -> Sales.settle(state, id) to Kind.UNCHANGED
                SaleStatus.PENDING -> Sales.replace(state, sale.copy(
                    status = SaleStatus.PENDING, closedAt = null, numberFreed = sale.listedAt != null,
                )) to Kind.UNPAID
                SaleStatus.CANCELLED -> Sales.replace(state, sale.copy(
                    status = SaleStatus.CANCELLED, byHand = false, closedAt = now, numberFreed = sale.listedAt != null,
                )) to Kind.CANCELLED
            }
            SaleStatus.PENDING -> when (order.status) {
                SaleStatus.PENDING -> state to Kind.UNCHANGED
                SaleStatus.COMPLETED -> Sales.settle(Sales.complete(state, id, now), id) to Kind.COMPLETED
                SaleStatus.CANCELLED -> Sales.cancel(state, id, now, byHand = false) to Kind.CANCELLED
            }
            SaleStatus.CANCELLED -> when {
                sale.byHand -> when (order.status) {
                    SaleStatus.PENDING -> state to Kind.UNCHANGED
                    SaleStatus.COMPLETED ->
                        if (sale.conflict) state to Kind.UNCHANGED
                        else Sales.replace(state, sale.copy(conflict = true)) to Kind.CONFLICT
                    // Vinted agrees now.
                    SaleStatus.CANCELLED -> Sales.replace(state, sale.copy(byHand = false, conflict = false)) to Kind.UNCHANGED
                }
                order.status == SaleStatus.CANCELLED -> state to Kind.UNCHANGED
                !Sales.canReopen(state, sale) ->
                    if (sale.conflict) state to Kind.UNCHANGED
                    else Sales.replace(state, sale.copy(conflict = true)) to Kind.CONFLICT
                order.status == SaleStatus.PENDING -> Sales.reopen(state, id) to Kind.REOPENED
                else -> Sales.settle(Sales.complete(Sales.reopen(state, id), id, now), id) to Kind.COMPLETED
            }
        }
    }

    /** The order is about an article a hand sale keeps: that sale learns the listing, so it can warn. */
    private fun heard(s: ParentState, a: Article?, itemId: String): ParentState {
        val hand = a?.let { Sales.sale(s, it.soldIn) }?.takeIf { it.order.isEmpty() && itemId.isNotBlank() } ?: return s
        return Sales.replace(s, hand.copy(listings = (hand.listings + itemId).distinct()))
    }

    /** Splits the money of a bundle by the garments' prices (equally without them); the cents add up. */
    private fun share(total: Long, listed: List<Long?>): List<Long> {
        val weights = if (listed.all { it != null && it > 0 }) listed.map { it!! } else listed.map { 1L }
        val sum = weights.sum()
        val parts = weights.map { total * it / sum }.toMutableList()
        var rest = total - parts.sum()
        var i = 0
        while (rest > 0) {
            parts[i % parts.size] = parts[i % parts.size] + 1
            rest--
            i++
        }
        return parts
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)
        ?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
}
