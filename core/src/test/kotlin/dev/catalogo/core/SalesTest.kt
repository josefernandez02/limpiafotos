package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private const val DAY = 86_400_000L

private val samsung = Device("p", "Samsung", "SH", account = "tienda", vintedId = "111")
private val poco = Device("c1", "Poco", "SA", "s", style = 1, vintedId = "222")

/** Three articles (0, 1, 2) created on day 10; 0 is published and on the PC. */
private fun shop(): ParentState {
    var s = ParentState(self = samsung, children = listOf(poco))
    s = Ledger.addLot(s, "L1", "Lote", 3_000, 3, 0)
    repeat(3) {
        val (next, n) = Catalog.create(s, 10 * DAY)
        s = Catalog.updateArticle(next, n, Listing(type = "jeans", brand = "Levi's"), "L1", null)
    }
    s = Catalog.setUploaded(s, 0, "p", true)
    return Catalog.setDesk(s, 0, Desk(ref = "SH0000-x", status = "publicado", url = "https://www.vinted.es/items/900"))
}

private fun ParentState.article(n: Int) = Catalog.article(this, n)
private fun ParentState.only() = sales.single()

class SaleStatesTest {

    @Test
    fun `a registered sale holds the article and its number`() {
        val s = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        val sale = s.only()
        assertEquals(SaleStatus.PENDING, sale.status)
        assertEquals(1_000, sale.cost, "cost taken from the lot")
        val a = assertNotNull(s.article(0))
        assertTrue(a.held)
        assertFalse(a.inStock)
        assertEquals(3, Catalog.nextFree(s), "the number is not given to anyone else")
        assertEquals(Stage.SOLD, Catalog.stage(s, a, s.self))
        assertEquals(a, Sales.article(s, sale))
        assertFailsWith<CatalogException> { Sales.register(s, 0, "m2", "p", 100, null, 21 * DAY) }
    }

    @Test
    fun `completing frees the number and cannot be undone`() {
        val held = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        val s = Sales.complete(held, "m1", 25 * DAY)
        assertNull(s.article(0))
        assertEquals(0, Catalog.nextFree(s))
        assertEquals(SaleStatus.COMPLETED, s.only().status)
        assertEquals(25 * DAY, s.only().closedAt)
        assertEquals(listOf(0), Sales.freed(held, s))
        assertFailsWith<CatalogException> { Sales.cancel(s, "m1", 26 * DAY) }
        assertFailsWith<CatalogException> { Sales.reopen(s, "m1") }
    }

    @Test
    fun `completing frees the number even with a second account`() {
        val s = Sales.complete(Sales.register(shop(), 1, "m1", "c1", 900, null, 20 * DAY), "m1", 21 * DAY)
        assertNull(s.article(1), "nothing waits for another phone to confirm")
    }

    @Test
    fun `cancelling puts everything back`() {
        val before = shop()
        val held = Sales.register(before, 0, "m1", "p", 2_500, null, 20 * DAY)
        val s = Sales.cancel(held, "m1", 22 * DAY)
        val a = assertNotNull(s.article(0))
        assertTrue(a.inStock)
        assertEquals(before.article(0)!!.uploaded, a.uploaded)
        assertEquals(before.article(0)!!.listing, a.listing)
        assertEquals(SaleStatus.CANCELLED, s.only().status)
        assertTrue(s.only().byHand)
        assertEquals(0, Ledger.totals(s.sales).count)
    }

    @Test
    fun `cancelling clears the sold mark of the wardrobe`() {
        var s = shop()
        s = VintedWardrobe.apply(s, "p", listOf(VintedWardrobe.Remote("900", Catalog.listingText(s, 0, samsung)!!.title, 2_500, 1, 1, "sold")), 15 * DAY).first
        s = Sales.cancel(Sales.register(s, 0, "m1", "p", 2_500, null, 20 * DAY), "m1", 21 * DAY)
        assertEquals("active", s.article(0)!!.vinted["p"]!!.status)
    }

    @Test
    fun `the PC leaves a held article alone and takes it back when cancelled`() {
        val held = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        assertEquals("fuera", held.article(0)!!.desk!!.pending)
        assertEquals("publicado", held.article(0)!!.desk!!.status, "what it was is remembered")
        val back = Sales.cancel(held, "m1", 21 * DAY)
        assertEquals("publicado", back.article(0)!!.desk!!.pending)
    }

