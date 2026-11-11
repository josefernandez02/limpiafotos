package dev.catalogo.core

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val day = 86_400_000L
    private val samsung = Device("p", "Samsung", "SH", account = "shiorawear")
    private val poco = Device("c1", "Poco", "SA", "s1", style = 1)

    @Test
    fun `kpis count stock, sales, speed and age`() {
        var s = ParentState(self = samsung, children = listOf(poco))
        s = Ledger.addLot(s, "l1", "Lote", 4000, 4, 0)
        repeat(4) { i ->
            val (next, n) = Catalog.create(s, i * 10 * day)
            s = Catalog.updateArticle(next, n, Listing(type = "jeans", brand = if (i < 2) "Levi's" else "Lee"), "l1", null)
        }
        s = Catalog.setUploaded(s, 0, "p", true)
        val now = 100 * day
        s = Ledger.sell(s, 0, "p", 3000, null, 20 * day)
        s = Ledger.sell(s, 1, "c1", 2000, null, 40 * day)
        val k = Stats.kpis(s, now, ZoneOffset.UTC)
        assertEquals(2, k.inStock)
        assertEquals(2000, k.stockCost)
        assertEquals(2, k.sold)
        assertEquals(5000, k.revenue)
        assertEquals(3000, k.profit)
        assertEquals(150, k.roi)
        assertEquals(50, k.sellThrough)
        assertEquals(30, k.medianDays)
        assertEquals(2, k.aged60)
        assertEquals(listOf("Levi's" to 2), k.brands)
        assertEquals(listOf("shiorawear", "Poco"), k.accounts.map { it.label })
        assertEquals(1, k.accounts[1].sold)
        assertEquals(6, k.months.size)
        assertEquals(2, k.notUploaded)
    }

    @Test
    fun `empty catalogue has no ratios`() {
        val k = Stats.kpis(ParentState(self = samsung), 0, ZoneOffset.UTC)
        assertNull(k.sellThrough)
        assertNull(k.roi)
        assertNull(k.medianDays)
    }
}

class AccountsTest {
    private val samsung = Device("p", "Samsung", "SH")
    private val poco = Device("c1", "Poco", "SA", "s1", style = 1)

    @Test
    fun `chosen accounts drive what is left to upload`() {
        var (s, n) = Catalog.create(ParentState(self = samsung, children = listOf(poco)), 0)
        assertTrue(Catalog.article(s, n)!!.toUpload, "no account chosen yet")
        s = Catalog.setAccounts(s, n, setOf("c1", "ghost"))
        assertEquals(setOf("c1"), Catalog.article(s, n)!!.targets)
        s = Catalog.setUploaded(s, n, "c1", true)
        kotlin.test.assertFalse(Catalog.article(s, n)!!.toUpload)
        s = Catalog.setUploaded(s, n, "p", true)
        assertEquals(setOf("p", "c1"), Catalog.article(s, n)!!.targets, "uploading somewhere adds that account")
        s = Catalog.setAccounts(s, n, setOf("p"))
        assertEquals(setOf("p"), Catalog.article(s, n)!!.uploaded)
        val old = Article(9, 0, delivered = mapOf("c1" to 3))
        assertEquals(setOf("c1"), old.targets, "older articles keep where their photos went")
    }
}
