package dev.catalogo.core.listing

import dev.catalogo.core.ai.Ai
import dev.catalogo.core.ai.AiAnswer
import dev.catalogo.core.ai.AiCategories
import dev.catalogo.core.ai.AiPrompt
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.model.Stage
import dev.catalogo.core.model.VintedAccount
import dev.catalogo.core.vinted.Gender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagReadingTest {
    private val jeans = Listing(type = "jeans", brand = "Levi's", model = "505", size = "W38 L38", gender = Gender.MEN)
    private val accounts = listOf(Ai.Account("a", "SH", 0))

    @Test
    fun `composition decides the materials`() {
        assertEquals(listOf("Algodón"), TagReader.materials(listOf("100% ALGODÓN")))
        assertEquals(listOf("Algodón", "Elastano"), TagReader.materials(listOf("98% cotton 2% elastane")))
        assertEquals(listOf("Poliéster", "Algodón"), TagReader.materials(listOf("35% algodón", "65% poliéster")))
        assertEquals(listOf("Cuero sintético"), TagReader.materials(listOf("100% piel sintética")))
        assertEquals(emptyList(), TagReader.materials(listOf("hecho a mano")))
    }

    @Test
    fun `reading answer is cleaned`() {
        val r = TagReader.parse(
            """{"composicion":["100% algodón","sin número"],"hecho_en":"Argentina","talla_etiqueta":" W38  L38 ",
               "marca":null,"modelo":"505","defectos":[],"detalles":["pestaña roja"]}""",
        )
        assertEquals(listOf("100% algodón"), r.composition)
        assertEquals("Argentina", r.origin)
        assertEquals("W38 L38", r.sizeLabel)
        assertNull(r.brand)
        assertEquals("100% algodón", TagReader.compositionText(r))
    }

    @Test
    fun `the label wins over the listing call for materials`() {
        val reading = TagReading(composition = listOf("100% algodón"), origin = "Argentina")
        val answer = """{"hallazgos":[],"tipo_prenda":"jeans","palabras":[],"sexo":"hombre","categoria":null,
            "colores":["Azul"],"materiales":["Vaquero"],"dudas":["materiales"],"marca":null,"modelo":null,"talla":null,
            "titulos":{"SH":"Levi's 505 Jeans Vintage 90s Classic Straight Fit Blue"},"hashtags":["levis"]}"""
        val s = AiAnswer.parse(answer, jeans, accounts, reading)
        assertEquals(listOf("Algodón"), s.materials)
        assertTrue("materiales" !in s.doubts)
        val result = s.result(s.applyTo(jeans), "m")
        assertEquals("100% algodón", result.composition)
    }

    @Test
    fun `the prompt carries the reading and keeps a country out of the title`() {
        val p = AiPrompt.prompt(jeans, accounts, TagReading(composition = listOf("100% algodón")))
        assertTrue("LECTURA_DE_ETIQUETAS" in p && "100% algodón" in p, p)
        assertTrue("en UN solo sitio" in p)
        assertTrue("LECTURA_DE_ETIQUETAS\n{" !in AiPrompt.prompt(jeans, accounts, null))
    }
}

class NotesTest {
    @Test
    fun `the seller's notes settle material and a size without label is not asked`() {
        val jumper = Listing(type = "jumper", brand = "Vintage Dressing", noLabel = true, aiNotes = "Es lana", noModel = true)
        val answer = """{"hallazgos":[],"tipo_prenda":"jumper","palabras":[],"sexo":"hombre","categoria":null,
            "colores":["Azul"],"materiales":["Acrílico"],"dudas":["materiales","talla"],"marca":null,"modelo":"Aran",
            "talla":"L","titulos":{"SH":"Vintage Dressing Wool Jumper Blue"},"hashtags":["jumper"]}"""
        val s = AiAnswer.parse(answer, jumper, listOf(Ai.Account("a", "SH", 0)))
        assertEquals(listOf("Lana"), s.materials)
        assertEquals(emptyList(), s.doubts)
        assertNull(s.model, "sin modelo: the AI does not add one")
        assertEquals("L", s.applyTo(jumper).size)
    }
}