    @Test
    fun `a job the PC had not finished goes back to por subir`() {
        var s = Catalog.setDesk(shop(), 1, Desk(ref = "SH0001-x", status = "revisar"))
        s = Sales.register(s, 1, "m1", "p", 900, null, 20 * DAY)
        assertNull(Sales.cancel(s, "m1", 21 * DAY).article(1)!!.desk)
        var local = Catalog.setDesk(shop(), 2, Desk(ref = "SH0002-x", status = Desk.PREPARING))
        local = Sales.register(local, 2, "m2", "p", 900, null, 20 * DAY)
        assertEquals("", local.article(2)!!.desk!!.pending, "the PC never heard of it")
        assertEquals(Desk.PREPARING, Sales.cancel(local, "m2", 21 * DAY).article(2)!!.desk!!.status)
    }

    @Test
    fun `an article the PC dropped is a new one for it when it comes back`() {
        for (status in listOf("pendiente", "revisar", Desk.SENDING)) {
            var s = Catalog.setDesk(shop(), 1, Desk(ref = "x", status = status))
            val old = Draft.ref(s.self, s.article(1)!!, s.digits)
            s = Sales.register(s, 1, "m1", "p", 900, null, 20 * DAY)
            assertEquals("fuera", s.article(1)!!.desk!!.pending, status)
            s = Sales.cancel(s, "m1", 21 * DAY)
            assertNull(s.article(1)!!.desk, status)
            assertTrue(Draft.ref(s.self, s.article(1)!!, s.digits) != old, "the PC would ignore the old ref: $status")
        }
    }

    @Test
    fun `a completed sale still tells the PC to drop the article`() {
        val s = Sales.complete(Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY), "m1", 21 * DAY)
        assertEquals(listOf("SH0000-x"), s.retired.map { it.ref })
        assertEquals("fuera", s.retired.single().pending)
        assertEquals(emptyList(), Sales.heard(s, "SH0000-x").retired)
    }

    @Test
    fun `a cancelled sale can be recovered while the article is still there`() {
        var s = Sales.cancel(Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY), "m1", 21 * DAY)
        assertTrue(Sales.canReopen(s, s.only()))
        s = Sales.reopen(s, "m1")
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertFalse(s.only().byHand)
        assertTrue(s.article(0)!!.held)
        s = Sales.cancel(s, "m1", 22 * DAY)
        s = Sales.register(s, 0, "m2", "p", 2_000, null, 23 * DAY)
        assertFalse(Sales.canReopen(s, Sales.sale(s, "m1")!!), "the article is in another sale")
        assertFailsWith<CatalogException> { Sales.reopen(s, "m1") }
    }

    @Test
    fun `an article in a sale cannot be deleted, re-listed or changed by the wardrobe`() {
        val s = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        assertFailsWith<CatalogException> { Catalog.delete(s, 0) }
        assertEquals(s, Catalog.relist(s, 0))
        assertEquals(s, Catalog.setUploaded(s, 0, "p", false))
        assertEquals(s, Catalog.setAccounts(s, 0, setOf("c1")))
        val title = Catalog.listingText(s, 0, samsung)!!.title
        val after = VintedWardrobe.apply(s, "p", listOf(VintedWardrobe.Remote("900", title, 1, 1, 1, "active")), 30 * DAY).first
        assertEquals(s.article(0), after.article(0))
    }

    @Test
    fun `deleting an article frees its number at once`() {
        val s = Catalog.delete(shop(), 1)
        assertNull(s.article(1))
        assertEquals(1, Catalog.nextFree(s))
    }

    @Test
    fun `lot and totals only count money that came in`() {
        var s = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        s = Sales.complete(Sales.register(s, 1, "m2", "p", 1_500, null, 20 * DAY), "m2", 21 * DAY)
        val stats = Ledger.stats(s, s.lots.single())
        assertEquals(1, stats.sold)
        assertEquals(1, stats.pending)
        assertEquals(1, stats.inStock)
        assertEquals(1_500, stats.recovered)
        assertEquals(1, Ledger.totals(s.sales).count)
    }

    @Test
    fun `sales saved by earlier versions are finished ones and get an id`() {
        val old = """{"self":{"id":"p","name":"Samsung","prefix":"SH"},
            "sales":[{"number":3,"deviceId":"p","price":1000,"cost":null,"lotId":null,"soldAt":5},
                     {"number":3,"deviceId":"p","price":1000,"cost":null,"lotId":null,"soldAt":5}]}"""
        val s = Sales.ensureIds(Json { ignoreUnknownKeys = true }.decodeFromString(ParentState.serializer(), old))
        assertTrue(s.sales.all { it.status == SaleStatus.COMPLETED })
        assertEquals(2, s.sales.map { it.id }.filter { it.isNotBlank() }.distinct().size)
        assertEquals(s, Sales.ensureIds(s))
    }
}

