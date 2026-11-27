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
    fun `deleting an article the PC has tells the PC to leave it`() {
        for (status in listOf("publicado", "borrador", "pendiente", "enviando", "error")) {
            val s = Catalog.setDesk(shop(), 0, Desk(ref = "SH0000-x", status = status))
            val job = Catalog.delete(s, 0).retired.single()
            assertEquals("SH0000-x" to "fuera", job.ref to job.pending, status)
        }
    }

    @Test
    fun `deleting an article that never left the phone says nothing to the PC`() {
        assertTrue(Catalog.delete(shop(), 1).retired.isEmpty())
        for (status in listOf(Desk.PREPARING, Desk.REVIEW)) {
            val s = Catalog.setDesk(shop(), 0, Desk(ref = "SH0000-x", status = status))
            assertTrue(Catalog.delete(s, 0).retired.isEmpty(), status)
        }
    }


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
    fun `the number can be freed while the money is still on its way`() {
        val held = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        val s = Sales.release(held, "m1")
        assertNull(s.article(0))
        assertEquals(0, Catalog.nextFree(s))
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertEquals("fuera", s.retired.single().pending, "the PC is told to leave it")
        assertEquals(SaleStatus.COMPLETED, Sales.complete(s, "m1", 25 * DAY).only().status)
        val cancelled = Sales.cancel(s, "m1", 22 * DAY)
        assertNull(cancelled.article(0), "the garment does not come back on its own")
        assertTrue(Sales.canReopen(cancelled, cancelled.only()))
        assertEquals(SaleStatus.PENDING, Sales.reopen(cancelled, "m1").only().status)
        assertFailsWith<CatalogException> { Sales.release(s, "m1") }
    }

    @Test
    fun `a hand sale given as paid can go back to waiting for the money`() {
        val paid = Sales.complete(Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY), "m1", 21 * DAY)
        val s = Sales.unpay(paid, "m1")
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertNull(s.only().closedAt)
        assertEquals(SaleStatus.CANCELLED, Sales.cancel(s, "m1", 22 * DAY).only().status)
        val fromVinted = paid.copy(sales = listOf(paid.only().copy(order = "7")))
        assertFailsWith<CatalogException> { Sales.unpay(fromVinted, "m1") }
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
    fun `what the PC read is understood`() {
        val body = """{"modo":"trabajando","esperando":"el programa del PC está detenido","pendientes":3,"pedidos":[
            {"id":"u1","titulo":"A","precio":1620,"estado":"en_curso","detalle":"Enviado","fecha":2000,"items":[{"id":"900","codigo":"SH0000"}]},
            {"id":"u2","titulo":"B","precio":2500,"estado":"completada","detalle":"Pedido finalizado","fecha":1000,"items":[]},
            {"id":"u3","titulo":"C","precio":500,"estado":"cancelada","detalle":"Cancelado","fecha":3000,"items":[]},
            {"id":"u4","titulo":"D","precio":700,"estado":"algo nuevo","detalle":"?","fecha":4000,"items":[{"id":"7","codigo":""}]}]}"""
        val r = VintedSales.parsePc(body)
        assertTrue(r.working)
        assertEquals("el programa del PC está detenido", r.waiting)
        assertEquals(3, r.pending)
        assertEquals(listOf("u2", "u1", "u3", "u4"), r.orders.map { it.first.id }, "oldest first")
        assertEquals(listOf(SaleStatus.COMPLETED, SaleStatus.PENDING, SaleStatus.CANCELLED, SaleStatus.PENDING),
            r.orders.map { it.first.status }, "an unknown state is never taken as final")
        assertEquals(VintedSales.Order("u1", "A", 1_620, SaleStatus.PENDING, "Enviado", 2_000, ""), r.orders[1].first)
        assertNull(r.orders[0].second, "no garment was looked up")
        assertEquals(VintedSales.Deal("", 2_000, listOf(VintedSales.Item("900", null, "", "#SH0000"))), r.orders[1].second)
        assertEquals("", r.orders[3].second!!.items.single().description)
        assertFailsWith<CatalogException> { VintedSales.parsePc("<html>") }
        assertFailsWith<CatalogException> { VintedSales.parsePc("""{"error":"no existe"}""") }
    }

    @Test
    fun `bringing in the same reading twice changes nothing`() {
        val body = """{"modo":"parado","esperando":"","pendientes":0,"pedidos":[
            {"id":"u1","titulo":"A","precio":1620,"estado":"en_curso","detalle":"Enviado","fecha":${29 * DAY},"items":[{"id":"900","codigo":"SH0000"}]}]}"""
        val orders = VintedSales.parsePc(body).orders
        val (once, counts) = VintedSales.applyAll(shop(), "p", orders, 40 * DAY)
        assertEquals(mapOf(VintedSales.Kind.NEW_PENDING to 1), counts)
        assertTrue(once.article(0)!!.held)
        val (twice, again) = VintedSales.applyAll(once, "p", orders, 50 * DAY)
        assertEquals(once, twice)
        assertTrue(again.isEmpty())
    }

    @Test
    fun `what to ask the PC for`() {
        assertEquals(listOf("SH", "SA"), VintedSales.prefixes(shop()))
    }

    @Test
    fun `how the PC is doing comes with its reading`() {
        val r = VintedSales.parsePc("""{"modo":"parado","pedidos":[],"leida":7,"proxima":9}""")
        assertEquals(listOf(7L, 9L), listOf(r.lastRead, r.next))
    }

    @Test
    fun `every order the PC sends is brought in, however old, except one already cancelled`() {
        val orders = listOf(
            order("done", SaleStatus.COMPLETED, date = 2 * DAY) to null,
            order("open", date = 3 * DAY) to null,
            order("gone", SaleStatus.CANCELLED, date = 25 * DAY) to null,
        )
        val (s, counts) = VintedSales.applyAll(shop(), "p", orders, 400 * DAY)
        assertEquals(listOf("done", "open"), s.sales.map { it.order })
        assertEquals(mapOf(VintedSales.Kind.NEW_COMPLETED to 1, VintedSales.Kind.NEW_PENDING to 1), counts)
    }

    /** Article 0 given as sold and paid by hand: it is gone. */
    private fun paidByHand(price: Long = 1_620, base: ParentState = shop()) =
        Sales.complete(Sales.register(base, 0, "m1", "p", price, null, 28 * DAY), "m1", 28 * DAY)

    /** The same, with the listing of article 0 known to the app. */
    private fun listed(item: String = "900") = Catalog.setDesk(shop(), 0, Desk(ref = "r", status = "publicado", itemId = item))

    /** What an earlier version left: a hand sale already joined to the order "1". */
    private fun joinedBefore() = paidByHand().let { it.copy(sales = listOf(it.only().copy(order = "1"))) }

    private fun ParentState.hand() = Sales.sale(this, "m1")!!

    @Test
    fun `sales an earlier version joined and left as paid go back to waiting`() {
        var (s, counts) = VintedSales.applyAll(joinedBefore(), "p", listOf(order("1") to one("SH0000")), 40 * DAY)
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertNull(s.only().closedAt)
        assertTrue(s.only().numberFreed)
        assertEquals(mapOf(VintedSales.Kind.UNPAID to 1), counts)
        s = s.sync(order("1", SaleStatus.COMPLETED), now = 41 * DAY).first
        assertEquals(SaleStatus.COMPLETED, s.only().status)
        assertTrue(s.only().settled)
        assertEquals(s.only().status, s.sync(order("1"), now = 42 * DAY).first.only().status, "finished in Vinted is final")
        assertEquals(SaleStatus.CANCELLED, joinedBefore().sync(order("1", SaleStatus.CANCELLED)).first.only().status)
    }

    @Test
    fun `a sale by hand is one outside Vinted, whatever code and price an order has`() {
        val before = paidByHand(1_620)
        val s = before.sync(order("1"), one("SH0000")).first
        assertEquals(2, s.sales.size)
        assertEquals(before.hand(), s.hand())
        assertTrue(Sales.doubts(s).isEmpty())
        val sum = Stats.summary(s.sales)
        assertEquals(1 to 1, sum.sold to sum.pending)
    }

    @Test
    fun `an order of another listing raises no warning`() {
        val s = paidByHand(1_620, listed("500")).sync(order("1"), one("SH0000")).first
        assertEquals(2, s.sales.size)
        assertTrue(Sales.doubts(s).isEmpty())
    }

    private fun soldTwice() = paidByHand(1_500, listed()).sync(order("1"), one("SH0000")).first

    @Test
    fun `Vinted selling a listing already sold by hand only warns`() {
        val s = soldTwice()
        assertEquals(paidByHand(1_500, listed()).hand(), s.hand())
        assertEquals(listOf("v1" to "m1"), Sales.doubts(s).map { it.first.id to it.second.id })
    }

    @Test
    fun `a hand sale remembers every listing of its garment`() {
        val title = Catalog.listingText(shop(), 0, samsung)!!.title
        val two = VintedWardrobe.apply(listed("950"), "p", listOf(VintedWardrobe.Remote("900", title, 2_500, 1, 1, "active")), 15 * DAY).first
        val sold = paidByHand(1_500, two)
        assertEquals(setOf("900", "950"), sold.hand().listings.toSet())
        for (item in listOf("900", "950")) {
            assertEquals(1, Sales.doubts(sold.sync(order("1"), one("SH0000", item)).first).size, item)
        }
    }

    @Test
    fun `cancelling that order in Vinted leaves the hand sale as it was`() {
        val twice = soldTwice()
        val s = twice.sync(order("1", SaleStatus.CANCELLED)).first
        assertEquals(twice.hand(), s.hand())
        assertTrue(Sales.doubts(s).isEmpty())
        assertNull(s.article(0))
    }

    @Test
    fun `saying it is the same sale joins them with Vinted's data and the hand cost`() {
        val s = Sales.merge(soldTwice(), "v1")
        assertEquals("m1", s.only().id)
        assertEquals("1", s.only().order)
        assertEquals(1_620, s.only().price)
        assertEquals(1_000, s.only().cost)
        assertEquals(0, s.only().number)
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertTrue(s.only().numberFreed)
        assertTrue(Sales.doubts(s).isEmpty())
        assertEquals(1, s.sync(order("1")).first.sales.size)
        assertFailsWith<CatalogException> { Sales.merge(s, "m1") }
    }

    @Test
    fun `profit on the way and still in stock only counts known costs`() {
        var s = Sales.register(shop(), 0, "m1", "p", 2_500, null, 20 * DAY)
        assertEquals(1_500, Stats.summary(s.sales).pendingProfit)
        s = Catalog.updateArticle(s, 1, s.article(1)!!.listing!!, "L1", null)
        s = s.copy(articles = s.articles.map { if (it.number == 1) it.copy(price = 1_800) else it })
        assertEquals(800, Stats.kpis(s, 30 * DAY).stockProfit)
    }

    @Test
    fun `two orders with the same code are told apart by their listing`() {
        // Number 0 was sold as listing 500, reused, and the new garment sold as listing 900.
        val base = Catalog.setDesk(shop(), 0, Desk(ref = "SH0000-y", status = "jugado", itemId = "900"))
        val orders = listOf(
            order("1", date = 9 * DAY) to deal("500" to "#SH0000", soldAt = 9 * DAY + DAY / 2),
            order("2", date = 20 * DAY) to deal("900" to "#SH0000", soldAt = 20 * DAY),
        )
        val (s, _) = VintedSales.applyAll(base, "p", orders, 40 * DAY)
        assertEquals(listOf(null, 10 * DAY), s.sales.map { it.listedAt })
        assertEquals("2", Sales.sale(s, s.article(0)!!.soldIn)!!.order)
    }

    @Test
    fun `an order kept apart takes its article once the listing is known`() {
        var s = shop().sync(order("1"), deal("901" to null)).first
        assertEquals(-1, s.only().number)
        s = Catalog.setDesk(s, 1, Desk(ref = "r", status = "publicado", itemId = "901"))
        s = s.sync(order("1")).first
        assertEquals(1, s.only().number)
        assertTrue(s.article(1)!!.held)
    }

    @Test
    fun `a number reused right after a sale is not taken by the older order`() {
        // The garment that has number 0 now is another listing, made hours after the old one was sold.
        val s = Catalog.setDesk(shop(), 0, Desk(ref = "SH0000-x", status = "publicado", itemId = "900"))
        val (after, _) = s.sync(order("1"), deal("555" to "#SH0000", soldAt = 10 * DAY - DAY / 2))
        assertNull(after.only().listedAt)
        assertTrue(after.article(0)!!.inStock)
    }

    @Test
    fun `a cancelled order never seen before is not a sale`() {
        val (s, counts) = VintedSales.applyAll(shop(), "p", listOf(order("1", SaleStatus.CANCELLED) to null), 40 * DAY)
        assertTrue(s.sales.isEmpty())
        assertTrue(counts.isEmpty())
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

    /** Article 0 sold by hand and still kept, with no listing the app knows. */
    private fun heldByHand() = Sales.register(shop(), 0, "m1", "p", 1_700, null, 28 * DAY)

    @Test
    fun `an order for an article sold by hand does not take it, and warns`() {
        val s = heldByHand().sync(order("1"), one("SH0000")).first
        assertEquals(2, s.sales.size)
        assertEquals(1_700, s.hand().price)
        assertEquals("m1", s.article(0)!!.soldIn)
        assertEquals(listOf("v1" to "m1"), Sales.doubts(s).map { it.first.id to it.second.id })
    }

    @Test
    fun `joining a finished order frees the article the hand sale kept`() {
        val twice = heldByHand().sync(order("1", SaleStatus.COMPLETED), one("SH0000")).first
        val s = Sales.merge(twice, "v1")
        assertEquals(SaleStatus.COMPLETED, s.only().status)
        assertTrue(s.only().settled)
        assertNull(s.article(0))
    }

    @Test
    fun `joining an order in progress keeps the article held`() {
        val s = Sales.merge(heldByHand().sync(order("1"), one("SH0000")).first, "v1")
        assertEquals(SaleStatus.PENDING, s.only().status)
        assertEquals("m1", s.article(0)!!.soldIn)
    }

    @Test
    fun `cancelling the hand sale lets the order take the article`() {
        val twice = heldByHand().sync(order("1"), one("SH0000")).first
        val s = Sales.cancel(twice, "m1", 41 * DAY).sync(order("1")).first
        assertEquals("v1", s.article(0)!!.soldIn)
        assertTrue(Sales.doubts(s).isEmpty())
    }

    @Test
    fun `a sale saved by an earlier version is left alone`() {
        val old = Sale(0, "p", 1_620, 1_000, "L1", 28 * DAY, id = "a0")
        val gone = Catalog.delete(shop(), 0).copy(sales = listOf(old))
        val (s, _) = gone.sync(order("1", SaleStatus.COMPLETED), one("SH0000"))
        assertEquals(old, s.sales.first())
        assertEquals(2, s.sales.size)
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
    fun `a sale without a looked up garment is recorded as it comes`() {
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
