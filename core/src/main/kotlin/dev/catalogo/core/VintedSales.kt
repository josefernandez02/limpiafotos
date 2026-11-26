package dev.catalogo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Bringing in what «Mis pedidos › Ventas» says. An order is a sale; the article it
 * was is the one named by the last hashtag of its listing's description
 * (SH0012 = article 0012 of the account with prefix SH). Only the order, the
 * garment and the seller are read: nothing about the buyer or the chat.
 */
object VintedSales {
    const val PER_PAGE = 100
    const val MAX_PAGES = 30
    private const val DAY = 86_400_000L

    /** One line of the sales list. [date] is its last change. */
    data class Order(val id: String, val title: String, val price: Long, val status: SaleStatus, val detail: String, val date: Long, val photo: String)

    data class Page(val orders: List<Order>, val pages: Int)

    /** A garment of an order. [description] is null when its listing no longer exists. */
    data class Item(val id: String, val price: Long?, val title: String, val description: String?)

    /** What an order was: who sold it, when it was paid and which garments (several in a bundle). */
    data class Deal(val sellerId: String, val soldAt: Long?, val items: List<Item>)

    enum class Kind { NEW_PENDING, NEW_COMPLETED, NEW_CANCELLED, COMPLETED, CANCELLED, REOPENED, CONFLICT, UNCHANGED }

    fun path(page: Int) =
        "https://api.vinted.es/escrow-orders/api/v2/current-user/escrow-orders?page=$page&per_page=$PER_PAGE&type=sold&status=all"

    /** The same list at its older address, for when the one above is not there. */
    fun oldPath(page: Int) = "/api/v2/my_orders?type=sold&status=all&page=$page&per_page=$PER_PAGE"

    private val json = Json { ignoreUnknownKeys = true }

