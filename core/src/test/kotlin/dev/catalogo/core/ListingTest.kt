package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ListingTest {

    private val jeans = Listing(
        type = "jeans", brand = "Levi's", model = "501", size = "W31 L32",
        keywords = listOf("vintage", "90s", "straight", "faded"),
    )
    private val hoodie = Listing(type = "hoodie", brand = "Nike", keywords = listOf("vintage", "y2k", "streetwear", "oversized"), size = "M")
    private val knit = Listing(type = "sweater", brand = "Ralph Lauren", keywords = listOf("oldmoney", "preppy", "cableknit"), size = "L")

    @Test
    fun `every type and keyword has a distinct wording per style`() {
        for (t in Garments.types) assertEquals(Garments.STYLES, t.en.size, t.id)
        for (k in Garments.keywords) {
            assertEquals(Garments.STYLES, k.en.size, k.id)
            assertNotEquals(k.en[0], k.en[1], "keyword ${k.id} must differ between accounts")
        }
        assertEquals(Garments.types.size, Garments.types.map { it.id }.toSet().size)
        assertEquals(Garments.keywords.size, Garments.keywords.map { it.id }.toSet().size)
    }

    @Test
    fun `titles carry brand, type and every keyword, never size or colour`() {
        val t0 = ListingWriter.title(jeans, 0)
        val t1 = ListingWriter.title(jeans, 1)
        assertEquals("Levi's 501 Vintage 90s Straight Leg Jeans Faded", t0)
        assertEquals("1990s Retro Levis 501 Straight Fit Denim Pants Sun Faded", t1)
        for (t in listOf(t0, t1)) assertFalse("W31" in t)
    }

    @Test
    fun `titles of both accounts never match in words or order`() {
        for (l in listOf(jeans, hoodie, knit)) {
            val a = ListingWriter.title(l, 0)
            val b = ListingWriter.title(l, 1)
            assertNotEquals(a, b)
            assertNotEquals(a.lowercase().split(" ").toSet(), b.lowercase().split(" ").toSet(), "same words: $a / $b")
            assertTrue(a.length <= ListingWriter.TITLE_MAX && b.length <= ListingWriter.TITLE_MAX)
        }
        assertEquals("Ralph Lauren Old Money Preppy Cable Knit Sweater", ListingWriter.title(knit, 0))
        assertEquals("Ivy Style Quiet Luxury Ralph Lauren Cable Knitted Jumper", ListingWriter.title(knit, 1))
        assertEquals("Nike Vintage Streetwear Y2K Oversized Hoodie", ListingWriter.title(hoodie, 0))
        assertEquals("Early 2000s Street Style Retro Nike Oversize Fit Hooded Sweatshirt", ListingWriter.title(hoodie, 1))
    }

    @Test
    fun `long titles drop details before style and never drop brand or type`() {
        val many = jeans.copy(keywords = Garments.keywords.map { it.id })
        for (s in 0 until Garments.STYLES) {
            val t = ListingWriter.title(many, s)
            assertTrue(t.length <= ListingWriter.TITLE_MAX, t)
            assertTrue(t.contains(if (s == 0) "Levi's" else "Levis"), t)
            assertTrue(t.contains(Garments.type("jeans")!!.en[s]), t)
        }
    }

    @Test
    fun `descriptions follow each account template`() {
        val d0 = ListingWriter.description(jeans, 0, "", "SH0054")
        assertEquals(
            """
            👕 Talla: W31 L32
            ✨ Estado: Muy buen estado
            📦 Envío en 24 horas

            🛍️ Echa un vistazo a mi perfil, encontrarás más prendas de este estilo.
            💬 Si tienes cualquier duda, escríbeme sin problema.

            #vintage #branded #y2k #vintagefashion #ootd #vintedfrance #aesthetic #2000s #90s #stylefinds #retrofashion #SH0054
            """.trimIndent(),
            d0,
        )
        val d1 = ListingWriter.description(jeans, 1, "", "SA0054")
        assertTrue(d1.startsWith("🏷️ Talla W31 L32\n✅ En muy buen estado, ver fotos"), d1)
        assertTrue(d1.endsWith("#SA0054"))
    }

    @Test
    fun `no sentence is shared between accounts`() {
        for (l in listOf(jeans, hoodie, knit, Listing())) {
            val a = ListingWriter.description(l, 0, "", "SH0001").lines().filter { it.isNotBlank() }.toSet()
            val b = ListingWriter.description(l, 1, "", "SA0001").lines().filter { it.isNotBlank() }.toSet()
            assertEquals(emptySet(), a intersect b)
        }
        for (c in Condition.entries) assertNotEquals(ListingWriter.condition(c, 0), ListingWriter.condition(c, 1))
    }

    @Test
    fun `missing values remove their line and measures are never mentioned`() {
        val d = ListingWriter.description(Listing(type = "tshirt"), 0, "", "SH0002")
        assertFalse(d.contains("Talla"), d)
        for (s in 0 until Garments.STYLES) {
            assertFalse(ListingWriter.description(jeans, s, "", "SH0003").contains("edidas"))
        }
    }

    @Test
    fun `templates saved with measures lose those lines`() {
        val old = """
            👕 Talla: {talla}
            📏 {medidas}
            ✨ Estado: {estado}

            🛍️ Descubre más prendas similares en mi perfil
            📝 Medidas tomadas en plano, aproximadas. Tómalas como referencia junto a la talla.
        """.trimIndent()
        assertEquals(
            "👕 Talla: W31 L32\n✨ Estado: Muy buen estado\n\n🛍️ Descubre más prendas similares en mi perfil",
            ListingWriter.description(jeans, 0, old, "SH1"),
        )
    }

    @Test
    fun `custom template keeps its own wording`() {
        val d = ListingWriter.description(jeans, 0, "Talla {talla}\nSKU {sku}\nTalla otra vez {nada}", "SH9")
        assertEquals("Talla W31 L32\nSKU SH9\nTalla otra vez {nada}", d)
    }

    @Test
    fun `every keyword explains itself and fits at least one garment`() {
        for (k in Garments.keywords) {
            assertTrue(k.help.length in 6..120, k.id)
            assertTrue(Group.entries.any { k.appliesTo(it) }, k.id)
        }
    }

    @Test
    fun `keywords are filtered by garment`() {
        val pants = Garments.keywordsFor(Group.PANTS).map { it.id }
        val tops = Garments.keywordsFor(Group.TOP).map { it.id }
        assertTrue("bootcut" in pants && "bootcut" !in tops)
        assertTrue("cableknit" in tops && "cableknit" !in pants)
        assertTrue("vintage" in pants && "vintage" in tops)
        // Keywords that do not fit the garment never reach the title.
        val odd = Listing(type = "sweater", brand = "X", keywords = listOf("bootcut", "vintage"))
        assertFalse("Bootcut" in ListingWriter.title(odd, 0))
    }

    @Test
    fun `search finds types by spanish, english and synonyms`() {
        fun ids(q: String) = Garments.search(q).map { it.id }
        assertTrue("joggers" in ids("chandal"))
        assertTrue("tracksuit" in ids("chándal"))
        assertTrue("hoodie" in ids("capucha"))
        assertTrue("sweatshirt" in ids("crewneck"))
        assertTrue("puffer" in ids("plumifero"))
        assertEquals(listOf("denimshorts"), ids("shorts vaqueros"))
        assertEquals(emptyList(), ids("   "))
    }
}