class VintedSalesTest {

    private fun order(id: String, status: SaleStatus = SaleStatus.PENDING, price: Long = 1_620, date: Long = 30 * DAY) =
        VintedSales.Order(id, "Vaqueros", price, status, "detalle $status", date, "https://images1.vinted.net/x.jpg")

    private fun deal(vararg items: Pair<String, String?>, seller: String = "111", soldAt: Long? = 29 * DAY) =
        VintedSales.Deal(seller, soldAt, items.map { (id, description) -> VintedSales.Item(id, null, "Vaqueros", description) })

    private fun one(code: String, item: String = "900") = deal(item to "Vaqueros azules\n#levis #denim #$code")

    private fun ParentState.sync(o: VintedSales.Order, d: VintedSales.Deal? = null, now: Long = 40 * DAY) =
        VintedSales.apply(this, "p", o, d, now)

    @Test
    fun `the order list is read with prices, dates and states`() {
        val body = """{"my_orders":[
            {"transaction_id":24000000001,"title":"A","price":{"amount":"16.20","currency_code":"EUR"},"status":"Enviado","date":"2026-11-26T09:36:36Z","photo_url":"https://images1.vinted.net/a.jpg","transaction_user_status":"waiting"},
            {"transaction_id":24000000002,"title":"B","price":{"amount":"25.0"},"status":"Fin","date":"2026-11-14T06:17:36+01:00","photo_url":null,"transaction_user_status":"completed"},
            {"transaction_id":24000000003,"title":"C","price":{"amount":"5"},"status":"Cancelado","date":"2026-11-01T00:00:00Z","transaction_user_status":"failed"},
            {"transaction_id":24000000004,"title":"D","price":{"amount":"7"},"status":"?","date":"2026-11-01T00:00:00Z","transaction_user_status":"needs_action"}],
            "pagination":{"current_page":1,"total_pages":3}}"""
        val page = VintedSales.parseList(body)
        assertEquals(3, page.pages)
        assertEquals(listOf("24000000001", "24000000002", "24000000003", "24000000004"), page.orders.map { it.id })
        assertEquals(listOf(1_620L, 2_500L, 500L, 700L), page.orders.map { it.price })
        assertEquals(listOf(SaleStatus.PENDING, SaleStatus.COMPLETED, SaleStatus.CANCELLED, SaleStatus.PENDING), page.orders.map { it.status },
            "an unknown state is never taken as final")
        assertEquals(java.time.Instant.parse("2026-11-26T09:36:36Z").toEpochMilli(), page.orders[0].date)
        assertEquals("", page.orders[1].photo)
        assertFailsWith<CatalogException> { VintedSales.parseList("<html>") }
        assertFailsWith<CatalogException> { VintedSales.parseList("""{"x":1}""") }
    }

    @Test
    fun `the order detail is read`() {
        val d = VintedSales.parseDeal(
            """{"seller_id":111,"sold_at":"2026-11-26T10:36:39+01:00","items":[{"id":900,"title":"A","price":"16.2","description":"x #SH0000"}]}""",
        )
        assertEquals("111", d.sellerId)
        assertEquals(java.time.Instant.parse("2026-11-26T09:36:39Z").toEpochMilli(), d.soldAt)
        assertEquals(listOf(VintedSales.Item("900", 1_620, "A", "x #SH0000")), d.items)
        assertFailsWith<CatalogException> { VintedSales.parseDeal("""{"items":[]}""") }
    }

