package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleGuardTest {
    private val accounts = listOf(Ai.Account("p", "SH", 0), Ai.Account("c1", "SA", 1))

    private fun facts(
        type: String?, materials: List<String> = emptyList(), colors: List<String> = emptyList(),
        brand: String = "", model: String = "", keywords: List<String> = emptyList(), notes: String = "",
    ) = TitleGuard.facts(Listing(type = type.orEmpty(), brand = brand, model = model, keywords = keywords, aiNotes = notes), type, materials, colors)

    @Test
    fun `a wool and nylon jumper is never cashmere (the real case of 25 Nov)`() {
        val f = facts("sweater", listOf("Lana", "Nailon"), listOf("Gris"), brand = "Jeckerson")
        assertEquals("Jeckerson Mockneck Jumper Retro Knit Heather",
            TitleGuard.fix("Jeckerson Mockneck Cashmere Jumper Retro Knit Heather", f))
        // wool itself is true here, and so is nylon
        assertEquals("Jeckerson Turtleneck Wool Sweater Vintage 90s Classic Knit Grey",
            TitleGuard.fix("Jeckerson Turtleneck Wool Sweater Vintage 90s Classic Knit Grey", f))
    }

    @Test
    fun `a fabric word is kept when the material is real, the seller wrote it or the user chose it`() {
        assertEquals("Cashmere Sweater Grey", TitleGuard.fix("Cashmere Sweater Grey", facts("sweater", listOf("Cachemira"), listOf("Gris"))))
        assertEquals("Merino Wool Sweater", TitleGuard.fix("Merino Wool Sweater", facts("sweater", listOf("Merino"))))
        assertEquals("Wool Sweater", TitleGuard.fix("Wool Sweater", facts("sweater", notes = "Es 100% lana")))
        assertEquals("Leather Jacket Black", TitleGuard.fix("Leather Jacket Black", facts("leatherjacket", colors = listOf("Negro"))), "the type says so")
        assertEquals("Levis 501 Denim Jeans", TitleGuard.fix("Levis 501 Denim Jeans", facts("jeans")), "denim is the garment of jeans")
        assertEquals("Woolrich Sweater", TitleGuard.fix("Woolrich Sweater", facts("sweater", brand = "Woolrich")))
        assertEquals("Cable Knit Wool Sweater", TitleGuard.fix("Cable Knit Wool Sweater", facts("sweater", keywords = listOf("wool"))), "the user picked «lana»")
        assertEquals("Silk Blouse", TitleGuard.fix("Silk Blouse", facts("top", listOf("Seda"))))
        assertEquals("Blouse", TitleGuard.fix("Silk Blouse", facts("top", listOf("Poliéster"))))
        assertEquals("Sweater", TitleGuard.fix("Cotton Linen Sweater", facts("sweater")), "no materials known: no fabric words")
    }

    @Test
    fun `colours must be the garment's, with their shades`() {
        val grey = facts("sweater", colors = listOf("Gris"))
        assertEquals("Jumper Grey", TitleGuard.fix("Blue Jumper Grey", grey))
        assertEquals("Jumper Grey", TitleGuard.fix("Light Blue Jumper Grey", grey), "the shade goes with its colour")
        assertEquals("Jumper Charcoal Heather", TitleGuard.fix("Jumper Charcoal Heather", grey))
        assertEquals("Jeans Navy", TitleGuard.fix("Jeans Navy", facts("jeans", colors = listOf("Azul marino"))))
        assertEquals("Jeans Dark Blue", TitleGuard.fix("Jeans Dark Blue", facts("jeans", colors = listOf("Azul"))))
        assertEquals("Levis 501 Red Tab Jeans Blue", TitleGuard.fix("Levis 501 Red Tab Jeans Blue", facts("jeans", colors = listOf("Azul"), brand = "Levi's")),
            "a red tab is a detail, not the colour")
        assertEquals("Red Wing Boots", TitleGuard.fix("Red Wing Boots", facts("boots", colors = listOf("Marrón"), brand = "Red Wing")))
        assertEquals("Jumper Blue Pink", TitleGuard.fix("Jumper Blue Pink", facts("sweater", colors = listOf("Varios"))))
        assertEquals("Jumper Blue", TitleGuard.fix("Jumper Blue", facts("sweater")), "colours unknown: nothing to check")
    }

    @Test
    fun `a title about another garment is rejected`() {
        val sweater = facts("sweater", colors = listOf("Gris"))
        assertNull(TitleGuard.fix("Jeckerson Puffer Jacket Grey", sweater))
        assertNull(TitleGuard.fix("Wool Hoodie Grey", sweater))
        assertEquals("Wool Pullover Knitwear Grey", TitleGuard.fix("Wool Pullover Knitwear Grey", facts("sweater", listOf("Lana"), listOf("Gris"))), "real synonyms")
        assertEquals("Hooded Sweatshirt Zip", TitleGuard.fix("Hooded Sweatshirt Zip", facts("hoodie")))
        assertNull(TitleGuard.fix("Levis 501 Cargo Pants Blue", facts("jeans")))
        assertEquals("Levis 501 Cargo Pockets Jeans", TitleGuard.fix("Levis 501 Cargo Pockets Jeans", facts("jeans")), "cargo pockets are a detail")
        assertEquals("Levis Dress Shirt", TitleGuard.fix("Levis Dress Shirt", facts("shirt")))
        assertNull(TitleGuard.fix("Cashmere", facts("sweater")), "nothing left")
        assertEquals("Sneakers Puffer Style", TitleGuard.fix("Sneakers Puffer Style", facts("sneakers")), "shoes are not checked")
    }

    @Test
    fun `search words and hashtags that name a fabric need that fabric`() {
        val f = facts("sweater", listOf("Lana", "Nailon"), brand = "Woolrich")
        assertFalse(TitleGuard.keywordTrue("cashmere", f))
        assertTrue(TitleGuard.keywordTrue("wool", f))
        assertTrue(TitleGuard.keywordTrue("knit", f), "construction, not a fabric claim")
        assertTrue(TitleGuard.keywordTrue("vintage", f))
        assertTrue(TitleGuard.keywordTrue("denim", facts("jeans")))
        assertFalse(TitleGuard.keywordTrue("denim", f))

        assertFalse(TitleGuard.hashtagTrue("cashmere", f))
        assertFalse(TitleGuard.hashtagTrue("cashmeresweater", f))
        assertFalse(TitleGuard.hashtagTrue("cachemir", f))
        assertFalse(TitleGuard.hashtagTrue("silk", f))
        assertTrue(TitleGuard.hashtagTrue("wool", f))
        assertTrue(TitleGuard.hashtagTrue("lana", f))
        assertTrue(TitleGuard.hashtagTrue("woolrich", f))
        assertTrue(TitleGuard.hashtagTrue("knitwear", f))
        assertTrue(TitleGuard.hashtagTrue("vaqueros", facts("jeans")))
        assertTrue(TitleGuard.hashtagTrue("denimjeans", facts("jeans")))
    }

    @Test
    fun `Ai parse drops what is not true of the garment`() {
        val answer = """
            {"tipo_prenda":"sweater","palabras":["cashmere","knit","wool"],"sexo":"hombre",
             "colores":["Gris"],"materiales":["Lana","Nailon"],
             "titulos":{"SH":"Jeckerson Mockneck Cashmere Jumper Retro Knit Blue Heather","SA":"Jeckerson Puffer Jacket Grey"},
             "hashtags":["jeckerson","cashmere","jumper","lana","cashmeresweater","knitwear"]}
        """
        val s = Ai.parse(answer, Listing(brand = "Jeckerson"), accounts)
        assertEquals(listOf("knit", "wool"), s.keywords)
        assertEquals(mapOf("p" to "Jeckerson Mockneck Jumper Retro Knit Heather"), s.titles, "the second account's title is about a puffer: dropped")
        assertEquals(listOf("jeckerson", "jumper", "lana", "knitwear"), s.hashtags)
        assertEquals(listOf("Lana", "Nailon"), s.materials)
    }

    @Test
    fun `the prompt asks for true titles, and a re-listing asks for real synonyms`() {
        val prompt = Ai.prompt(Listing(brand = "Jeckerson"), accounts)
        assertTrue("VERDAD (obligatorio)" in prompt)
        assertTrue("Wool ≠ Cashmere" in prompt)
        val relist = Ai.prompt(Listing(brand = "Jeckerson"), accounts, avoid = Relist(mapOf("p" to "Jeckerson Wool Sweater"), listOf("lana")))
        assertTrue("sinónimos REALES" in relist)
        assertTrue("Wool no se cambia por Cashmere" in relist)
    }
}
