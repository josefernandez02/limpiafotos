package dev.catalogo.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverFrameTest {

    /** A jumper seen from above: body and two sleeves, symmetric about the vertical axis; [s] draws it s times bigger. */
    private fun jumper(w: Int, h: Int, shiftX: Int = 0, shiftY: Int = 0, s: Int = 1): Mask {
        val on = BooleanArray(w * h)
        val cx = (w - 1) / 2 + shiftX
        for (y in 0 until h) for (x in 0 until w) {
            val yy = (y - shiftY) / s
            val dx = abs(x - cx) / s
            on[y * w + x] = (dx <= 15 && yy in 20..95) || (dx <= 40 && yy in 20..45)
        }
        return Mask(w, h, on)
    }

    private fun box(m: Mask): IntArray {
        var x0 = m.w; var y0 = m.h; var x1 = -1; var y1 = -1
        for (y in 0 until m.h) for (x in 0 until m.w) if (m.on[y * m.w + x]) {
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    @Test
    fun `an upright garment is not turned and ends up centred at the safe size`() {
        val m = jumper(120, 140, shiftX = 9, shiftY = 6)
        val p = assertNotNull(CoverFrame.place(m, 3000, 3500))
        assertEquals(0f, p.rotation, 0.01f)
        val b = box(m)
        val k = 3000f / m.w
        val bw = (b[2] - b[0] + 1) * k
        val bh = (b[3] - b[1] + 1) * k
        // in frame widths: the limiting side is exactly the safe box, the other one fits
        val gw = bw * p.scale / 3000
        val gh = bh * p.scale / 3000
        assertEquals(1f, max(gw / CoverFrame.SAFE_W, gh / (CoverFrame.SAFE_H * CoverFrame.FRAME_H)), 0.02f)
        // centred: the garment's centre lands on the frame's centre (a little higher by LIFT)
        val gcx = ((b[0] + b[2] + 1) / 2f - m.w / 2f) * k
        val gcy = ((b[1] + b[3] + 1) / 2f - m.h / 2f) * k
        assertEquals(-gcx * p.scale / 3000, p.dx, 0.01f)
        assertEquals(-gcy * p.scale / 3000 - CoverFrame.LIFT * CoverFrame.FRAME_H, p.dy, 0.01f)
    }

    @Test
    fun `the same garment photographed bigger or smaller gets the same size`() {
        fun framedWidth(m: Mask): Float {
            val p = CoverFrame.place(m, 3000, 3500)!!
            val b = box(m)
            return (b[2] - b[0] + 1) * (3000f / m.w) * p.scale / 3000
        }
        assertEquals(framedWidth(jumper(120, 140)), framedWidth(jumper(240, 280, s = 2)), 0.05f)
    }

    @Test
    fun `a crooked garment is straightened`() {
        for (deg in listOf(-8f, -4.5f, 3f, 7f)) {
            val tilted = CoverFrame.rotated(jumper(100, 110), deg)     // the photo was taken crooked by deg
            val p = assertNotNull(CoverFrame.place(tilted, 3000, 3000))
            // it has to be turned back by about -deg (to within a degree)
            assertEquals(-deg, p.rotation, 1.0f, "tilt $deg")
        }
    }

    @Test
    fun `a round or lopsided shape is left as it is`() {
        val n = 120
        val disc = Mask(n, n, BooleanArray(n * n) { i -> val x = i % n - n / 2; val y = i / n - n / 2; x * x + y * y < 45 * 45 })
        assertEquals(0f, CoverFrame.tilt(disc), 0.01f)
    }

    @Test
    fun `no believable silhouette means no framing`() {
        assertNull(CoverFrame.place(Mask(50, 50, BooleanArray(2500)), 1000, 1000))                       // nothing
        assertNull(CoverFrame.place(Mask(50, 50, BooleanArray(2500) { it == 1000 }), 1000, 1000))        // a speck
        assertNull(CoverFrame.place(Mask(50, 50, BooleanArray(2500) { true }), 1000, 1000))              // everything
    }

    @Test
    fun `stray specks do not move the box`() {
        val clean = jumper(120, 140)
        val specks = Mask(120, 140, clean.on.copyOf().also { it[2 * 120 + 2] = true; it[130 * 120 + 117] = true })
        val a = CoverFrame.place(clean, 3000, 3500)!!
        val b = CoverFrame.place(specks, 3000, 3500)!!
        assertEquals(a.scale, b.scale, 0.01f)
        assertEquals(a.dx, b.dx, 0.01f)
        assertEquals(a.dy, b.dy, 0.01f)
    }

    @Test
    fun `the backdrop silhouette finds a garment on a floor that is lit unevenly`() {
        val w = 120
        val h = 160
        val rnd = java.util.Random(7)
        fun photo(garment: (Int) -> Int): IntArray = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val tex = rnd.nextInt(17) - 8
            val light = 120 + 50 * x / w + 30 * (h - y) / h                      // brighter to the right and to the top
            if (x in 30..89 && y in 40..119) garment(tex)
            else (0xFF shl 24) or ((light + tex) shl 16) or ((light - 2 + tex) shl 8) or (light - 5 + tex)
        }
        // a red garment, and a grey one about as bright as the floor around it
        for (colour in listOf({ t: Int -> (0xFF shl 24) or ((190 + t) shl 16) or ((60 + t) shl 8) or (60 + t) },
                              { t: Int -> (0xFF shl 24) or ((105 + t) shl 16) or ((105 + t) shl 8) or (108 + t) })) {
            val m = Backdrop.mask(photo(colour), w, h)
            val b = box(CoverFrame.largestParts(m)!!)
            assertTrue(abs(b[0] - 30) <= 2 && abs(b[2] - 89) <= 2 && abs(b[1] - 40) <= 2 && abs(b[3] - 119) <= 2, "box ${b.toList()}")
        }
    }

    @Test
    fun `a photo that is all garment gives no framing`() {
        val w = 100
        val h = 130
        val rnd = java.util.Random(3)
        val allGarment = IntArray(w * h) { (0xFF shl 24) or ((60 + rnd.nextInt(30)) shl 16) or (80 shl 8) or 150 }
        assertNull(CoverFrame.place(Backdrop.mask(allGarment, w, h), 1000, 1300))
    }
}
