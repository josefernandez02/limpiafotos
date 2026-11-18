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
        assertEquals(1f, max(gw / CoverFrame.SAFE_W, gh / (CoverFrame.SAFE_H * CoverFrame.FRAME_H)), 0.07f)   // up to 6% bigger is allowed to avoid any fill
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

    @Test
    fun `a garment that fits is framed ideally and the rest is filled with mirrored backdrop`() {
        // small garment in the middle of a big photo: the ideal size is bigger than the photo, but what is missing is backdrop
        val p = assertNotNull(CoverFrame.place(jumper(120, 140, s = 1), 3000, 3500))
        assertTrue(p.scale >= CoverFrame.coverScale(3000, 3500, p.rotation) - 0.001f)
    }

    @Test
    fun `a garment that touches the photo's edge is not zoomed out so no garment is mirrored`() {
        val w = 120
        val h = 160
        // a garment running off the bottom of the photo: mirroring the bottom edge would show a second garment
        val on = BooleanArray(w * h) { val x = it % w; val y = it / w; x in 30..89 && y >= 50 }
        val p = assertNotNull(CoverFrame.place(Mask(w, h, on), 3000, 4000))
        assertEquals(CoverFrame.coverScale(3000, 4000, p.rotation), p.scale, 0.001f)
        assertEquals(0f, p.dx, 0.001f)
        assertEquals(0f, p.dy, 0.001f)
    }

    @Test
    fun `the safe box leaves the same space above and below`() {
        val b = CoverFrame.safeBox()
        assertEquals(b[1], CoverFrame.FRAME_H - b[3], 0.001f)
        assertEquals(0f, CoverFrame.LIFT, 0.001f)
    }

    @Test
    fun `noFill grows a placement with a gap until the photo covers the frame, and leaves a covering one alone`() {
        val covering = Placement(1.2f, 0.02f, 0.02f, 0f)
        assertEquals(covering, CoverFrame.noFill(covering, 3000, 4000))
        val gap = Placement(0.9f, 0f, 0.05f, 0f)
        assertTrue(CoverFrame.uncoveredShare(gap, 3000, 4000) > 0f)
        val fixed = CoverFrame.noFill(gap, 3000, 4000)
        assertEquals(0f, CoverFrame.uncoveredShare(fixed, 3000, 4000), 0f)
        assertTrue(fixed.scale >= gap.scale && fixed.scale < 1.3f, "scale ${fixed.scale}")
        val hopeless = CoverFrame.noFill(Placement(0.5f, 3f, 3f, 0f), 3000, 4000)
        assertEquals(0f, CoverFrame.uncoveredShare(hopeless, 3000, 4000), 0f)
    }

    @Test
    fun `cover scale never leaves a gap`() {
        assertEquals(1f, CoverFrame.coverScale(3000, 4000, 0f), 0.001f)                  // 3:4 photo = frame
        assertTrue(CoverFrame.coverScale(3000, 4000, 5f) > 1f)                           // turned: needs a little more
        assertTrue(CoverFrame.coverScale(4000, 3000, 0f) > 1.7f)                         // landscape photo must grow to fill the height
    }

    @Test
    fun `the backdrop silhouette holds a patterned garment together even where the pattern matches the floor`() {
        val w = 120
        val h = 160
        val rnd = java.util.Random(11)
        val photo = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val tex = rnd.nextInt(11) - 5
            val light = 130 + 40 * x / w + 20 * (h - y) / h
            val inside = x in 30..89 && y in 40..119
            val spot = inside && ((x / 3 + y / 3) % 2 == 0)                      // black spots; between them, floor-coloured wool
            val v = if (spot) 25 + tex else light + tex
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val b = box(CoverFrame.largestParts(Backdrop.mask(photo, w, h))!!)
        assertTrue(abs(b[0] - 30) <= 4 && abs(b[2] - 89) <= 4 && abs(b[1] - 40) <= 4 && abs(b[3] - 119) <= 4, "box ${b.toList()}")
    }

    @Test
    fun `the framing leaves no gap, or at most a tiny one, whatever the photo`() {
        // a garment filling different parts of a few photo shapes: never a big hole to invent
        for ((w, h) in listOf(3000 to 4000, 3000 to 3000, 4000 to 3000)) for (sx in listOf(-12, 0, 12)) {
            val m = jumper(120, 140, shiftX = sx)
            val p = assertNotNull(CoverFrame.place(m, w, h), "$w x $h shift $sx")
            assertTrue(CoverFrame.uncoveredShare(p, w, h) <= 0.07f, "$w x $h shift $sx: ${CoverFrame.uncoveredShare(p, w, h)}")
        }
    }

    @Test
    fun `a photo that covers the frame reports no uncovered share`() {
        val cover = Placement(CoverFrame.coverScale(3000, 4000, 0f), 0f, 0f, 0f)
        assertEquals(0f, CoverFrame.uncoveredShare(cover, 3000, 4000), 0.0001f)
        val zoomedOut = Placement(0.5f, 0f, 0f, 0f)
        assertTrue(CoverFrame.uncoveredShare(zoomedOut, 3000, 4000) > 0.3f)
    }

    /** Trousers seen from above: two long legs joined at a waistband; much taller than wide. */
    private fun trousers(w: Int, h: Int, top: Int, bottom: Int): Mask {
        val on = BooleanArray(w * h)
        val cx = w / 2
        for (y in top..bottom) for (x in 0 until w) {
            val waist = y < top + (bottom - top) / 5
            val dx = abs(x - cx)
            on[y * w + x] = if (waist) dx <= 22 else dx in 2..22
        }
        return Mask(w, h, on)
    }

    @Test
    fun `tall trousers in a photo with little margin are framed inside the safe box, not left as they are`() {
        // the garment takes ~73% of the photo's height, as in a typical flat-lay of trousers
        val m = trousers(120, 160, top = 22, bottom = 138)
        val p = assertNotNull(CoverFrame.place(m, 3000, 4000))
        assertTrue(!p.tight, "must not give up and keep the photo as it is")
        assertTrue(p.scale < CoverFrame.coverScale(3000, 4000, 0f) - 0.02f, "the photo is moved away a little: scale ${p.scale}")
        assertTrue(CoverFrame.fillDepth(p, 3000, 4000) <= 0.056f, "the strip to fill is thin: ${CoverFrame.fillDepth(p, 3000, 4000)}")
        // the garment's bottom stays above the label band of the 4:5 preview (and the top below the crop of the 2:3 one)
        val b = box(m)
        val k = 3000f / m.w
        val gh = (b[3] - b[1] + 1) * k * p.scale / 3000
        assertTrue(gh <= CoverFrame.SAFE_H * CoverFrame.FRAME_H * 1.04f, "height $gh")   // up to ~3% over: still inside the safe margin
    }

    @Test
    fun `the fill depth is zero when the photo covers the frame and grows as it is moved away`() {
        val cover = Placement(CoverFrame.coverScale(3000, 4000, 0f), 0f, 0f, 0f)
        assertEquals(0f, CoverFrame.fillDepth(cover, 3000, 4000), 0.001f)
        assertEquals(0.05f, CoverFrame.fillDepth(Placement(0.9f, 0f, 0f, 0f), 3000, 4000), 0.01f)
    }

    @Test
    fun `a shadow in a corner joined to the garment by a thin neck is not part of it`() {
        val w = 120
        val h = 160
        val m = trousers(w, h, top = 40, bottom = 140)
        val on = m.on.copyOf()
        for (y in 0 until 22) for (x in 0 until 45) on[y * w + x] = true            // corner blob touching the border
        for (i in 0 until 20) on[(22 + i) * w + 44 + i / 2] = true                   // a 1-cell neck down to the waistband
        val clean = assertNotNull(CoverFrame.largestParts(Mask(w, h, on)))
        assertTrue((0 until 22).none { y -> (0 until 45).any { x -> clean.on[y * w + x] } }, "the corner is gone")
        assertTrue(clean.on[90 * w + 40], "the garment stays")
        assertTrue(box(clean)[1] >= 38, "its box starts at the garment's top")
    }

    @Test
    fun `a garment cut by the photo's own edge keeps the piece that touches the border`() {
        val w = 120
        val h = 160
        val on = BooleanArray(w * h) { i -> i % w in 30..90 && i / w >= 60 }            // runs off the bottom edge
        val clean = assertNotNull(CoverFrame.largestParts(Mask(w, h, on)))
        assertEquals(on.count { it }, clean.count())
    }

    /** The silhouette ML Kit gave on the phone for a pair of grey trousers (3:4 photo, they fill 17%..90% of its height). */
    private val phoneTrousers = listOf(
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "..........############..........",
        "..........#############.........",
        ".........##############.........",
        ".........##############.........",
        ".........##############.........",
        ".........##############.........",
        "........###############.........",
        "........###############.........",
        "........###############.........",
        "........################........",
        "........################........",
        "........################........",
        "........################........",
        "........#######.########........",
        "........#######.#######.........",
        ".......########.#######.........",
        ".......########..######.........",
        "........######...######.........",
        "........######..#######.........",
        "........######..#######.........",
        "........######..######..........",
        "........#######.######..........",
        "........#######.######..........",
        "........##############..........",
        "........##############..........",
        "........#############...........",
        ".........############...........",
        ".........############...........",
        "..........##########............",
        "..........###...####............",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................"
    )

    @Test
    fun `the trousers silhouette measured on the phone is framed, not left as it is`() {
        val on = BooleanArray(96 * 128) { phoneTrousers.getOrNull(it / 96 / 3)?.getOrNull(it % 96 / 3) == '#' }
        for (grow in 0..2) {      // the real mask is a cell or so bigger than this coarse copy of it
            val m = CoverFrame.dilated(Mask(96, 128, on), grow)
            val p = assertNotNull(CoverFrame.place(m, 480, 640), "grow $grow")
            assertTrue(!p.tight, "grow $grow must not be left as it is")
            assertTrue(CoverFrame.fillDepth(p, 480, 640) <= 0.091f, "grow $grow depth ${CoverFrame.fillDepth(p, 480, 640)}")
        }
    }

    @Test
    fun `a garment that sits off-centre in the photo still gets the same space above and below`() {
        // the garment is high, centred or low in the photo: the cover must show the same margin on both sides each time
        for (shiftY in intArrayOf(0, 6, 12, 18)) {
            val m = jumper(120, 140, shiftY = shiftY)
            val p = assertNotNull(CoverFrame.place(m, 3000, 3500))
            val g = assertNotNull(CoverFrame.margins(m, 3000, p, 1536))
            assertTrue(abs(g[0] - g[1]) <= 6, "shiftY $shiftY: above ${g[0]} px, below ${g[1]} px")
            assertTrue(abs(g[2] - g[3]) <= 6, "shiftY $shiftY: left ${g[2]} px, right ${g[3]} px")
        }
    }

    @Test
    fun `margins are measured in pixels of the cover and centered puts the garment in the middle`() {
        val m = jumper(120, 140)
        val off = Placement(1.3f, 0.07f, -0.05f, 0f)
        val before = assertNotNull(CoverFrame.margins(m, 3000, off, 1536))
        assertTrue(abs(before[0] - before[1]) > 20 && abs(before[2] - before[3]) > 20, "starts off-centre: ${before.toList()}")
        val c = assertNotNull(CoverFrame.centered(m, 3000, off))
        val after = assertNotNull(CoverFrame.margins(m, 3000, c, 1536))
        assertTrue(abs(after[0] - after[1]) <= 2 && abs(after[2] - after[3]) <= 2, "centred: ${after.toList()}")
        // a bigger garment leaves less room
        val big = assertNotNull(CoverFrame.margins(m, 3000, c.copy(scale = c.scale * 1.2f), 1536))
        assertTrue(big[0] < after[0] && big[2] < after[2])
    }
}
