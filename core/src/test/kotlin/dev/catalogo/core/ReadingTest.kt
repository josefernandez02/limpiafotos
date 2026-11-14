package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingTest {
    private val jeans = Listing(type = "jeans", brand = "Levi's", model = "505", size = "W38 L38", gender = Gender.MEN)
    private val accounts = listOf(Ai.Account("a", "SH", 0))

    @Test
    fun `composition decides the materials`() {
        assertEquals(listOf("Algodón"), Reader.materials(listOf("100% ALGODÓN")))
        assertEquals(listOf("Algodón", "Elastano"), Reader.materials(listOf("98% cotton 2% elastane")))
        assertEquals(listOf("Poliéster", "Algodón"), Reader.materials(listOf("35% algodón", "65% poliéster")))
        assertEquals(listOf("Cuero sintético"), Reader.materials(listOf("100% piel sintética")))
        assertEquals(emptyList(), Reader.materials(listOf("hecho a mano")))
    }

    @Test
    fun `reading answer is cleaned`() {
        val r = Reader.parse(
            """{"composicion":["100% algodón","sin número"],"hecho_en":"Argentina","talla_etiqueta":" W38  L38 ",
               "marca":null,"modelo":"505","defectos":[],"detalles":["pestaña roja"]}""",
        )
        assertEquals(listOf("100% algodón"), r.composition)
        assertEquals("Argentina", r.origin)
        assertEquals("W38 L38", r.sizeLabel)
        assertNull(r.brand)
        assertEquals("100% algodón", Reader.compositionText(r))
    }

    @Test
    fun `the label wins over the listing call for materials`() {
        val reading = Reading(composition = listOf("100% algodón"), origin = "Argentina")
        val answer = """{"hallazgos":[],"tipo_prenda":"jeans","palabras":[],"sexo":"hombre","categoria":null,
            "colores":["Azul"],"materiales":["Vaquero"],"dudas":["materiales"],"marca":null,"modelo":null,"talla":null,
            "titulos":{"SH":"Levi's 505 Jeans Vintage 90s Classic Straight Fit Blue"},"hashtags":["levis"]}"""
        val s = Ai.parse(answer, jeans, accounts, reading)
        assertEquals(listOf("Algodón"), s.materials)
        assertTrue("materiales" !in s.doubts)
        val result = s.result(s.applyTo(jeans), "m")
        assertEquals("100% algodón", result.composition)
    }

    @Test
    fun `the prompt carries the reading and keeps a country out of the title`() {
        val p = Ai.prompt(jeans, accounts, Reading(composition = listOf("100% algodón")))
        assertTrue("LECTURA_DE_ETIQUETAS" in p && "100% algodón" in p, p)
        assertTrue("un país cualquiera no aporta en el título" in p)
        assertTrue("LECTURA_DE_ETIQUETAS\n{" !in Ai.prompt(jeans, accounts, null))
    }
}

class NotesTest {
    @Test
    fun `the seller's notes settle material and a size without label is not asked`() {
        val jumper = Listing(type = "jumper", brand = "Vintage Dressing", noLabel = true, aiNotes = "Es lana", noModel = true)
        val answer = """{"hallazgos":[],"tipo_prenda":"jumper","palabras":[],"sexo":"hombre","categoria":null,
            "colores":["Azul"],"materiales":["Acrílico"],"dudas":["materiales","talla"],"marca":null,"modelo":"Aran",
            "talla":"L","titulos":{"SH":"Vintage Dressing Wool Jumper Blue"},"hashtags":["jumper"]}"""
        val s = Ai.parse(answer, jumper, listOf(Ai.Account("a", "SH", 0)))
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
        val self = Device("me", "Móvil", "SH", account = "shiorawear")
        val s = ParentState(self = self)
        fun st(desk: Desk?, uploaded: Set<String> = emptySet()) =
            Catalog.stage(s, Article(number = 1, createdAt = 1, desk = desk, uploaded = uploaded), self)
        assertEquals(Stage.TO_UPLOAD, st(null))
        assertEquals(Stage.WORKING, st(Desk("r", Desk.PREPARING)))
        assertEquals(Stage.REVIEW, st(Desk("r", "revisar")))
        assertEquals(Stage.DRAFT, st(Desk("r", "borrador")))
        assertEquals(Stage.UPLOADED, st(Desk("r", "publicado"), setOf("me")))
        assertEquals(Stage.PLAYED, st(Desk("r", "jugado"), setOf("me")))
        assertEquals(Stage.UPLOADED, st(null, setOf("me")))
    }
}
