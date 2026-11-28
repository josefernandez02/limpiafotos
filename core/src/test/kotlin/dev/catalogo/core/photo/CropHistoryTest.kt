package dev.catalogo.core.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CropHistoryTest {
    @Test
    fun `a record survives being saved and read back`() {
        val list = listOf(CropEdit(2, CropHistory.MANUAL, 1000L), null, CropEdit(1, CropHistory.AUTO, 5L))
        assertEquals(list, CropHistory.decode(CropHistory.encode(list)))
    }

    @Test
    fun `garbage reads as no crops, never as a crash`() {
        assertEquals(listOf(null, null), CropHistory.decode("x,y;1,zzz,3"))
        assertEquals(emptyList(), CropHistory.decode(""))
    }

    @Test
    fun `counting one more crop`() {
        val a = CropHistory.next(null, CropHistory.AUTO, 10L)
        val b = CropHistory.next(a, CropHistory.MANUAL, 20L)
        assertEquals(CropEdit(1, CropHistory.AUTO, 10L), a)
        assertEquals(CropEdit(2, CropHistory.MANUAL, 20L), b)
    }

    @Test
    fun `a record for another number of photos is not trusted`() {
        val saved = listOf<CropEdit?>(CropEdit(1, CropHistory.AUTO, 1L), null)
        assertEquals(saved, CropHistory.aligned(saved, 2))
        assertNull(CropHistory.aligned(saved, 3)[0])
    }

    @Test
    fun `the sentence says how many times, how and when`() {
        val now = 10 * 60_000L
        assertEquals("Recortada 1 vez · la última automático, hace 5 min", CropHistory.describe(CropEdit(1, CropHistory.AUTO, 5 * 60_000L), now))
        assertEquals("Recortada 3 veces · la última a mano, ahora", CropHistory.describe(CropEdit(3, CropHistory.MANUAL, now), now))
    }
}