    @Test
    fun `the code is the last hashtag of the description`() {
        val s = shop()
        assertEquals(samsung to 12, VintedSales.code("#SH0099 azul #levis #SH0012", s))
        assertEquals(poco to 3, VintedSales.code("texto #sa0003\n", s))
        assertNull(VintedSales.code("#SH0012 #levis", s), "only the last one counts")
        assertNull(VintedSales.code("#SH001", s), "a cut code is not another article")
        assertNull(VintedSales.code("#XX0001", s))
        assertNull(VintedSales.code("sin etiquetas", s))
    }

    @Test
    fun `a sale in progress holds the article found by its code`() {
        val (s, kind) = shop().sync(order("1"), one("SH0000"))
        assertEquals(VintedSales.Kind.NEW_PENDING, kind)
        val sale = s.only()
        assertEquals("1", sale.order)
        assertEquals(0, sale.number)
        assertEquals("SH0000", sale.code)
        assertEquals(1_620, sale.price)
        assertEquals(1_000, sale.cost)
        assertEquals("p", sale.deviceId)
        assertEquals("Levi's", sale.brand)
        assertEquals(29 * DAY, sale.soldAt)
        assertTrue(s.article(0)!!.held)
    }

    @Test
    fun `syncing the same order again changes nothing`() {
        val first = shop().sync(order("1"), one("SH0000")).first
        val (again, kind) = first.sync(order("1"))
        assertEquals(VintedSales.Kind.UNCHANGED, kind)
        assertEquals(first, again)
    }

    @Test
    fun `a finished order closes the sale for good`() {
        val held = shop().sync(order("1"), one("SH0000")).first
        val (s, kind) = held.sync(order("1", SaleStatus.COMPLETED))
        assertEquals(VintedSales.Kind.COMPLETED, kind)
        assertNull(s.article(0))
        assertEquals(SaleStatus.COMPLETED, s.only().status)
        assertEquals(s, s.sync(order("1", SaleStatus.CANCELLED)).first, "no way back from finished")
    }

    @Test
    fun `an order cancelled in Vinted returns the article`() {
        val held = shop().sync(order("1"), one("SH0000")).first
        val (s, kind) = held.sync(order("1", SaleStatus.CANCELLED))
        assertEquals(VintedSales.Kind.CANCELLED, kind)
        assertTrue(s.article(0)!!.inStock)
        assertFalse(s.only().byHand)
    }

    @Test
    fun `a sale cancelled by hand stays cancelled while Vinted still shows it in progress`() {
        val held = shop().sync(order("1"), one("SH0000")).first
        val cancelled = Sales.cancel(held, held.only().id, 35 * DAY)
        val (s, kind) = cancelled.sync(order("1"))
        assertEquals(VintedSales.Kind.UNCHANGED, kind)
        assertEquals(SaleStatus.CANCELLED, s.only().status)
        assertTrue(s.article(0)!!.inStock)
    }

    @Test
    fun `finished in Vinted after being cancelled by hand asks the user`() {
        val held = shop().sync(order("1"), one("SH0000")).first
        val cancelled = Sales.cancel(held, held.only().id, 35 * DAY)
        val (s, kind) = cancelled.sync(order("1", SaleStatus.COMPLETED))
        assertEquals(VintedSales.Kind.CONFLICT, kind)
        assertTrue(s.only().conflict)
        assertNotNull(s.article(0), "nothing is deleted without asking")
        assertEquals(VintedSales.Kind.UNCHANGED, s.sync(order("1", SaleStatus.COMPLETED)).second)
        val done = Sales.accept(s, s.only().id, 41 * DAY)
        assertEquals(SaleStatus.COMPLETED, done.only().status)
        assertFalse(done.only().conflict)
        assertNull(done.article(0))
    }

    @Test
    fun `an order Vinted reopens holds the article again`() {
        var s = shop().sync(order("1"), one("SH0000")).first
        s = s.sync(order("1", SaleStatus.CANCELLED)).first
        s = s.sync(order("1")).first
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertTrue(s.article(0)!!.held)
    }

    @Test
    fun `a reused number is not confused with the article sold before`() {
        // Sold on day 5, and the article that now has that number was created on day 10.
        val (s, _) = shop().sync(order("1", SaleStatus.COMPLETED, date = 8 * DAY), deal("500" to "#SH0000", soldAt = 5 * DAY))
        assertNotNull(s.article(0))
        assertNull(s.only().listedAt)
        assertEquals("SH0000", s.only().code)
    }