    fun parseList(body: String): Page {
        val root = root(body)
        val list = root["my_orders"] as? JsonArray ?: throw CatalogException("Vinted ha cambiado el formato de las ventas")
        val orders = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("transaction_id") ?: return@mapNotNull null
            val price = cents((o["price"] as? JsonObject)?.str("amount") ?: o.str("price")) ?: return@mapNotNull null
            val status = when (o.str("transaction_user_status")) {
                "completed" -> SaleStatus.COMPLETED
                "failed" -> SaleStatus.CANCELLED
                // Anything else, known or not, can still change.
                else -> SaleStatus.PENDING
            }
            Order(id, o.str("title").orEmpty(), price, status, o.str("status").orEmpty(), time(o.str("date")) ?: 0, o.str("photo_url").orEmpty())
        }
        if (list.isNotEmpty() && orders.isEmpty()) throw CatalogException("Vinted ha cambiado el formato de las ventas")
        val pages = (root["pagination"] as? JsonObject)?.str("total_pages")?.toIntOrNull() ?: 1
        return Page(orders, pages)
    }

    /** The id of the signed-in user, from `/api/v2/users/current`. */
    fun userId(body: String): String = (root(body)["user"] as? JsonObject)?.str("id").orEmpty()

    /** `{"seller_id":…, "sold_at":…, "items":[{"id":…,"price":…,"title":…,"description":…}]}`, as the app's page script builds it. */
    fun parseDeal(body: String): Deal {
        val root = root(body)
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            Item(id, cents(o.str("price")), o.str("title").orEmpty(), o.str("description"))
        }
        if (items.isEmpty()) throw CatalogException("Vinted no dice qué prenda era")
        return Deal(root.str("seller_id").orEmpty(), time(root.str("sold_at")), items)
    }

    /** The account and article number of the last hashtag, if it is one of the app's codes. */
    fun code(description: String, s: ParentState): Pair<Device, Int>? {
        val tag = Regex("#([\\p{L}\\p{N}_]+)").findAll(description).lastOrNull()?.groupValues?.get(1)?.uppercase() ?: return null
        return Catalog.devices(s).firstNotNullOfOrNull { d -> Sku.parse(tag, d.prefix, s.digits)?.let { d to it } }
    }

    /**
     * Whether it is worth asking Vinted what [order] was: only if it can move an
     * article of the app or is a sale already registered by hand. Older history is
     * recorded as it comes (Vinted limits how many requests can be made).
     */
    fun needsLookup(s: ParentState, order: Order): Boolean {
        if (s.sales.any { it.order == order.id }) return false
        // A sale registered by hand can only be an order that changed around then or later.
        val byHand = s.sales.any { it.order.isEmpty() && it.status != SaleStatus.CANCELLED && order.date >= it.soldAt - 30 * DAY }
        if (order.status == SaleStatus.CANCELLED) return byHand
        return byHand || s.articles.any { it.inStock && it.createdAt <= order.date + DAY }
    }

    /** Brings one order up to date. [accountId] is used when [deal] does not say who sold it. */
    fun apply(s: ParentState, accountId: String, order: Order, deal: Deal?, now: Long): Pair<ParentState, Kind> {
        val known = s.sales.filter { it.order == order.id }
        if (known.isNotEmpty()) {
            var state = s
            var kind = Kind.UNCHANGED
            for (sale in known) {
                val (next, k) = update(state, sale.id, order, now)
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
        // An article created after the sale only shares the number of the one sold.
        fun Article.couldBeSold() = !released && createdAt <= soldAt + DAY
        val found = if (deal == null) null else code?.let { (_, n) -> Catalog.article(s, n)?.takeIf { it.couldBeSold() } }
            ?: s.articles.firstOrNull { a ->
                a.couldBeSold() && item.id.isNotEmpty() && (a.desk?.itemId == item.id || a.vinted.values.any { it.id == item.id })
            }
        val account = Catalog.devices(s).firstOrNull { it.vintedId.isNotBlank() && it.vintedId == deal?.sellerId }?.id
            ?: code?.first?.id ?: accountId
        val codeText = code?.let { (d, n) -> Sku.format(d.prefix, n, s.digits) }.orEmpty()

        val manual = byHand(s, found, code?.second, price, soldAt)
        if (manual != null) {
            val joined = manual.copy(
                order = order.id, itemId = item.id, code = codeText, title = item.title.ifBlank { order.title },
                photo = order.photo, price = price, deviceId = account, detail = order.detail,
            )
            val (next, k) = update(Sales.replace(s, joined), manual.id, order, now)
            return next to k
        }

        var sale = Sale(
            found?.number ?: code?.second ?: -1, account, price, null, null, soldAt, id = id, status = order.status,
            order = order.id, itemId = item.id, code = codeText, title = item.title.ifBlank { order.title },
            photo = order.photo, detail = order.detail,
            closedAt = if (order.status == SaleStatus.PENDING) null else order.date,
        )
        // An article already held by another sale is not taken from it.
        val free = found?.takeIf { it.inStock }
        val kind = when (order.status) {
            SaleStatus.PENDING -> Kind.NEW_PENDING
            SaleStatus.COMPLETED -> Kind.NEW_COMPLETED
            SaleStatus.CANCELLED -> Kind.NEW_CANCELLED
        }
        if (free == null) return s.copy(sales = s.sales + sale) to kind
        // A cancelled order only remembers which article it was about.
        if (order.status == SaleStatus.CANCELLED) return s.copy(sales = s.sales + Sales.link(s, sale, free)) to kind
        sale = Sales.link(s, sale, free).copy(status = SaleStatus.PENDING, closedAt = null)
        var next = Sales.hold(s.copy(sales = s.sales + sale), free.number, id)
        if (order.status == SaleStatus.COMPLETED) next = Sales.complete(next, id, now)
        return next to kind
    }

    /** The sale registered here (by hand or by an earlier version) that [found], or the code, says is this same one. */
    private fun byHand(s: ParentState, found: Article?, number: Int?, price: Long, soldAt: Long): Sale? {
        val manual = s.sales.filter { it.order.isEmpty() && it.status != SaleStatus.CANCELLED }
        // With the article still here, only a sale of that very article is this one.
        if (found != null) return manual.firstOrNull { it.number == found.number && it.listedAt == found.createdAt }
        if (number == null) return null
        // Its article is gone already: same number, same money, around the same days.
        return manual.firstOrNull { it.number == number && it.price == price && abs(it.soldAt - soldAt) < 30 * DAY && Sales.article(s, it) == null }
    }

    private fun update(s: ParentState, id: String, order: Order, now: Long): Pair<ParentState, Kind> {
        val before = Sales.sale(s, id) ?: return s to Kind.UNCHANGED
        // Finished is finished: nothing Vinted says later changes it.
        if (before.status == SaleStatus.COMPLETED) return s to Kind.UNCHANGED
        val sale = before.copy(detail = order.detail, photo = before.photo.ifBlank { order.photo })
        val state = if (sale == before) s else Sales.replace(s, sale)
        return when (sale.status) {
            SaleStatus.COMPLETED -> state to Kind.UNCHANGED   // (handled above)
            SaleStatus.PENDING -> when (order.status) {
                SaleStatus.PENDING -> state to Kind.UNCHANGED
                SaleStatus.COMPLETED -> Sales.complete(state, id, now) to Kind.COMPLETED
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
                else -> Sales.complete(Sales.reopen(state, id), id, now) to Kind.COMPLETED
            }
        }
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

    private fun root(body: String): JsonObject = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
        ?: throw CatalogException("Vinted ha respondido algo inesperado")

    private fun cents(text: String?): Long? = text?.replace(',', '.')?.toDoubleOrNull()?.let { (it * 100).roundToLong() }

    private fun time(text: String?): Long? = text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)
        ?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
}