class ChosenPhotosTest {
    @Test
    fun `chosen photos keep their order and fall back to the album`() {
        val album = listOf("a", "b", "c", "d")
        assertEquals(album, Catalog.chosenPhotos(album, emptyList()) { it })
        assertEquals(listOf("c", "a"), Catalog.chosenPhotos(album, listOf("c", "x", "a")) { it })
        assertEquals(album, Catalog.chosenPhotos(album, listOf("x")) { it })
    }
}

class StageTest {
    @Test
    fun `stages are one word and uploaded is not played`() {
        val mainAccount = VintedAccount("me", "Móvil", "SH", vintedName = "shiorawear")
        val s = CatalogState(mainAccount = mainAccount)
        fun st(pcTask: PcTask?, uploaded: Set<String> = emptySet()) =
            Catalog.stage(s, Article(number = 1, createdAt = 1, pcTask = pcTask, uploaded = uploaded), mainAccount)
        assertEquals(Stage.TO_UPLOAD, st(null))
        assertEquals(Stage.WORKING, st(PcTask("r", PcTask.PREPARING)))
        assertEquals(Stage.REVIEW, st(PcTask("r", "revisar")))
        assertEquals(Stage.DRAFT, st(PcTask("r", "borrador")))
        assertEquals(Stage.UPLOADED, st(PcTask("r", "publicado"), setOf("me")))
        assertEquals(Stage.PLAYED, st(PcTask("r", "jugado"), setOf("me")))
        assertEquals(Stage.UPLOADED, st(null, setOf("me")))
    }
}

class FitsTest {
    @Test
    fun `the cut decides the jeans category and the model number wins`() {
        assertEquals(TrouserFits.SLIM, TrouserFits.ofModel("Levi's", "511"))
        assertEquals(TrouserFits.STRAIGHT, TrouserFits.ofModel("Levis", "505 Regular"))
        assertEquals(TrouserFits.BAGGY, TrouserFits.normalize("Loose Straight"))
        assertEquals("Hombre › Ropa › Vaqueros › Vaqueros rectos", TrouserFits.jeansCategory(Gender.MEN, TrouserFits.STRAIGHT))
        assertEquals("Mujer › Ropa › Vaqueros › Vaqueros de campana", TrouserFits.jeansCategory(Gender.WOMEN, TrouserFits.BOOTCUT))
        assertEquals("Mujer › Ropa › Vaqueros › Vaqueros rectos", TrouserFits.forGender("Hombre › Ropa › Vaqueros › Vaqueros rectos", Gender.WOMEN))
        // The AI said «ajustados» but the patch reads 505: straight.
        val jeans = Listing(type = "jeans", brand = "Levi's", model = "505", gender = Gender.MEN)
        val answer = """{"hallazgos":[],"tipo_prenda":"jeans","palabras":[],"sexo":"hombre","corte":"slim",
            "categoria":"Hombre › Ropa › Vaqueros › Vaqueros ajustados","colores":["Azul"],"materiales":[],"dudas":["categoria"],
            "marca":null,"modelo":null,"talla":null,"titulos":{"SH":"Levi's 505 Jeans Blue"},"hashtags":["levis"]}"""
        val s = AiAnswer.parse(answer, jeans, listOf(Ai.Account("a", "SH", 0)))
        assertEquals("Hombre › Ropa › Vaqueros › Vaqueros rectos", s.category)
        assertTrue("categoria" !in s.doubts)
        // A women's label read in the photos moves the category to Mujer.
        val r = TagReading(gender = "mujer", fit = TrouserFits.STRAIGHT)
        val s2 = AiAnswer.parse(answer, jeans.copy(gender = null, model = ""), listOf(Ai.Account("a", "SH", 0)), r)
        assertEquals("Mujer › Ropa › Vaqueros › Vaqueros rectos", s2.category)
    }
}

class CategoryFamilyTest {
    @Test
    fun `the garment type narrows the categories the AI sees`() {
        val jeans = AiCategories.categoriesFor(Gender.MEN, "jeans")
        assertTrue(jeans.isNotEmpty() && jeans.all { it.startsWith("Hombre › Ropa › Vaqueros") }, jeans.toString())
        val hoodies = AiCategories.categoriesFor(Gender.WOMEN, "hoodie")
        assertTrue(hoodies.all { "Jerséis y sudaderas" in it }, hoodies.toString())
        assertEquals(AiCategories.categoriesFor(Gender.MEN), AiCategories.categoriesFor(Gender.MEN, ""))    // unknown type: all
    }
}
