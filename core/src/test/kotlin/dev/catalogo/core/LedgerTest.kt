package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LedgerTest {

    private val samsung = Device("p", "Samsung", "SH")
    private val poco = Device("c1", "Poco", "SA", "s", style = 1)

    private fun withLot(): ParentState {
        var s = ParentState(self = samsung, children = listOf(poco))
        s = Ledger.addLot(s, "L1", "Lote vaqueros", 10_000, 20, 0)
        repeat(3) { s = Catalog.create(s, 0).first }
        s = Catalog.updateArticle(s, 0, Listing(type = "jeans"), "L1", null)
        s = Catalog.updateArticle(s, 1, Listing(type = "jeans"), "L1", null)
        s = Catalog.updateArticle(s, 2, null, null, 700)
        return s
    }

    @Test
    fun `money parses and formats`() {
        assertEquals(1250, Money.parse("12,5"))
        assertEquals(1250, Money.parse("12.50 €"))
        assertEquals(1200, Money.parse("12"))
        assertNull(Money.parse("-3"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1,234"))
        assertEquals("12 €", Money.format(1200))
        assertEquals("12,05 €", Money.format(1205))
        assertEquals("-3,50 €", Money.format(-350))
        assertEquals("12,5".let { Money.parse(it) }, Money.parse(Money.input(1250)))
    }

    @Test
    fun `cost comes from the lot or the manual value`() {
        val s = withLot()
        assertEquals(500, Ledger.costOf(s, Catalog.article(s, 0)!!))
        assertEquals(700, Ledger.costOf(s, Catalog.article(s, 2)!!))
        assertNull(Ledger.costOf(s, Catalog.article(Catalog.create(s, 0).first, 3)!!))
    }

    @Test
    fun `a finished sale records the money and frees the number`() {
        var s = withLot().sold(0, "p", 2_500, null, 10)
        assertEquals(1, s.sales.size)
        assertEquals(500, s.sales.single().cost)
        assertEquals(0, Catalog.nextFree(s))
        val stats = Ledger.stats(s, s.lots.single())
        assertEquals(1, stats.sold)
        assertEquals(1, stats.inStock)
        assertEquals(2_500, stats.recovered)
        assertEquals(-7_500, stats.profit)
    }

    @Test
    fun `explicit cost wins and totals add up`() {
        var s = withLot()
        s = s.sold(2, "p", 2_000, 900, 5)
        s = s.sold(0, "p", 1_500, null, 50)
        val all = Ledger.totals(s.sales)
        assertEquals(2, all.count)
        assertEquals(3_500, all.revenue)
        assertEquals(1_400, all.cost)
        assertEquals(2_100, all.profit)
        assertEquals(1, Ledger.totalsBetween(s, 0, 10).count)
    }

    @Test
    fun `lots validate and deleting one detaches its articles`() {
        val s = withLot()
        assertFailsWith<CatalogException> { Ledger.addLot(s, "x", " ", 100, 1, 0) }
        assertFailsWith<CatalogException> { Ledger.addLot(s, "x", "Lote", 100, 0, 0) }
        assertFailsWith<CatalogException> { Catalog.updateArticle(s, 0, null, "missing", null) }
        val after = Ledger.deleteLot(s, "L1")
        assertNull(Catalog.article(after, 0)!!.lotId)
        assertEquals(500, Ledger.unitCost(s.lots.single()))
    }

    @Test
    fun `a lot overrides the manual cost`() {
        val s = Catalog.updateArticle(withLot(), 2, null, "L1", 900)
        assertNull(Catalog.article(s, 2)!!.cost)
        assertEquals(500, Ledger.costOf(s, Catalog.article(s, 2)!!))
    }
}

/** Sold and paid. */
internal fun ParentState.sold(number: Int, account: String, price: Long, cost: Long?, now: Long): ParentState =
    Sales.complete(Sales.register(this, number, "t$number-$now", account, price, cost, now), "t$number-$now", now)
