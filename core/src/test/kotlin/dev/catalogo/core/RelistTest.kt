package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelistTest {

    private val samsung = Device("p", "Samsung", "SH", account = "shiorawear")
    private val poco = Device("c1", "Poco", "SA", account = "otra")
    private val levis = Listing(type = "jeans", brand = "Levi's", model = "501", size = "W32 L34", gender = Gender.MEN, notes = "Tiene un roto")
    private val oldTags = listOf("levis", "levis501", "jeans", "vaqueros", "straightjeans", "vaquerosrectos", "90s", "vintage", "levisvintage", "denim")

    /** A published article: AI data, price, cost, draft on the PC, uploaded and seen in the wardrobe. */
    private fun published(): Pair<ParentState, Int> {
        val (s, n) = Catalog.create(ParentState(self = samsung, children = listOf(poco)), 1_700_000_000_000)
        val a = Catalog.article(s, n)!!.copy(
            listing = levis, price = 1500, cost = 400, accounts = setOf("p", "c1"),
            ai = AiResult(Ai.basis(levis), "Hombre › Ropa › Vaqueros › Vaqueros rectos", listOf("Azul"),
                mapOf("p" to "Levi's 501 Jeans Vintage Straight Fit Blue", "c1" to "Vintage 90s Levis 501 Straight Leg Jeans Blue"),
                hashtags = oldTags),
            photos = listOf("content://foto/1"),
            desk = Desk("SH0001-x", "publicado", url = "https://vinted/old"),
            uploaded = setOf("p", "c1"),
            vinted = mapOf("p" to VintedItem("9", status = "active"), "c1" to VintedItem("10")),
        )
        return s.copy(articles = s.articles.map { if (it.number == n) a else it }) to n
    }

    @Test
    fun `relisting keeps what the user filled in and drops what the old publication left`() {
        val (s, n) = published()
        val a = Catalog.article(Catalog.relist(s, n), n)!!
        assertEquals(levis, a.listing)
        assertEquals(1500L, a.price)
        assertEquals(400L, a.cost)
        assertEquals(setOf("p", "c1"), a.accounts)
        assertNull(a.ai, "a new title and hashtags have to be written")
        assertNull(a.desk)
        assertTrue(a.photos.isEmpty())
        assertEquals(setOf("c1"), a.uploaded, "only this phone's account goes back to «por subir»")
        assertEquals(setOf("c1"), a.vinted.keys)
        assertEquals(Stage.TO_UPLOAD, Catalog.stage(Catalog.relist(s, n), a, samsung))
        assertEquals(1, a.round)
    }

    @Test
    fun `it remembers the titles and hashtags it must not repeat`() {
        val (s, n) = published()
        val r = Catalog.article(Catalog.relist(s, n), n)!!.relist!!
        assertEquals("Levi's 501 Jeans Vintage Straight Fit Blue", r.titles["p"])
        assertEquals("Vintage 90s Levis 501 Straight Leg Jeans Blue", r.titles["c1"])
        assertEquals(oldTags, r.hashtags)
    }

    @Test
    fun `an article that never had AI data has nothing to avoid, and is still blocked until it gets some`() {
        val (s0, n) = published()
        val s = s0.copy(articles = s0.articles.map { it.copy(ai = null) })
        val a = Catalog.article(Catalog.relist(s, n), n)!!
        assertEquals(Relist(), a.relist)
        assertEquals(emptyList(), Relisting.repeated(a.relist!!, emptyMap(), emptyList()))
        assertTrue(Draft.build(s, a, samsung).problems.isNotEmpty())
    }

    @Test
    fun `listing again before the new texts exist keeps the first ones to avoid`() {
        val (s, n) = published()
        val once = Catalog.relist(s, n)
        val twice = Catalog.relist(once, n)
        assertEquals(Catalog.article(once, n)!!.relist, Catalog.article(twice, n)!!.relist)
        assertEquals(2, Catalog.article(twice, n)!!.round)
    }

    @Test
    fun `the PC sees a re-listed article as a new one`() {
        val (s, n) = published()
        val before = Catalog.article(s, n)!!
        val after = Catalog.article(Catalog.relist(s, n), n)!!
        assertNotEquals(Draft.ref(samsung, before, 4), Draft.ref(samsung, after, 4))
        assertEquals("SH" + n.toString().padStart(4, '0') + "-" + before.createdAt.toString(36), Draft.ref(samsung, before, 4), "first listings keep their code")
    }

    @Test
    fun `nothing is sent while the title and hashtags have not been renewed`() {
        val (s, n) = published()
        val a = Catalog.article(Catalog.relist(s, n), n)!!
        // even with AI data in place (the same as before), the draft is blocked until the re-listing is settled
        val withAi = a.copy(ai = AiResult(Ai.basis(levis), "Hombre › Ropa › Vaqueros › Vaqueros rectos", listOf("Azul"), mapOf("p" to "x Jeans"), hashtags = oldTags))
        assertTrue(Draft.build(s, withAi, samsung).problems.any { "resubir" in it.lowercase() })
        val done = Catalog.article(Catalog.finishRelist(s.copy(articles = s.articles.map { if (it.number == n) withAi else it }), n), n)!!
        assertNull(done.relist)
        assertFalse(Draft.build(s, done, samsung).problems.any { "resubir" in it.lowercase() })
    }

    @Test
    fun `a sold or deleted article is not re-listed`() {
        val (s, n) = published()
        val gone = Catalog.delete(s, n)
        assertEquals(gone, Catalog.relist(gone, n))
        assertEquals(s, Catalog.relist(s, 99))
    }

    @Test
    fun `the same title or hashtags are refused, other words are accepted`() {
        val avoid = Relist(mapOf("p" to "Levi's 501 Jeans Vintage Straight Fit Blue"), oldTags)
        val sameTags = oldTags
        assertEquals(listOf("título", "hashtags"), Relisting.repeated(avoid, mapOf("p" to "Levi's 501 Jeans Vintage Straight Fit Blue"), sameTags))
        // only reordered: the same words
        assertTrue("título" in Relisting.repeated(avoid, mapOf("p" to "Vintage Levi's 501 Straight Fit Jeans Blue"), sameTags))
        // a different case or accents is not a change either
        assertTrue("título" in Relisting.repeated(avoid, mapOf("p" to "LEVI'S 501 JEANS VINTAGE STRAIGHT FIT BLUE"), sameTags))
        // one word changed is still too little; two new words are enough
        assertTrue("título" in Relisting.repeated(avoid, mapOf("p" to "Levi's 501 Jeans Vintage Straight Fit Navy"), sameTags))
        val newTitle = "Levi's 501 Denim Pants Vintage 90s Straight Fit Blue"
        assertFalse("título" in Relisting.repeated(avoid, mapOf("p" to newTitle), sameTags))
        // hashtags: two new ones are not enough, three are
        val two = oldTags.dropLast(2) + listOf("denimjeans", "levis90s")
        val three = oldTags.dropLast(3) + listOf("denimjeans", "levis90s", "pantalonesvaqueros")
        assertTrue("hashtags" in Relisting.repeated(avoid, mapOf("p" to newTitle), two))
        assertEquals(emptyList(), Relisting.repeated(avoid, mapOf("p" to newTitle), three))
        assertEquals(emptyList(), Relisting.repeated(avoid, mapOf("p" to newTitle), three.map { it.uppercase() }.filter { it.lowercase() !in oldTags } + oldTags.take(7)))
    }

    @Test
    fun `an account the AI wrote no new title for counts as repeating`() {
        val avoid = Relist(mapOf("p" to "A B C D", "c1" to "E F G H"), emptyList())
        assertEquals(listOf("título"), Relisting.repeated(avoid, mapOf("p" to "A B X Y"), listOf("x")))
        assertEquals(emptyList(), Relisting.repeated(avoid, mapOf("p" to "A B X Y", "c1" to "E F Z W"), emptyList()))
    }

    @Test
    fun `the prompt asks for other words only when re-listing`() {
        val accounts = listOf(Ai.Account("p", "SH", 0), Ai.Account("c1", "SA", 1))
        val first = Ai.prompt(levis, accounts)
        assertFalse("REPUBLICACION" in first)
        val avoid = Relist(mapOf("p" to "Levi's 501 Jeans Vintage Straight Fit Blue"), oldTags)
        val again = Ai.prompt(levis, accounts, avoid = avoid)
        assertTrue("REPUBLICACION" in again)
        assertTrue("SH: Levi's 501 Jeans Vintage Straight Fit Blue" in again)
        assertTrue("levisvintage" in again)
        assertTrue("DATOS_DEL_USUARIO" in again && again.indexOf("REPUBLICACION") < again.lastIndexOf("DATOS_DEL_USUARIO"))
        assertEquals(first.length < again.length, true)
    }

    @Test
    fun `an edit fixes the title and hashtags that still repeat and leaves the rest alone`() {
        val accounts = listOf(Ai.Account("p", "SH", 0), Ai.Account("c1", "SA", 1))
        val oldTags = listOf("levis", "levis511", "jeans", "vaqueros", "slimjeans", "y2k", "vintage", "denim", "levisvintage", "blackjeans")
        val avoid = Relist(mapOf("p" to "Levi's 511 Slim Fit Jeans Vintage Y2K Classic Black", "c1" to "Vintage Y2K Levis 511 Slim Jeans Denim Black"), oldTags)
        val current = AiSuggestion(
            null, emptyList(), null, null, emptyList(), null,
            titles = mapOf("p" to "Levi's 511 Slim Fit Jeans Vintage Y2K Classic Black", "c1" to "Vintage Y2K Levis 511 Slim Leg Denim Trousers Black"),
            findings = emptyList(), hashtags = oldTags + "pantalonesnegros",
        )
        assertEquals(listOf("p"), RelistFix.stale(avoid, current.titles))
        val edit = RelistFix.parse(
            """{"titulos":{"SH":"Levi's 511 Slim Leg Denim Original Retro Y2K Washed Black","SA":"Igual"},"hashtags":["pantalonesslim","levis511slim","blackdenim","jeans"]}""", accounts)
        val fixed = RelistFix.merge(avoid, current, edit, accounts)
        assertTrue(Relisting.titleChanged(avoid.titles["p"]!!, fixed.titles["p"]!!))
        assertEquals(current.titles["c1"], fixed.titles["c1"])
        assertTrue(Relisting.hashtagsChanged(avoid.hashtags, fixed.hashtags), fixed.hashtags.toString())
        assertTrue(fixed.hashtags.size <= RelistFix.MAX_TAGS)
        assertEquals(emptyList(), Relisting.repeated(avoid, fixed.titles, fixed.hashtags))
        val untouched = RelistFix.merge(avoid, current, RelistFix.parse("no es json", accounts), accounts)
        assertEquals(current.titles, untouched.titles)
        assertEquals(current.hashtags, untouched.hashtags)
    }
}
