package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VintedProfileTest {
    private val samsung = Device("p", "Samsung", "SH")

    @Test
    fun `profile links give member id and name`() {
        assertEquals("123456" to "shiorawear", VintedProfile.parse("https://www.vinted.es/member/123456-shiorawear"))
        assertEquals("98765" to "", VintedProfile.parse("vinted.es/member/98765"))
        assertEquals(null, VintedProfile.parse("shiorawear"))
        val s = Catalog.setAccount(ParentState(self = samsung), "p", "", "https://www.vinted.es/member/123456-shiorawear?tab=x")
        assertEquals("shiorawear", s.self.label)
        assertEquals("123456", s.self.vintedId)
        assertFailsWith<CatalogException> { Catalog.setAccount(s, "p", "x", "nope") }
    }
}
