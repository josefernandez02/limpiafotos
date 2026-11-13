package dev.catalogo.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftTest {

    private val samsung = Device("p", "Samsung", "SH", account = "shiorawear")
    private val levis = Listing(type = "jeans", brand = "Levi's", model = "501", size = "W32 L34", gender = Gender.MEN)

    /** One article with fresh AI data for [listing]. */
    private fun state(
        listing: Listing = levis,
        price: Long? = 1500,
        ai: (Listing) -> AiResult = { l ->
            AiResult(Ai.basis(l), "Hombre › Ropa › Vaqueros › Vaqueros rectos", listOf("Azul"),
                mapOf("p" to "Levi's 501 Jeans Vintage Straight Fit Blue"), materials = listOf("Vaquero", "Algodón"))
        },
    ): Pair<ParentState, Article> {
        val (s, n) = Catalog.create(ParentState(self = samsung), 1_700_000_000_000)
        val a = Catalog.article(s, n)!!.copy(listing = listing, price = price, ai = ai(listing))
        val s2 = s.copy(articles = s.articles.map { if (it.number == n) a else it })
        return s2 to a
    }

    @Test
    fun `complete article becomes a ready payload`() {
        val (s, a) = state()
        val b = Draft.build(s, a, samsung)
        assertTrue(b.ready, "${b.problems} ${b.doubts}")
        val p = b.payload!!
        assertEquals("shiorawear", p["cuenta"]!!.jsonPrimitive.content)
        assertEquals("Levi's 501 Jeans Vintage Straight Fit Blue", p["titulo"]!!.jsonPrimitive.content)
        assertEquals("Hombre › Ropa › Vaqueros › Vaqueros rectos", p["categoria"]!!.jsonPrimitive.content)
        assertEquals("W32", p["talla"]!!.jsonPrimitive.content, "the form lists W sizes without the L")
        assertEquals("muy_bueno", p["estado"]!!.jsonPrimitive.content)
        assertEquals(listOf("Vaquero", "Algodón"), p["materiales"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(15.0, p["precio"]!!.jsonPrimitive.content.toDouble())
        assertEquals("pequeno", p["envio"]!!.jsonPrimitive.content)
        assertTrue(p["descripcion"]!!.jsonPrimitive.content.contains("SH"), "the description carries the SKU")
        assertTrue(b.ref.startsWith("SH") && "-" in b.ref)
    }

    @Test
    fun `missing price, account or AI block the draft`() {
        val (s, a) = state(price = null)
        assertTrue(Draft.build(s, a, samsung).problems.any { "precio" in it })
        val (s2, a2) = state()
        assertTrue(Draft.build(s2, a2, samsung.copy(account = "")).problems.any { "cuenta" in it })
        val (s3, a3) = state(ai = { AiResult("otra base") })
        assertTrue(Draft.build(s3, a3, samsung).problems.any { "IA" in it }, "stale AI is not used")
    }

    @Test
    fun `doubts wait for the user and fixes settle them`() {
        val (s, a) = state(ai = { l ->
            AiResult(Ai.basis(l), "Hombre › Ropa › Vaqueros › Vaqueros rectos", listOf("Azul"), mapOf("p" to "T"),
                doubts = listOf("categoria", "marca"))
        })
        val b = Draft.build(s, a, samsung)
        assertFalse(b.ready)
        assertEquals(listOf("categoria", "marca"), b.doubts)
        val fixed = Draft.build(s, a, samsung,
            Draft.Fixes(category = "Hombre › Ropa › Vaqueros › Vaqueros ajustados", brandChecked = true))
        assertTrue(fixed.ready, "${fixed.doubts}")
        assertEquals("Hombre › Ropa › Vaqueros › Vaqueros ajustados", fixed.payload!!["categoria"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an estimated size is not asked, unknown category and no colour are doubts`() {
        val (s, a) = state(listing = levis.copy(noLabel = true), ai = { l ->
            AiResult(Ai.basis(l), "Hombre › Inventada", listOf("Albaricoque"), mapOf("p" to "T"))
        })
        val b = Draft.build(s, a, samsung)
        assertEquals(setOf("categoria"), b.doubts.toSet(), "legacy colour is mapped, not a doubt; no label: the AI sizes it")
        val (s2, a2) = state(ai = { l -> AiResult(Ai.basis(l), "Hombre › Ropa › Vaqueros › Vaqueros rectos", emptyList(), mapOf("p" to "T")) })
        assertEquals(listOf("colores"), Draft.build(s2, a2, samsung).doubts)
    }

    @Test
    fun `a size the form does not offer waits for the user, a bag needs none`() {
        val (s, a) = state(listing = levis.copy(size = ""))
        assertEquals(listOf("talla"), Draft.build(s, a, samsung).doubts)
        assertEquals(listOf("talla"), Draft.build(s, a, samsung, Draft.Fixes(size = "")).doubts, "jeans always need a size")
        val ok = Draft.build(s, a, samsung, Draft.Fixes(size = "W33"))
        assertTrue(ok.ready, "${ok.doubts}")
        assertEquals("W33", ok.payload!!["talla"]!!.jsonPrimitive.content)

        val bag = Listing(type = "bag", brand = "Eastpak", size = "Única")
        val (s2, a2) = state(listing = bag, ai = { l ->
            AiResult(Ai.basis(l), "Mujer › Bolsos › Mochilas", listOf("Negro"), mapOf("p" to "T"))
        })
        val b = Draft.build(s2, a2, samsung)
        assertTrue(b.ready, "${b.doubts}")
        assertEquals(JsonNull, b.payload!!["talla"], "the form asks no size for bags")
    }

    @Test
    fun `women's jeans with a waist size go as the letter the form offers`() {
        val l = Listing(type = "jeans", brand = "Levi's", model = "710", size = "W30 L30", gender = Gender.WOMEN)
        val (s, a) = state(listing = l, ai = { x ->
            AiResult(Ai.basis(x), "Mujer › Ropa › Vaqueros › Vaqueros pitillo", listOf("Azul claro"), mapOf("p" to "T"))
        })
        val b = Draft.build(s, a, samsung)
        assertTrue(b.ready, "${b.problems} ${b.doubts}")
        assertEquals("M", b.payload!!["talla"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the brand is always written, also for old articles without brand`() {
        val (s, a) = state(listing = levis.copy(brand = "", noBrand = true))
        assertTrue(Draft.build(s, a, samsung).problems.any { "marca" in it })
        val (s2, a2) = state(listing = levis.copy(brand = "Vintage Dressing", noBrand = false))
        assertEquals("Vintage Dressing", Draft.build(s2, a2, samsung).payload!!["marca"]!!.jsonPrimitive.content)
    }

    @Test
    fun `colour helpers`() {
        assertEquals("Naranja pastel", Vinted.color("Albaricoque"))
        assertNull(Vinted.color("Inventado"))
    }
}
