package dev.catalogo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** A purchased batch of garments. Amounts are in cents. */
@Serializable
data class Lot(
    val id: String,
    val name: String,
    val price: Long,
    val quantity: Int,
    val createdAt: Long,
)

/** Where a sale stands. Only [COMPLETED] is final: the money is in. */
enum class SaleStatus(val label: String) { PENDING("En curso"), COMPLETED("Completada"), CANCELLED("Cancelada") }

/**
 * One sold article. [cost] is null when unknown. Sales saved by earlier versions
 * have none of the later fields: they were final the moment they were recorded.
 */
@Serializable
data class Sale(
    /** Article number, or -1 while it is not known which article it was. */
    val number: Int,
    @SerialName("deviceId") val accountId: String,
    val price: Long,
    val cost: Long?,
    val lotId: String?,
    val soldAt: Long,
    /**
     * When the article was created: with [number] it says which article it was
     * (numbers are reused), and how long it took to sell. Null = not linked.
     */
    val listedAt: Long? = null,
    val brand: String = "",
    val type: String = "",
    val id: String = "",
    val status: SaleStatus = SaleStatus.COMPLETED,
    /** Vinted's order number; blank for a sale registered by hand. */
    val order: String = "",
    /** Vinted's listing id of the garment. */
    val itemId: String = "",
    /** Every listing id its article had when the sale took it: how a later order is told to be about it. */
    val listings: List<String> = emptyList(),
    /** The code read in the listing's description, e.g. SH0012. */
    val code: String = "",
    /** Listing title and photo, as the order shows them. */
    val title: String = "",
    val photo: String = "",
    /** Vinted's own words for where the order is. */
    val detail: String = "",
    /** Cancelled by the user: an order Vinted still shows in progress does not reopen it. */
    val byHand: Boolean = false,
    /** Cancelled here, but Vinted says it finished: the user decides. */
    val conflict: Boolean = false,
    /** When it was completed or cancelled. */
    val closedAt: Long? = null,
    /** The user freed the article's number before the sale was over: the article does not come back. */
    val numberFreed: Boolean = false,
    /** Vinted itself finished it: nothing changes it again. */
    val settled: Boolean = false,
)

object Money {
    /** "12", "12,5", "12.50 €" → cents; null if not a valid non-negative amount. */
    fun parse(text: String): Long? {
        val clean = text.trim().removeSuffix("€").trim().replace(',', '.')
        if (clean.isEmpty() || !Regex("^\\d{1,7}(\\.\\d{1,2})?$").matches(clean)) return null
        return (clean.toDouble() * 100).roundToLong()
    }

    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val units = abs / 100
        val rest = abs % 100
        return if (rest == 0L) "$sign$units €" else "$sign$units,${rest.toString().padStart(2, '0')} €"
    }

    fun input(cents: Long?): String = when {
        cents == null -> ""
        cents % 100 == 0L -> (cents / 100).toString()
        else -> "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
    }
}

/** Lots, sales and their numbers. Pure functions over the parent state. */
object Ledger {

    data class LotStats(val sold: Int, val quantity: Int, val inStock: Int, val recovered: Long, val profit: Long, val pending: Int = 0)

    data class Totals(val count: Int, val revenue: Long, val cost: Long, val profit: Long)

    fun lot(s: CatalogState, id: String?): Lot? = id?.let { i -> s.lots.firstOrNull { it.id == i } }

    fun unitCost(lot: Lot): Long = if (lot.quantity <= 0) 0 else (lot.price.toDouble() / lot.quantity).roundToLong()

    /** Manual cost first, otherwise the lot's cost per garment. */
    fun costOf(s: CatalogState, a: Article): Long? = a.cost ?: lot(s, a.lotId)?.let(::unitCost)

    fun addLot(s: CatalogState, id: String, name: String, price: Long, quantity: Int, now: Long): CatalogState {
        validateLot(name, price, quantity)
        return s.copy(lots = s.lots + Lot(id, name.trim(), price, quantity, now))
    }

    fun updateLot(s: CatalogState, id: String, name: String, price: Long, quantity: Int): CatalogState {
        validateLot(name, price, quantity)
        return s.copy(lots = s.lots.map { if (it.id == id) it.copy(name = name.trim(), price = price, quantity = quantity) else it })
    }

    /** Removes a lot; its articles keep existing without a lot, past sales keep their numbers. */
    fun deleteLot(s: CatalogState, id: String): CatalogState = s.copy(
        lots = s.lots.filter { it.id != id },
        articles = s.articles.map { if (it.lotId == id) it.copy(lotId = null) else it },
    )

    private fun validateLot(name: String, price: Long, quantity: Int) {
        if (name.isBlank() || name.length > 40) throw CatalogException("Nombre de 1 a 40 caracteres")
        if (price < 0) throw CatalogException("Precio no válido")
        if (quantity !in 1..10_000) throw CatalogException("Número de prendas no válido")
    }

    /** A lot's numbers: only finished sales have recovered money. */
    fun stats(s: CatalogState, lot: Lot): LotStats {
        val sales = s.sales.filter { it.lotId == lot.id }
        val done = sales.filter { it.status == SaleStatus.COMPLETED }
        val recovered = done.sumOf { it.price }
        val inStock = s.articles.count { it.lotId == lot.id && it.inStock }
        val pending = sales.count { it.status == SaleStatus.PENDING }
        return LotStats(done.size, lot.quantity, inStock, recovered, recovered - lot.price, pending)
    }

    /** Money of the finished ones among [sales]; the profit only counts those whose cost is known. */
    fun totals(sales: List<Sale>): Totals {
        val done = sales.filter { it.status == SaleStatus.COMPLETED }
        val known = done.filter { it.cost != null }
        val cost = known.sumOf { it.cost!! }
        return Totals(done.size, done.sumOf { it.price }, cost, known.sumOf { it.price } - cost)
    }
}
