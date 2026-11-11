package dev.catalogo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTest {

    private val accounts = listOf(Ai.Account("p", "SH", 0), Ai.Account("c1", "SA", 1))
    private val levis = Listing(brand = "Levi's", model = "501", size = "W32 L34", keywords = listOf("90s"))

    @Test
    fun `request carries prompt, photos and a strict schema with every account`() {
        val body = Json.parseToJsonElement(Ai.request(levis, accounts, listOf("AAA", "BBB", "CCC", "DDD"))).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1 + Ai.PHOTOS, parts.size)
        val prompt = parts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("\"marca\":\"Levi's\"" in prompt, prompt)
        assertTrue("\"epoca\":[\"90s\"]" in prompt, prompt)
        assertTrue("SH: " in prompt && "SA: " in prompt)
        val schema = body["generationConfig"]!!.jsonObject["responseSchema"]!!.jsonObject
        val props = schema["properties"]!!.jsonObject
        assertEquals("hallazgos", schema["propertyOrdering"]!!.jsonArray[0].jsonPrimitive.content, "observe before writing")
        assertEquals(setOf("SH", "SA"), props["titulos"]!!.jsonObject["properties"]!!.jsonObject.keys)
        assertFalse(props["palabras"].toString().contains("\"90s\""), "epochs are the user's choice")
    }

    @Test
    fun `answer is checked against the closed lists`() {
        val answer = """
            {"hallazgos":[{"detalle":"Etiqueta Levi's 501","prueba":"cintura"}],
             "tipo_prenda":"jeans","palabras":["straight","faded","90s","invented","cableknit"],
             "sexo":"hombre","categoria":"Hombre › Ropa › Vaqueros › Vaqueros rectos",
             "colores":["Azul","Azul claro","Negro"],"talla_etiqueta":"W30",
             "titulos":{"SH":"Levi's 501 Straight Leg Jeans 90s Faded","SA":"  1990s Levis 501 Denim Pants\n Sun Faded. ","XX":"nope"}}
        """
        val s = Ai.parse(answer, levis, accounts)
        assertEquals("jeans", s.type)
        assertEquals(listOf("straight", "faded"), s.keywords)
        assertEquals(Gender.MEN, s.gender)
        assertEquals("Hombre › Ropa › Vaqueros › Vaqueros rectos", s.category)
        assertEquals(listOf("Azul", "Azul claro"), s.colors)
        assertNull(s.sizeLabel, "the user already gave a size")
        assertEquals(mapOf("p" to "Levi's 501 Straight Leg Jeans 90s Faded", "c1" to "1990s Levis 501 Denim Pants Sun Faded"), s.titles)
        assertEquals(listOf("Etiqueta Levi's 501"), s.findings)
    }

    @Test
    fun `wrong gender category, unknown values and duplicated titles are dropped`() {
        val answer = """{"tipo_prenda":"spaceship","palabras":[],"sexo":"mujer",
            "categoria":"Hombre › Ropa › Vaqueros › Vaqueros rectos","colores":["Azulado"],"talla_etiqueta":"M",
            "titulos":{"SH":"Same Title","SA":"same title"}}"""
        val s = Ai.parse(answer, Listing(), accounts)
        assertNull(s.type)
        assertNull(s.category)
        assertEquals(emptyList(), s.colors)
        assertEquals("M", s.sizeLabel)
        assertEquals(mapOf("p" to "Same Title"), s.titles)
    }

    @Test
    fun `user values win when suggestions are applied`() {
        val s = AiSuggestion("hoodie", listOf("oversized", "bootcut"), Gender.WOMEN, null, emptyList(), "L", emptyMap(), emptyList())
        val applied = s.applyTo(Listing(type = "", size = "", keywords = listOf("y2k"), gender = Gender.MEN))
        assertEquals("hoodie", applied.type)
        assertEquals(listOf("y2k", "oversized"), applied.keywords, "keywords that do not fit the garment are dropped")
        assertEquals(Gender.MEN, applied.gender)
        assertEquals("L", applied.size)
        assertEquals("jeans", s.applyTo(Listing(type = "jeans")).type)
    }

    @Test
    fun `listing text uses ai titles only while the data they were written for is unchanged`() {
        val samsung = Device("p", "Samsung", "SH")
        val poco = Device("c1", "Poco", "SA", "s1", style = 1)
        var (s, n) = Catalog.create(ParentState(self = samsung, children = listOf(poco)), 0)
        val listing = levis.copy(type = "jeans")
        val ai = AiResult(Ai.basis(listing), titles = mapOf("p" to "AI SH", "c1" to "AI SA"))
        s = Catalog.updateArticle(s, n, listing, null, null, ai)
        assertEquals("AI SH", Catalog.listingText(s, n, samsung)!!.title)
        assertEquals("AI SA", Catalog.listingText(s, n, poco)!!.title)
        s = Catalog.updateArticle(s, n, listing.copy(size = "W33", notes = "Tiene manchas"), null, null, ai)
        assertEquals("AI SH", Catalog.listingText(s, n, samsung)!!.title, "size and notes are not in the title")
        s = Catalog.updateArticle(s, n, listing.copy(model = "505"), null, null, ai)
        assertNull(Catalog.freshAi(s, n))
        assertEquals(ListingWriter.title(listing.copy(model = "505"), 0), Catalog.listingText(s, n, samsung)!!.title)
    }

    @Test
    fun `ai answer is saved into the article without touching what the user typed`() {
        val samsung = Device("p", "Samsung", "SH")
        var (s, n) = Catalog.create(ParentState(self = samsung), 0)
        s = Catalog.updateArticle(s, n, levis.copy(notes = "Tiene manchas"), null, null)
        val answer = AiSuggestion("jeans", listOf("straight"), Gender.MEN, "Hombre › Ropa › Vaqueros › Vaqueros rectos",
            listOf("Azul"), "W30", mapOf("p" to "Levi's 513 Jeans"), emptyList())
        s = Catalog.applyAi(s, n, answer, "gemini-x-flash")
        val a = Catalog.article(s, n)!!
        assertEquals("jeans", a.listing!!.type)
        assertEquals(listOf("90s", "straight"), a.listing!!.keywords)
        assertEquals("W32 L34", a.listing!!.size)
        assertEquals("Tiene manchas", a.listing!!.notes)
        assertEquals("Levi's 513 Jeans", Catalog.listingText(s, n, samsung)!!.title)
        assertEquals("gemini-x-flash", a.ai!!.model)
    }

    @Test
    fun `letter sizes follow gender and show in the description`() {
        assertEquals("M", Vinted.letterSize("W32 L34", Gender.MEN))
        assertEquals("L", Vinted.letterSize("w32", Gender.WOMEN))
        assertEquals("S", Vinted.letterSize("W 30 L32", Gender.MEN))
        assertNull(Vinted.letterSize("W32 L34", null))
        assertNull(Vinted.letterSize("M", Gender.MEN))
        val l = Listing(size = "W32 L34", gender = Gender.MEN)
        assertTrue(ListingWriter.description(l, 0, "", "SH1").startsWith("🏷️ Talla: W32 L34 (equivale a una M)\n"))
        assertTrue(ListingWriter.description(l, 1, "", "SA1").startsWith("🏷️ Talla W32 L34 · M\n"))
        assertTrue(ListingWriter.description(l.copy(gender = null), 0, "", "SH1").startsWith("🏷️ Talla: W32 L34\n"))
    }

    @Test
    fun `brand, model, size and hashtags fill only what the user left blank`() {
        val answer = """{"marca":"Levi's","modelo":"505","talla":"W33","titulos":{},
            "hashtags":["#Levis","levis505","Vaqueros Vintage","sh0019","sa12","ootd!","levis"]}"""
        val blank = Listing(noLabel = true, flat = Measures(waist = "42"))
        val s = Ai.parse(answer, blank, accounts)
        assertEquals("Levi's", s.brand)
        assertEquals("505", s.model)
        assertEquals(listOf("levis", "levis505", "vaquerosvintage", "ootd"), s.hashtags)
        val applied = s.applyTo(blank)
        assertEquals("W33", applied.size)
        assertEquals("Levi's", applied.brand)
        val noBrand = Ai.parse(answer, Listing(noBrand = true, size = "M"), accounts)
        assertNull(noBrand.brand)
        assertNull(noBrand.model)
        assertNull(noBrand.sizeLabel)
        assertNull(Ai.parse(answer, levis, accounts).brand, "the user typed the brand")
    }

    @Test
    fun `seller notes and missing data reach the prompt, and thinking stays short`() {
        val prompt = Ai.prompt(Listing(noBrand = true, noLabel = true, flat = Measures(chest = "55"), aiNotes = "Made in Argentina"), accounts)
        assertTrue("\"marca\":\"SIN MARCA\"" in prompt, prompt)
        assertTrue("\"pecho\":\"55\"" in prompt)
        assertTrue("\"notas_del_vendedor\":\"Made in Argentina\"" in prompt)
        assertEquals("1024", Ai.thinkingConfig("gemini-2.5-flash")!!["thinkingBudget"].toString())
        assertNull(Ai.thinkingConfig("gemini-2.5-flash-lite"))
        assertEquals("\"low\"", Ai.thinkingConfig("gemini-3-flash")!!["thinkingLevel"].toString())
        assertNull(Ai.thinkingConfig("gemini-flash-latest"))
        assertFalse("thinkingConfig" in Ai.request(levis, accounts, listOf("A"), "gemini-3-flash", thinking = false))
        assertTrue("thinkingConfig" in Ai.request(levis, accounts, listOf("A"), "gemini-3-flash"))
    }

    @Test
    fun `old ai results stay valid and seller notes make titles stale`() {
        val l = levis.copy(type = "jeans")
        assertEquals("jeans|levi's|501|90s", Ai.basis(l))
        assertTrue(Ai.basis(l.copy(aiNotes = "Made in USA")) != Ai.basis(l))
    }

    @Test
    fun `categories are unique and split by gender`() {
        assertEquals(Vinted.categories.size, Vinted.categories.toSet().size)
        assertTrue(Vinted.categories.all { it.startsWith("Hombre › ") || it.startsWith("Mujer › ") })
        assertTrue("Mujer › Ropa › Vestidos › Midi" in Vinted.categories)
        assertFalse("Hombre › Ropa › Vestidos › Midi" in Vinted.categories)
    }

    @Test
    fun `answer text is read from the first candidate`() {
        val r = """{"candidates":[{"content":{"parts":[{"text":"{\"a\":"},{"text":"1}"}]}}]}"""
        assertEquals("{\"a\":1}", Ai.answerText(r))
        val blocked = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertTrue(runCatching { Ai.answerText(blocked) }.exceptionOrNull()!!.message!!.contains("SAFETY"))
    }
}