    @Test
    fun `without a code the listing id already linked is enough`() {
        var base = shop()
        base = Catalog.setDesk(base, 1, Desk(ref = "r", status = "publicado", itemId = "901"))
        val (s, _) = base.sync(order("1"), deal("901" to null))
        assertEquals(1, s.only().number)
        assertTrue(s.article(1)!!.held)
    }

    @Test
    fun `an unknown article is kept apart until the user assigns it`() {
        var s = shop().sync(order("1"), deal("999" to "sin codigo")).first
        assertEquals(-1, s.only().number)
        assertTrue(s.articles.none { it.held })
        s = Sales.assign(s, s.only().id, 2)
        assertEquals(2, s.only().number)
        assertEquals(1_000, s.only().cost)
        assertTrue(s.article(2)!!.held)
    }

    @Test
    fun `assigning an article to a finished sale frees it`() {
        var s = shop().sync(order("1", SaleStatus.COMPLETED), deal("999" to null)).first
        s = Sales.assign(s, s.only().id, 2)
        assertNull(s.article(2))
        assertEquals(1_000, s.only().cost)
        assertFailsWith<CatalogException> { Sales.assign(s, s.only().id, 1) }
    }

    @Test
    fun `a sale registered by hand is joined to its order, not repeated`() {
        val manual = Sales.register(shop(), 0, "m1", "p", 1_700, null, 28 * DAY)
        val (s, _) = manual.sync(order("1"), one("SH0000"))
        assertEquals("1", s.only().order)
        assertEquals("m1", s.only().id)
        assertEquals(1_620, s.only().price, "Vinted knows the real price")
        assertTrue(s.article(0)!!.held)
    }

    @Test
    fun `a manual sale already finished is recognised too`() {
        val done = Sales.complete(Sales.register(shop(), 0, "m1", "p", 1_620, null, 28 * DAY), "m1", 28 * DAY)
        val (s, _) = done.sync(order("1", SaleStatus.COMPLETED), one("SH0000"))
        assertEquals("1", s.only().order)
    }

    @Test
    fun `a sale saved by an earlier version is recognised instead of repeated`() {
        val old = Sale(0, "p", 1_620, 1_000, "L1", 28 * DAY, id = "a0")
        val gone = Catalog.delete(shop(), 0).copy(sales = listOf(old))
        val (s, _) = gone.sync(order("1", SaleStatus.COMPLETED), one("SH0000"))
        assertEquals("1", s.only().order)
        assertEquals("a0", s.only().id)
    }

    @Test
    fun `an old sale never takes the place of an article now in stock`() {
        // Article 0 was sold, its number reused, and the new one sells for the same money days later.
        val old = Sale(0, "p", 1_620, null, null, 9 * DAY, id = "a0")
        val (s, _) = shop().copy(sales = listOf(old)).sync(order("1"), one("SH0000"))
        assertEquals(2, s.sales.size)
        assertTrue(s.article(0)!!.held)
    }

    @Test
    fun `a bundle becomes one sale per garment sharing the price`() {
        val (s, _) = shop().sync(order("1", price = 3_001), deal("900" to "#SH0000", "901" to "#SH0001"))
        assertEquals(listOf(0, 1), s.sales.map { it.number })
        assertEquals(3_001, s.sales.sumOf { it.price })
        assertEquals(2, s.sales.map { it.id }.distinct().size)
        assertTrue(s.article(0)!!.held && s.article(1)!!.held)
        val done = s.sync(order("1", SaleStatus.COMPLETED, price = 3_001)).first
        assertTrue(done.sales.all { it.status == SaleStatus.COMPLETED })
        assertNull(done.article(1))
    }

    @Test
    fun `the account is the seller of the order`() {
        val (s, _) = shop().sync(order("1"), deal("999" to null, seller = "222"))
        assertEquals("c1", s.only().deviceId)
    }

