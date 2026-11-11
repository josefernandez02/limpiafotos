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
            if (k.kind != KeywordKind.EPOCH) assertNotEquals(k.en[0], k.en[1], "keyword ${k.id} must differ between accounts")
        }
        assertEquals(Garments.types.size, Garments.types.map { it.id }.toSet().size)
        assertEquals(Garments.keywords.size, Garments.keywords.map { it.id }.toSet().size)
    }

    @Test
    fun `titles follow brand, model, garment, vintage and era, never size`() {
        val t0 = ListingWriter.title(jeans, 0)
        val t1 = ListingWriter.title(jeans, 1)
        assertEquals("Levi's 501 Jeans Vintage 90s Straight Fit Faded", t0)
        assertEquals("Vintage 1990s Levis 501 Straight Leg Jeans Sun Faded", t1)
        for (t in listOf(t0, t1)) assertFalse("W31" in t)
        assertEquals("Nike Hoodie Vintage Y2K Oversize Fit Streetwear", ListingWriter.title(hoodie, 0))
        assertEquals("Ralph Lauren Sweater Old Money Preppy Cable Knit", ListingWriter.title(knit, 0))
    }

    @Test
    fun `titles of both accounts never match`() {
        for (l in listOf(jeans, hoodie, knit)) {
            val a = ListingWriter.title(l, 0)
            val b = ListingWriter.title(l, 1)
            assertNotEquals(a, b)
            assertTrue(a.length <= ListingWriter.TITLE_MAX && b.length <= ListingWriter.TITLE_MAX)
        }
    }

    @Test
    fun `long titles drop details before style and never drop brand or type`() {
        val many = jeans.copy(keywords = Garments.keywords.map { it.id })
        for (s in 0 until Garments.STYLES) {
            val t = ListingWriter.title(many, s)
            assertTrue(t.length <= ListingWriter.TITLE_MAX, t)
            assertTrue(t.contains(if (s == 0) "Levi's" else "Levis"), t)
            assertTrue(t.contains("Jeans"), t)
        }
    }

    @Test
    fun `descriptions follow each account template`() {
        val l = jeans.copy(flat = Measures(waist = "42", length = "104"))
        val d0 = ListingWriter.description(l, 0, "", "SH0054", listOf("levis", "levis501", "jeans", "vaqueros"))
        assertEquals(
            """
            🏷️ Talla: W31 L32
            📏 Medidas en plano: Cintura 42 cm · Largo 104 cm
            ✨ Estado: Muy buen estado
            🚚 Envío: 24 horas

            🛍️ ¡Echa un vistazo a mi perfil, tengo más prendas de este estilo que te pueden gustar!

            #levis #levis501 #jeans #vaqueros #SH0054
            """.trimIndent(),
            d0,
        )
        val d1 = ListingWriter.description(l, 1, "", "SA0054")
        assertTrue(d1.startsWith("🏷️ Talla W31 L32\n📏 Cintura 42 cm · Largo 104 cm (en plano)\n✅ En muy buen estado\n🚚 Lo envío en 24h"), d1)
        assertTrue(d1.endsWith("#SA0054"))
    }

    @Test
    fun `hashtags without ai come from the listing and the sku goes last`() {
        assertEquals(
            listOf("levis", "levis501", "jeans", "vaqueros", "vintage", "levisvintage", "90s", "90sfashion", "straightleg", "faded"),
            ListingWriter.hashtags(jeans),
        )
        assertEquals(listOf("y2k", "2000s"), ListingWriter.hashtags(Listing(keywords = listOf("y2k"))).filter { it.startsWith("y2") || it.startsWith("20") })
        assertTrue(ListingWriter.hashtags(jeans.copy(brand = "Levi's", noBrand = true)).none { it.startsWith("levis") })
        assertEquals(listOf("cafe", "levis501"), ListingWriter.clean(listOf("#Café", "Levi's 501", "x", "cafe")))
    }

    @Test
    fun `estimated sizes say aprox`() {
        val l = Listing(size = "W33", gender = Gender.MEN, noLabel = true)
        assertEquals("W33 aprox. (equivale a una L)", ListingWriter.size(l, 0))
        assertEquals("L aprox.", ListingWriter.size(Listing(size = "L", noLabel = true), 1))
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
    fun `missing values remove their line`() {
        val d = ListingWriter.description(Listing(type = "tshirt"), 0, "", "SH0002")
        assertFalse(d.contains("Talla"), d)
        for (s in 0 until Garments.STYLES) assertFalse(ListingWriter.description(jeans, s, "", "SH0003").contains("📏"))
    }

    @Test
    fun `templates saved by older versions are brought up to date`() {
        val old = """
            👕 Talla: {talla}
            ✨ Estado: {estado}
            📦 Envío: En 24 horas

            🛍️ Echa un vistazo a mi perfil, tengo más prendas de este estilo que te pueden gustar.
            💬 ¿Tienes alguna duda? Escríbeme y te respondo lo antes posible.
            📝 Medidas tomadas en plano, aproximadas. Tómalas como referencia junto a la talla.

            #vintage #branded #y2k #{sku}
        """.trimIndent()
        assertEquals(ListingWriter.defaultTemplate(0), ListingWriter.upgrade(old, 0))
    }

    @Test
    fun `notes follow the condition as a sentence`() {
        for (note in listOf("Tiene manchas", "tiene manchas.", "  Tiene\n manchas ")) {
            val l = jeans.copy(notes = note)
            assertTrue(ListingWriter.description(l, 0, "", "SH1").contains("✨ Estado: Muy buen estado. Tiene manchas (revisar imágenes)\n"), note)
            assertTrue(ListingWriter.description(l, 1, "", "SA1").contains("✅ En muy buen estado. Tiene manchas (ver fotos)\n"), note)
        }
        assertEquals("Buen estado, con ligeros signos de uso", ListingWriter.state(jeans.copy(condition = Condition.GOOD, notes = " . "), 0))
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
