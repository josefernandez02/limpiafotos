package dev.catalogo.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L

/** The fakes never suspend, so no coroutine library is needed. */
private fun <T> now(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return out!!.getOrThrow()
}

private fun iso(day: Long) = java.time.Instant.ofEpochMilli(day * DAY).toString()

private fun line(id: Int, state: String, day: Long) =
    """{"transaction_id":$id,"title":"T$id","price":{"amount":"10.0"},"status":"$state","date":"${iso(day)}","transaction_user_status":"$state"}"""

private fun list(vararg lines: String, pages: Int = 1) = """{"my_orders":[${lines.joinToString(",")}],"pagination":{"total_pages":$pages}}"""

private fun detail(code: String) = """{"seller_id":222,"sold_at":null,"items":[{"id":1,"title":"x","price":"10","description":"#$code"}]}"""

private class FakeVinted(
    val answers: MutableMap<String, Pair<Int, String>> = mutableMapOf(),
    val deals: MutableMap<String, Pair<Int, String>> = mutableMapOf(),
) : SalesSync.Page {
    val asked = ArrayList<String>()
    val looked = ArrayList<String>()
    var pauses = 0

    init {
        answers["/api/v2/users/current"] = 200 to """{"user":{"id":222}}"""
    }

    fun lines(vararg l: String) = apply { answers[VintedSales.path(1)] = 200 to list(*l) }

    override suspend fun get(path: String): Pair<Int, String> {
        asked += path
        return answers[path] ?: (404 to "")
    }

    override suspend fun deal(order: String): Pair<Int, String> {
        looked += order
        return deals[order] ?: (200 to detail("XX9999"))
    }

    override suspend fun pause() {
        pauses++
    }
}

private class Book(var s: ParentState) : SalesSync.Book {
    override fun state() = s
    override suspend fun change(block: (ParentState) -> ParentState) {
        s = block(s)
    }
}

private fun stock(count: Int = 3): ParentState {
    var s = ParentState(
        self = Device("p", "Samsung", "SH", vintedId = "111"),
        children = listOf(Device("c1", "Poco", "SA", "s", style = 1, vintedId = "222")),
    )
    repeat(count) { s = Catalog.create(s, 10 * DAY).first }
    return s
}

class SalesSyncTest {

    private fun sync(vinted: FakeVinted, book: Book) = now { SalesSync(vinted, book) { 50 * DAY }.run() }

    @Test
    fun `orders are read, matched and counted for the signed in account`() {
        val vinted = FakeVinted().lines(line(1, "waiting", 30), line(2, "completed", 2), line(3, "failed", 29))
        vinted.deals["1"] = 200 to detail("SH0001")
        val book = Book(stock())
        val report = sync(vinted, book)
        assertEquals("Poco", report.account)
        assertEquals(3, report.read)
        assertEquals(1, report.count(VintedSales.Kind.NEW_PENDING))
        assertEquals(1, report.count(VintedSales.Kind.NEW_COMPLETED))
        assertEquals(1, report.count(VintedSales.Kind.NEW_CANCELLED))
        assertEquals(listOf("1"), vinted.looked, "old and cancelled ones are not asked about")
        assertTrue(Catalog.article(book.s, 1)!!.held)
        assertNull(report.stop)
        assertEquals(0, report.left)
    }

    @Test
    fun `every page is read, spaced out`() {
        val vinted = FakeVinted()
        vinted.answers[VintedSales.path(1)] = 200 to list(line(1, "completed", 2), pages = 2)
        vinted.answers[VintedSales.path(2)] = 200 to list(line(2, "completed", 1), pages = 2)
        val book = Book(stock())
        assertEquals(2, sync(vinted, book).read)
        assertEquals(2, book.s.sales.size)
        assertTrue(vinted.pauses >= 1)
    }

    @Test
    fun `the older address is used when the list is not found`() {
        val vinted = FakeVinted()
        vinted.answers[VintedSales.oldPath(1)] = 200 to list(line(1, "completed", 2))
        assertEquals(1, sync(vinted, Book(stock())).read)
    }

    @Test
    fun `sales in progress are looked up first and the limit stops without losing any`() {
        val vinted = FakeVinted().lines(line(1, "completed", 40), line(2, "waiting", 20), line(3, "waiting", 30), line(4, "completed", 45))
        vinted.deals["4"] = 429 to ""
        val book = Book(stock())
        val report = sync(vinted, book)
        assertEquals(listOf("3", "2", "4"), vinted.looked)
        assertEquals(SalesSync.Stop.LIMIT, report.stop)
        assertEquals(2, report.left)
        assertEquals(listOf("2", "3"), book.s.sales.map { it.order }.sorted(), "what was not looked up stays for next time")

        vinted.deals.remove("4")
        vinted.looked.clear()
        val second = sync(vinted, book)
        assertEquals(listOf("4", "1"), vinted.looked)
        assertEquals(0, second.left)
        assertEquals(4, book.s.sales.size)
    }

    @Test
    fun `a lookup that fails is not recorded and three in a row stop`() {
        val vinted = FakeVinted().lines(line(1, "waiting", 40), line(2, "waiting", 39), line(3, "waiting", 38), line(4, "waiting", 37))
        vinted.deals["1"] = 500 to ""
        vinted.deals["2"] = 200 to "<html>"
        vinted.deals["3"] = 0 to ""
        val book = Book(stock())
        val report = sync(vinted, book)
        assertEquals(SalesSync.Stop.ERRORS, report.stop)
        assertEquals(3, report.failed)
        assertEquals(4, report.left)
        assertTrue(book.s.sales.isEmpty())
    }

    @Test
    fun `known orders are updated even if Vinted then asks to wait`() {
        val vinted = FakeVinted().lines(line(1, "waiting", 30))
        vinted.deals["1"] = 200 to detail("SH0001")
        val book = Book(stock())
        sync(vinted, book)
        vinted.lines(line(1, "completed", 41), line(2, "waiting", 40))
        vinted.deals["2"] = 429 to ""
        val report = sync(vinted, book)
        assertEquals(1, report.count(VintedSales.Kind.COMPLETED))
        assertNull(Catalog.article(book.s, 1))
    }

    @Test
    fun `without a session nothing changes`() {
        val vinted = FakeVinted()
        vinted.answers["/api/v2/users/current"] = 401 to ""
        val book = Book(stock())
        assertFailsWith<CatalogException> { sync(vinted, book) }
        assertTrue(book.s.sales.isEmpty())
    }
}
