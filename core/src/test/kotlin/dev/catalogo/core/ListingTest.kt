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
        measures = mapOf("length" to "98", "waist" to "36 cm", "rise" to "25"),
    )
    private val hoodie = Listing(type = "hoodie", brand = "Nike", keywords = listOf("vintage", "y2k", "streetwear", "oversized"), size = "M")
    private val knit = Listing(type = "sweater", brand = "Ralph Lauren", keywords = listOf("oldmoney", "classic", "cableknit"), size = "L")

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
        assertEquals("Ralph Lauren Old Money Classic Sweater Cable Knit", ListingWriter.title(knit, 0))
        assertEquals("Timeless Quiet Luxury Ralph Lauren Knit Jumper Cable Knitted", ListingWriter.title(knit, 1))
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
            📏 Medidas: largo total 98 cm · cintura 36 cm · tiro 25 cm
            ✨ Estado: Muy buen estado.
            📦 Envío en 24h

            📝 Medidas tomadas en plano, aproximadas. Tómalas como referencia junto a la talla.
            🛍️ Descubre más prendas similares en mi perfil

            #vintage #branded #y2k #vintagefashion #ootd #vintedfrance #aesthetic #2000s #90s #stylefinds #retrofashion #SH0054
            """.trimIndent(),
            d0,
        )
        val d1 = ListingWriter.description(jeans, 1, "", "SA0054")
        assertTrue(d1.contains("📐 Largo total 98 · Cintura 36 · Tiro 25 (cm)"), d1)
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
    fun `missing values remove their line, measures fall back to photos`() {
        val d = ListingWriter.description(Listing(type = "tshirt"), 0, "", "SH0002")
        assertFalse(d.contains("Talla"), d)
        assertTrue(d.contains("📏 Medidas en las fotos"), d)
        assertTrue(ListingWriter.description(Listing(), 1, "", "SA0002").contains("📐 Medidas: ver fotos"))
    }

    @Test
    fun `measures follow the garment group`() {
        val top = Listing(type = "sweater", measures = mapOf("length" to "74", "chest" to "58", "shoulders" to "51", "sleeve" to "52", "waist" to "40"))
        val d = ListingWriter.description(top, 0, "", "SH0003")
        assertTrue(d.contains("Medidas: largo 74 cm · pecho 58 cm · hombros 51 cm · manga 52 cm"), d)
        assertFalse(d.contains("cintura"), "measures from another group are ignored")
    }

    @Test
    fun `custom template keeps its own wording`() {
        val d = ListingWriter.description(jeans, 0, "Talla {talla}\nSKU {sku}\nTalla otra vez {nada}", "SH9")
        assertEquals("Talla W31 L32\nSKU SH9\nTalla otra vez {nada}", d)
    }
}
