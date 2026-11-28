package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VintedSizesTest {
    private val womenJeans = "Mujer › Ropa › Vaqueros › Vaqueros pitillo"
    private val menJeans = "Hombre › Ropa › Vaqueros › Vaqueros pitillo"
    private val menSweat = "Hombre › Ropa › Jerséis y sudaderas › Sudaderas con cremallera"
    private val menShirt = "Hombre › Ropa › Camisetas y camisas › Camisas › Camisas de cuadros"
    private val menShoes = "Hombre › Calzado › Zapatillas"
    private val womenShoes = "Mujer › Calzado › Zapatillas"

    @Test
    fun `every category of the app has its sizes or none on purpose`() {
        val withSizes = VintedTaxonomy.categories.count { VintedSizes.asksForSize(it) }
        assertEquals(295, withSizes, "categories with sizes in Vinted's catalog")
        for (c in VintedTaxonomy.categories.filter { "Calzado" in it }) assertTrue(VintedSizes.asksForSize(c), c)
        assertTrue(!VintedSizes.asksForSize("Mujer › Bolsos › Mochilas"))
    }

    @Test
    fun `what the form offers`() {
        assertEquals("XXXS", VintedSizes.options(womenJeans).first())
        assertTrue("W30" !in VintedSizes.options(womenJeans), "women's jeans have no W sizes in the form")
        assertTrue("W30" in VintedSizes.options(menJeans) && "M" in VintedSizes.options(menJeans))
        assertTrue("38" in VintedSizes.options(menShirt) && "L" in VintedSizes.options(menShirt))
        assertTrue("42.5" in VintedSizes.options(menShoes) && "42.5" in VintedSizes.options(womenShoes))
        assertEquals(VintedSizes.ONE_SIZE, VintedSizes.options(menSweat).last())
    }

    @Test
    fun `women's clothes`() {
        assertEquals("M", VintedSizes.resolve(womenJeans, "W30 L30"))
        assertEquals("M", VintedSizes.resolve(womenJeans, "w29"))
        assertEquals("S", VintedSizes.resolve(womenJeans, "W27 L32"))
        assertEquals("L", VintedSizes.resolve(womenJeans, "W32"))
        assertEquals("M", VintedSizes.resolve(womenJeans, "38"))
        assertEquals("M", VintedSizes.resolve(womenJeans, "EU 38"))
        assertEquals("M", VintedSizes.resolve(womenJeans, "M / 38 / 10"))
        assertEquals("XXL", VintedSizes.resolve(womenJeans, "2XL"))
        assertNull(VintedSizes.resolve(womenJeans, "S/M"), "two letters: the user decides")
        assertNull(VintedSizes.resolve(womenJeans, "UK 10"))
        assertNull(VintedSizes.resolve(womenJeans, "39"))
    }

    @Test
    fun `men's clothes`() {
        assertEquals("W32", VintedSizes.resolve(menJeans, "W32 L34"))
        assertEquals("W30", VintedSizes.resolve(menJeans, "w 30"))
        assertEquals("M", VintedSizes.resolve(menJeans, "M"))
        assertEquals("M", VintedSizes.resolve(menSweat, "W32"), "no W in sweatshirts: the letter")
        assertEquals("L", VintedSizes.resolve(menSweat, "Talla L"))
        assertEquals(VintedSizes.ONE_SIZE, VintedSizes.resolve(menSweat, "talla única"))
        assertEquals("39", VintedSizes.resolve(menShirt, "39"))
        assertNull(VintedSizes.resolve(menSweat, "50"), "Italian numbers are not guessed")
        assertNull(VintedSizes.resolve(menJeans, "32/34"))
    }

    @Test
    fun `shoes`() {
        assertEquals("42", VintedSizes.resolve(menShoes, "42"))
        assertEquals("42.5", VintedSizes.resolve(menShoes, "42,5"))
        assertEquals("42.5", VintedSizes.resolve(menShoes, "EU 42.5"))
        assertEquals("42.5", VintedSizes.resolve(menShoes, "42 1/2"))
        assertEquals("37", VintedSizes.resolve(womenShoes, "37 EU"))
        assertNull(VintedSizes.resolve(menShoes, "42 2/3"), "adidas thirds are not in the form")
        assertNull(VintedSizes.resolve(menShoes, "UK 8"))
        assertNull(VintedSizes.resolve(menShoes, "M"))
        assertNull(VintedSizes.resolve(menShoes, "47"))
    }
}

class TitleTest {
    @Test
    fun `no repeated words in titles`() {
        assertEquals(
            "Levi's 710 Skinny Jeans Vintage Y2K Classic Fit Light Blue",
            Ai.distinctWords("Levi's 710 Skinny Jeans Vintage Y2K Classic Skinny Fit Light Blue"),
        )
        assertEquals("Nike Air Max 90 White", Ai.distinctWords("Nike Air Max 90 White"))
        assertEquals("Carhartt Jacket Brown", Ai.distinctWords("Carhartt Jacket Brown brown"))
    }
}
