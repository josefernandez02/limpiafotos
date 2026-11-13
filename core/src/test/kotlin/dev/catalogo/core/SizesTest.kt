package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SizesTest {
    private val womenJeans = "Mujer › Ropa › Vaqueros › Vaqueros pitillo"
    private val menJeans = "Hombre › Ropa › Vaqueros › Vaqueros pitillo"
    private val menSweat = "Hombre › Ropa › Jerséis y sudaderas › Sudaderas con cremallera"
    private val menShirt = "Hombre › Ropa › Camisetas y camisas › Camisas › Camisas de cuadros"
    private val menShoes = "Hombre › Calzado › Zapatillas"
    private val womenShoes = "Mujer › Calzado › Zapatillas"

    @Test
    fun `every category of the app has its sizes or none on purpose`() {
        val withSizes = Vinted.categories.count { Sizes.asksForSize(it) }
        assertEquals(295, withSizes, "categories with sizes in Vinted's catalog")
        for (c in Vinted.categories.filter { "Calzado" in it }) assertTrue(Sizes.asksForSize(c), c)
        assertTrue(!Sizes.asksForSize("Mujer › Bolsos › Mochilas"))
    }

    @Test
    fun `what the form offers`() {
        assertEquals("XXXS", Sizes.options(womenJeans).first())
        assertTrue("W30" !in Sizes.options(womenJeans), "women's jeans have no W sizes in the form")
        assertTrue("W30" in Sizes.options(menJeans) && "M" in Sizes.options(menJeans))
        assertTrue("38" in Sizes.options(menShirt) && "L" in Sizes.options(menShirt))
        assertTrue("42.5" in Sizes.options(menShoes) && "42.5" in Sizes.options(womenShoes))
        assertEquals(Sizes.ONE_SIZE, Sizes.options(menSweat).last())
    }

    @Test
    fun `women's clothes`() {
        assertEquals("M", Sizes.resolve(womenJeans, "W30 L30"))
        assertEquals("M", Sizes.resolve(womenJeans, "w29"))
        assertEquals("S", Sizes.resolve(womenJeans, "W27 L32"))
        assertEquals("L", Sizes.resolve(womenJeans, "W32"))
        assertEquals("M", Sizes.resolve(womenJeans, "38"))
        assertEquals("M", Sizes.resolve(womenJeans, "EU 38"))
        assertEquals("M", Sizes.resolve(womenJeans, "M / 38 / 10"))
        assertEquals("XXL", Sizes.resolve(womenJeans, "2XL"))
        assertNull(Sizes.resolve(womenJeans, "S/M"), "two letters: the user decides")
        assertNull(Sizes.resolve(womenJeans, "UK 10"))
        assertNull(Sizes.resolve(womenJeans, "39"))
    }

    @Test
    fun `men's clothes`() {
        assertEquals("W32", Sizes.resolve(menJeans, "W32 L34"))
        assertEquals("W30", Sizes.resolve(menJeans, "w 30"))
        assertEquals("M", Sizes.resolve(menJeans, "M"))
        assertEquals("M", Sizes.resolve(menSweat, "W32"), "no W in sweatshirts: the letter")
        assertEquals("L", Sizes.resolve(menSweat, "Talla L"))
        assertEquals(Sizes.ONE_SIZE, Sizes.resolve(menSweat, "talla única"))
        assertEquals("39", Sizes.resolve(menShirt, "39"))
        assertNull(Sizes.resolve(menSweat, "50"), "Italian numbers are not guessed")
        assertNull(Sizes.resolve(menJeans, "32/34"))
    }

    @Test
    fun `shoes`() {
        assertEquals("42", Sizes.resolve(menShoes, "42"))
        assertEquals("42.5", Sizes.resolve(menShoes, "42,5"))
        assertEquals("42.5", Sizes.resolve(menShoes, "EU 42.5"))
        assertEquals("42.5", Sizes.resolve(menShoes, "42 1/2"))
        assertEquals("37", Sizes.resolve(womenShoes, "37 EU"))
        assertNull(Sizes.resolve(menShoes, "42 2/3"), "adidas thirds are not in the form")
        assertNull(Sizes.resolve(menShoes, "UK 8"))
        assertNull(Sizes.resolve(menShoes, "M"))
        assertNull(Sizes.resolve(menShoes, "47"))
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
