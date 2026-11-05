package dev.catalogo.core

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

/** One sold article. [cost] is null when unknown. */
@Serializable
data class Sale(
    val number: Int,
    val deviceId: String,
    val price: Long,
    val cost: Long?,
    val lotId: String?,
    val soldAt: Long,
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

    data class LotStats(val sold: Int, val quantity: Int, val inStock: Int, val recovered: Long, val profit: Long)

    data class Totals(val count: Int, val revenue: Long, val cost: Long, val profit: Long)

    fun lot(s: ParentState, id: String?): Lot? = id?.let { i -> s.lots.firstOrNull { it.id == i } }

    fun unitCost(lot: Lot): Long = if (lot.quantity <= 0) 0 else (lot.price.toDouble() / lot.quantity).roundToLong()

    /** Manual cost first, otherwise the lot's cost per garment. */
    fun costOf(s: ParentState, a: Article): Long? = a.cost ?: lot(s, a.lotId)?.let(::unitCost)

    fun addLot(s: ParentState, id: String, name: String, price: Long, quantity: Int, now: Long): ParentState {
        validateLot(name, price, quantity)
        return s.copy(lots = s.lots + Lot(id, name.trim(), price, quantity, now))
    }

    fun updateLot(s: ParentState, id: String, name: String, price: Long, quantity: Int): ParentState {
        validateLot(name, price, quantity)
        return s.copy(lots = s.lots.map { if (it.id == id) it.copy(name = name.trim(), price = price, quantity = quantity) else it })
    }

    /** Removes a lot; its articles keep existing without a lot, past sales keep their numbers. */
    fun deleteLot(s: ParentState, id: String): ParentState = s.copy(
        lots = s.lots.filter { it.id != id },
        articles = s.articles.map { if (it.lotId == id) it.copy(lotId = null) else it },
    )

    private fun validateLot(name: String, price: Long, quantity: Int) {
        if (name.isBlank() || name.length > 40) throw CatalogException("Nombre de 1 a 40 caracteres")
        if (price < 0) throw CatalogException("Precio no válido")
        if (quantity !in 1..10_000) throw CatalogException("Número de prendas no válido")
    }

    /**
     * Records the sale of [number] (sold through [deviceId]) and releases it on
     * every phone. An article already released is not sold twice.
     */
    fun sell(s: ParentState, number: Int, deviceId: String, price: Long, cost: Long?, now: Long): ParentState {
        val a = Catalog.article(s, number) ?: return s
        val fromChild = deviceId != s.self.id
        if (a.released) return if (fromChild) Catalog.release(s, number, confirmedBy = deviceId) else s
        val sale = Sale(number, deviceId, price, cost ?: costOf(s, a), a.lotId, now)
        val withSale = s.copy(sales = s.sales + sale)
        return if (fromChild) Catalog.release(withSale, number, confirmedBy = deviceId) else Catalog.release(withSale, number)
    }

    fun stats(s: ParentState, lot: Lot): LotStats {
        val sales = s.sales.filter { it.lotId == lot.id }
        val recovered = sales.sumOf { it.price }
        val inStock = s.articles.count { it.lotId == lot.id && !it.released }
        return LotStats(sales.size, lot.quantity, inStock, recovered, recovered - lot.price)
    }

    fun totals(sales: List<Sale>): Totals {
        val revenue = sales.sumOf { it.price }
        val cost = sales.sumOf { it.cost ?: 0 }
        return Totals(sales.size, revenue, cost, revenue - cost)
    }

    fun totalsBetween(s: ParentState, from: Long, until: Long): Totals =
        totals(s.sales.filter { it.soldAt in from until until })
}
