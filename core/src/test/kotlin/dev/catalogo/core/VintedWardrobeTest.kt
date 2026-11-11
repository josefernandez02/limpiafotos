package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VintedWardrobeTest {
    private val samsung = Device("p", "Samsung", "SH")

    @Test
    fun `profile links give member id and name`() {
        assertEquals("123456" to "shiorawear", VintedWardrobe.profile("https://www.vinted.es/member/123456-shiorawear"))
        assertEquals("98765" to "", VintedWardrobe.profile("vinted.es/member/98765"))
        assertEquals(null, VintedWardrobe.profile("shiorawear"))
        val s = Catalog.setAccount(ParentState(self = samsung), "p", "", "https://www.vinted.es/member/123456-shiorawear?tab=x")
        assertEquals("shiorawear", s.self.label)
        assertEquals("123456", s.self.vintedId)
        assertFailsWith<CatalogException> { Catalog.setAccount(s, "p", "x", "nope") }
    }

    @Test
    fun `wardrobe items are parsed whatever the price format`() {
        val body = """{"items":[
            {"id":11,"title":"Levi's 501 Jeans","price":{"amount":"29.0","currency_code":"EUR"},"favourite_count":3,"view_count":40},
            {"id":"12","title":"Old","price":"19,5","is_closed":true},
            {"id":13,"title":"Hidden","is_hidden":true}]}"""
        val items = VintedWardrobe.parse(body)
        assertEquals(listOf(2900L, 1950L, null), items.map { it.price })
        assertEquals(listOf("active", "sold", "hidden"), items.map { it.status })
        assertEquals(3, items[0].favourites)
        assertFailsWith<CatalogException> { VintedWardrobe.parse("""{"nope":1}""") }
    }

    @Test
    fun `listings are matched by title, then remembered by id`() {
        var (s, n) = Catalog.create(ParentState(self = samsung), 0)
        s = Catalog.updateArticle(s, n, Listing(type = "jeans", brand = "Levi's", model = "501"), null, null)
        val title = Catalog.listingText(s, n, samsung)!!.title
        val remote = VintedWardrobe.Remote("77", title.uppercase().replace("'", ""), 2500, 10, 2, "active")
        val (after, r) = VintedWardrobe.apply(s, "p", listOf(remote, remote.copy(id = "78", title = "Otra cosa")), 5)
        assertEquals(1, r.found)
        assertEquals(1, r.newlyUploaded)
        assertEquals(listOf("Otra cosa"), r.unmatched)
        val a = Catalog.article(after, n)!!
        assertTrue("p" in a.uploaded)
        assertEquals(2500L, a.vinted["p"]!!.price)
        val (again, r2) = VintedWardrobe.apply(after, "p", listOf(remote.copy(title = "Renamed", status = "sold")), 6)
        assertEquals(listOf(n), r2.sold)
        assertEquals("sold", Catalog.article(again, n)!!.vinted["p"]!!.status)
    }
}
