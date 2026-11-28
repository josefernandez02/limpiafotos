package dev.catalogo.core.vinted

import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.VintedAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VintedProfileTest {
    private val samsung = VintedAccount("p", "Samsung", "SH")

    @Test
    fun `profile links give member id and name`() {
        assertEquals("123456" to "shiorawear", VintedProfile.parse("https://www.vinted.es/member/123456-shiorawear"))
        assertEquals("98765" to "", VintedProfile.parse("vinted.es/member/98765"))
        assertEquals(null, VintedProfile.parse("shiorawear"))
        val s = Catalog.setVintedProfile(CatalogState(mainAccount = samsung), "p", "", "https://www.vinted.es/member/123456-shiorawear?tab=x")
        assertEquals("shiorawear", s.mainAccount.label)
        assertEquals("123456", s.mainAccount.vintedId)
        assertFailsWith<CatalogException> { Catalog.setVintedProfile(s, "p", "x", "nope") }
    }
}
