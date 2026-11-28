package dev.catalogo.core

import kotlin.math.roundToLong

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
