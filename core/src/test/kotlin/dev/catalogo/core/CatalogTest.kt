package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {

    private val samsung = Device("p", "Samsung", "SH")
    private val poco = Device("c1", "Poco", "SA", "s1")
    private val third = Device("c2", "Tercero", "SB", "s2")

    private fun state(vararg children: Device) = ParentState(self = samsung, children = children.toList())

    private fun ParentState.create(): Pair<ParentState, Int> = Catalog.create(this, 0)

    @Test
    fun `sku format and parse`() {
        assertEquals("SA0054", Sku.format("SA", 54, 4))
        assertEquals(54, Sku.parse("SA0054", "SA", 4))
        assertNull(Sku.parse("SA054", "SA", 4))
        assertNull(Sku.parse("SH0054", "SA", 4))
        assertNull(Sku.parse("SA00x4", "SA", 4))
        assertEquals(9999, Sku.maxNumber(4))
    }

    @Test
    fun `numbers are allocated lowest first`() {
        var s = state(poco)
        val numbers = (1..3).map { s.create().also { r -> s = r.first }.second }
        assertEquals(listOf(0, 1, 2), numbers)
    }

    @Test
    fun `released number stays taken until every child confirms`() {
        var s = state(poco, third)
        repeat(3) { s = s.create().first }
        s = Catalog.release(s, 1)
        assertEquals(3, Catalog.nextFree(s), "1 is still pending on children")
        assertEquals(listOf(1), Catalog.releasesFor(s, "c1"))
        assertEquals(listOf(1), Catalog.releasesFor(s, "c2"))

        s = Catalog.confirmRelease(s, 1, "c1")
        assertEquals(3, Catalog.nextFree(s))
        assertEquals(emptyList(), Catalog.releasesFor(s, "c1"))

        s = Catalog.confirmRelease(s, 1, "c2")
        assertEquals(1, Catalog.nextFree(s), "free once both confirmed")
        assertNull(Catalog.article(s, 1))
    }

    @Test
    fun `release without children frees immediately`() {
        var s = state()
        s = s.create().first
        s = Catalog.release(s, 0)
        assertEquals(0, Catalog.nextFree(s))
    }

    @Test
    fun `release originated on a child skips that child`() {
        var s = state(poco, third)
        s = s.create().first
        s = Catalog.release(s, 0, confirmedBy = "c1")
        assertEquals(emptyList(), Catalog.releasesFor(s, "c1"))
        assertEquals(listOf(0), Catalog.releasesFor(s, "c2"))
        s = Catalog.confirmRelease(s, 0, "c2")
        assertEquals(0, Catalog.nextFree(s))
    }

    @Test
    fun `child release after parent release just confirms`() {
        var s = state(poco)
        s = s.create().first
        s = Catalog.release(s, 0)
        s = Catalog.release(s, 0, confirmedBy = "c1")
        assertEquals(0, Catalog.nextFree(s))
    }

    @Test
    fun `release of unknown number is ignored`() {
        val s = state(poco)
        assertEquals(s, Catalog.release(s, 42))
        assertEquals(s, Catalog.confirmRelease(s, 42, "c1"))
    }

    @Test
    fun `removing a child unblocks its pending releases`() {
        var s = state(poco)
        s = s.create().first
        s = Catalog.release(s, 0)
        s = Catalog.removeChild(s, "c1")
        assertEquals(0, Catalog.nextFree(s))
    }

    @Test
    fun `delivered counts accumulate and reset on release`() {
        var s = state(poco)
        s = s.create().first
        s = Catalog.markDelivered(s, 0, "c1", 5)
        s = Catalog.markDelivered(s, 0, "c1", 2)
        assertEquals(7, Catalog.article(s, 0)!!.delivered["c1"])
    }

    @Test
    fun `device validation`() {
        val s = state(poco)
        assertFailsWith<CatalogException> { Catalog.addChild(s, third.copy(prefix = "SA")) }
        assertFailsWith<CatalogException> { Catalog.addChild(s, third.copy(prefix = "s1")) }
        assertFailsWith<CatalogException> { Catalog.addChild(s, third.copy(name = "poco")) }
        assertFailsWith<CatalogException> { Catalog.addChild(s, third.copy(name = " ")) }
        val renamed = Catalog.renameDevice(s, "c1", " Poco F3 ", "sx")
        assertEquals("Poco F3", renamed.children.single().name)
        assertEquals("SX", renamed.children.single().prefix)
        assertTrue(renamed.configVersion > s.configVersion, "children learn renames on next sync")
    }

    @Test
    fun `digits must fit existing numbers`() {
        var s = state()
        repeat(3) { s = s.create().first }
        assertEquals(5, Catalog.setDigits(s, 5).digits)
        assertFailsWith<CatalogException> { Catalog.setDigits(s, 2) }
        val big = s.copy(articles = s.articles + Article(1500, 0))
        assertFailsWith<CatalogException> { Catalog.setDigits(big, 3) }
    }

    @Test
    fun `numbers run out only when every number is in use`() {
        var s = state().copy(digits = 3)
        repeat(1000) { s = s.create().first }
        assertNull(Catalog.nextFree(s))
        assertFailsWith<CatalogException> { s.create() }
        s = Catalog.release(s, 500)
        assertEquals(500, Catalog.nextFree(s))
    }
}