    @Test
    fun `Vinted is only asked about orders that can move something`() {
        val s = shop()
        assertTrue(VintedSales.needsLookup(s, order("1")))
        assertFalse(VintedSales.needsLookup(s, order("2", SaleStatus.COMPLETED, date = 2 * DAY)), "older than everything in stock")
        assertFalse(VintedSales.needsLookup(s, order("3", SaleStatus.CANCELLED)))
        assertFalse(VintedSales.needsLookup(s.sync(order("1"), one("SH0000")).first, order("1")), "already known")
        assertFalse(VintedSales.needsLookup(ParentState(self = samsung), order("1")))
        val manual = Sales.register(s, 0, "m1", "p", 1_700, null, 28 * DAY)
        assertTrue(VintedSales.needsLookup(manual, order("3", SaleStatus.CANCELLED)))
        assertFalse(VintedSales.needsLookup(manual.copy(articles = emptyList()), order("4", SaleStatus.COMPLETED, date = -5 * DAY)),
            "long before that sale was registered")
    }

    @Test
    fun `history is recorded without asking`() {
        val (s, kind) = shop().sync(order("9", SaleStatus.COMPLETED, date = 2 * DAY))
        assertEquals(VintedSales.Kind.NEW_COMPLETED, kind)
        assertEquals("Vaqueros", s.only().title)
        assertEquals(-1, s.only().number)
        assertEquals(2 * DAY, s.only().soldAt)
    }
}

class SalesStatsTest {

    @Test
    fun `money in, money on the way and profit with known cost`() {
        var s = shop()
        s = Sales.complete(Sales.register(s, 0, "m1", "p", 3_000, null, 20 * DAY), "m1", 22 * DAY)
        s = Sales.register(s, 1, "m2", "p", 2_000, null, 23 * DAY)
        s = VintedSales.apply(s, "p", VintedSales.Order("7", "Antigua", 5_000, SaleStatus.COMPLETED, "", 2 * DAY, ""), null, 30 * DAY).first
        s = Catalog.setPrice(s, 2, 1_800)
        val k = Stats.kpis(s, 30 * DAY, java.time.ZoneOffset.UTC)
        assertEquals(2, k.sold)
        assertEquals(8_000, k.revenue)
        assertEquals(1, k.pending)
        assertEquals(2_000, k.pendingRevenue)
        assertEquals(2_000, k.profit, "the sale with unknown cost is not counted as pure profit")
        assertEquals(1, k.unknownCost)
        assertEquals(1, k.inStock)
        assertEquals(1_800, k.stockValue)
        assertEquals(50, k.sellThrough, "only articles of this app")
    }
}

class SalesPeriodTest {
    private val utc = java.time.ZoneOffset.UTC
    private fun at(date: String) = java.time.Instant.parse("${date}T12:00:00Z").toEpochMilli()
    private fun sale(date: String, price: Long = 1_000, cost: Long? = 400, status: SaleStatus = SaleStatus.COMPLETED, listed: String? = null) =
        Sale(-1, "p", price, cost, null, at(date), listedAt = listed?.let(::at), status = status)

    @Test
    fun `sales are picked by period`() {
        val all = listOf(sale("2025-12-31"), sale("2026-01-05"), sale("2026-10-31"), sale("2026-11-02"))
        val now = at("2026-11-26")
        fun count(p: Stats.Period) = Stats.within(all, p, now, utc).size
        assertEquals(1, count(Stats.Period.MONTH))
        assertEquals(2, count(Stats.Period.LAST_30))
        assertEquals(3, count(Stats.Period.YEAR))
        assertEquals(4, count(Stats.Period.ALL))
    }

    @Test
    fun `a summary keeps money in, money on the way and cancellations apart`() {
        val s = Stats.summary(
            listOf(
                sale("2026-11-10", 2_000, 500, listed = "2026-11-01"),
                sale("2026-11-11", 1_000, null, listed = "2026-11-08"),
                sale("2026-11-12", 700, status = SaleStatus.PENDING),
                sale("2026-11-13", 9_000, status = SaleStatus.CANCELLED),
            ),
        )
        assertEquals(2, s.sold)
        assertEquals(3_000, s.revenue)
        assertEquals(1_500, s.profit)
        assertEquals(1, s.unknownCost)
        assertEquals(1, s.pending)
        assertEquals(700, s.pendingRevenue)
        assertEquals(1, s.cancelled)
        assertEquals(1_500, s.averagePrice)
        assertEquals(9, s.medianDays)
        assertNull(Stats.summary(emptyList()).averagePrice)
    }
}
