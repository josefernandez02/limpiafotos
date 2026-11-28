package dev.catalogo.core.catalog

import dev.catalogo.core.listing.Listing
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.VintedAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {

    private val samsung = VintedAccount("p", "Samsung", "SH")
    private val poco = VintedAccount("c1", "Poco", "SA")
    private val third = VintedAccount("c2", "Tercero", "SB")

    private fun state(vararg otherAccounts: VintedAccount) = CatalogState(mainAccount = samsung, otherAccounts = otherAccounts.toList())

    private fun CatalogState.create(): Pair<CatalogState, Int> = Catalog.create(this, 0)

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
    fun `uploaded mark is kept per account`() {
        var (s, n) = state(poco).create()
        s = Catalog.setUploaded(s, n, "c1", true)
        s = Catalog.setUploaded(s, n, "nobody", true)
        assertEquals(setOf("c1"), Catalog.article(s, n)!!.uploaded)
        s = Catalog.setUploaded(s, n, "p", true)
        s = Catalog.setUploaded(s, n, "c1", false)
        assertEquals(setOf("p"), Catalog.article(s, n)!!.uploaded)
        s = Catalog.setUploaded(s, n, "c1", true)
        s = Catalog.removeAccount(s, "c1")
        assertEquals(setOf("p"), Catalog.article(s, n)!!.uploaded)
    }

    @Test
    fun `numbers are allocated lowest first`() {
        var s = state(poco)
        val numbers = (1..3).map { s.create().also { r -> s = r.first }.second }
        assertEquals(listOf(0, 1, 2), numbers)
    }

    @Test
    fun `removing an account unblocks its pending releases`() {
        var s = state(poco)
        // as saved by earlier versions: sold, waiting for another phone to confirm
        s = s.copy(articles = listOf(Article(0, 0, released = true, pendingRelease = setOf("c1"))))
        s = Catalog.removeAccount(s, "c1")
        assertEquals(0, Catalog.nextFree(s))
    }

    @Test
    fun `account validation`() {
        val s = state(poco)
        assertFailsWith<CatalogException> { Catalog.addAccount(s, third.copy(prefix = "SA")) }
        assertFailsWith<CatalogException> { Catalog.addAccount(s, third.copy(prefix = "s1")) }
        assertFailsWith<CatalogException> { Catalog.addAccount(s, third.copy(name = "poco")) }
        assertFailsWith<CatalogException> { Catalog.addAccount(s, third.copy(name = " ")) }
        val renamed = Catalog.renameAccount(s, "c1", " Poco F3 ", "sx")
        assertEquals("Poco F3", renamed.otherAccounts.single().name)
        assertEquals("SX", renamed.otherAccounts.single().prefix)
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
        s = Catalog.delete(s, 500)
        assertEquals(500, Catalog.nextFree(s))
    }

    @Test
    fun `phones paired before styles get a distinct style`() {
        var s = state(poco.copy(style = 0), third.copy(style = 0))
        s = Catalog.ensureDistinctStyles(s)
        assertTrue(s.otherAccounts.none { it.style == s.mainAccount.style })
        assertEquals(s, Catalog.ensureDistinctStyles(s), "idempotent")
        s = Catalog.create(s, 0).first
        s = Catalog.updateArticle(s, 0, Listing(type = "jeans", brand = "Levi's", keywords = listOf("vintage", "90s")), null, null)
        val a = Catalog.listingText(s, 0, s.mainAccount)!!
        val b = Catalog.listingText(s, 0, s.otherAccounts.first())!!
        assertTrue(a.title != b.title && a.description != b.description)
    }
}
