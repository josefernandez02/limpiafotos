package dev.catalogo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a photo goes inside the 3:4 template: [scale] is the photo's width in frame widths,
 * [dx]/[dy] move the photo's centre away from the frame's centre (also in frame widths), and
 * [rotation] turns it (degrees, clockwise) around its centre. Same meaning as the app's Framing.
 */
data class Placement(val scale: Float, val dx: Float, val dy: Float, val rotation: Float)

/** A yes/no picture of where the garment is: [on] has [w] x [h] cells, row by row. */
class Mask(val w: Int, val h: Int, val on: BooleanArray) {
    init { require(on.size == w * h) { "mask size" } }
    fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && on[y * w + x]
    fun count() = on.count { it }
}

/**
 * Automatic framing of a cover photo: from the garment's silhouette, upright it, centre it and give it
 * always the same size inside a safe box, so every cover looks alike whatever the garment is and
 * wherever it was in the photo. Pure maths (no Android): the silhouette comes from the caller.
 */
object CoverFrame {
    const val FRAME_H = 4f / 3f          // frame height in frame widths (3:4)
    const val SAFE_W = 0.88f             // the garment never takes more than this of the frame's width…
    const val SAFE_H = 0.70f             // …or of its height: the square (1:1) crop of Vinted's previews keeps the middle 75% of the frame
    const val LIFT = 0.015f              // garment centre a bit above the middle (the grid's «Destacado» band covers the bottom)
    const val MAX_TILT = 10f             // degrees it will ever straighten
    const val MIN_AREA = 0.08f           // silhouettes smaller or bigger than this share of the photo are not trusted
    const val MAX_AREA = 0.92f
    const val MAX_BORDER = 0.6f          // more than this share of the photo's borders being garment: nothing to frame by
    private const val STEPS = 10         // from the ideal placement to «photo just covers the frame»
    private const val GRID_X = 24        // frame points checked for what the mirrored fill would show
    private const val GRID_Y = 32
    private const val TRIM = 0.002f      // share of the silhouette ignored at each border (stray specks)

    /** The garment's silhouette → where to put the photo; null if the silhouette is not believable. */
    fun place(mask: Mask, imageW: Int, imageH: Int): Placement? {
        val clean = largestParts(mask) ?: return null
        val area = clean.count().toFloat() / (clean.w * clean.h)
        if (area < MIN_AREA || area > MAX_AREA) return null
        if (borderShare(clean) > MAX_BORDER) return null      // the garment fills the photo: nothing to frame by
        val near = dilated(clean, 2)
        val turned = plan(clean, near, imageW, imageH, tilt(clean)) ?: return null
        // when the garment cannot be framed without going beyond the photo, do not also turn it: leave the shot as it is
        if (turned.second > 0.5f && turned.first.rotation != 0f) return plan(clean, near, imageW, imageH, 0f)?.first
        return turned.first
    }

    /**
     * The ideal placement for the given turn (garment centred, always the same size) and, if the photo does not reach
     * the frame, how far it had to be moved towards covering it (0 = ideal, 1 = photo just covers the frame).
     * What the photo does not reach is filled with mirrored backdrop, so the ideal is accepted as long as that mirror
     * shows no garment; otherwise the photo is brought closer to the frame, step by step.
     */
    private fun plan(clean: Mask, near: Mask, imageW: Int, imageH: Int, tilt: Float): Pair<Placement, Float>? {
        val grid = rotated(clean, tilt)
        val box = bounds(grid) ?: return null
        val k = imageW.toFloat() / clean.w                      // image pixels per mask cell
        val bw = (box.x1 - box.x0 + 1) * k
        val bh = (box.y1 - box.y0 + 1) * k
        if (bw < 1f || bh < 1f) return null
        val bcx = ((box.x0 + box.x1 + 1) / 2f - grid.w / 2f) * k
        val bcy = ((box.y0 + box.y1 + 1) / 2f - grid.h / 2f) * k
        val scale = min(SAFE_W * imageW / bw, SAFE_H * FRAME_H * imageW / bh).coerceIn(0.3f, 6f)
        val dx = -bcx * scale / imageW
        val dy = -bcy * scale / imageW - LIFT * FRAME_H
        val cover = coverScale(imageW, imageH, tilt)
        for (i in 0..STEPS) {
            val a = i.toFloat() / STEPS
            val p = Placement((1 - a) * scale + a * cover, (1 - a) * dx, (1 - a) * dy, tilt)
            if (fillIsBackdrop(near, p, imageW, imageH)) return p to a
        }
        return Placement(cover, 0f, 0f, tilt) to 1f
    }

    /** Smallest photo width (in frame widths) with which the photo, turned [tilt], covers the whole frame when centred. */
    fun coverScale(imageW: Int, imageH: Int, tilt: Float): Float {
        val rad = tilt * PI / 180.0
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val xs = 0.5f * c + FRAME_H / 2 * s
        val ys = 0.5f * s + FRAME_H / 2 * c
        return max(2 * xs, 2 * ys * imageW / imageH)
    }

    /** True when every frame point the photo does not reach would show, once mirrored, backdrop and no garment. */
    private fun fillIsBackdrop(near: Mask, p: Placement, imageW: Int, imageH: Int): Boolean {
        val rad = p.rotation * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val ph = p.scale * imageH / imageW
        for (j in 0..GRID_Y) for (i in 0..GRID_X) {
            val px = i.toFloat() / GRID_X - 0.5f - p.dx
            val py = j.toFloat() / GRID_Y * FRAME_H - FRAME_H / 2 - p.dy
            val u = (c * px + s * py) / p.scale + 0.5f
            val v = (-s * px + c * py) / ph + 0.5f
            if (u in 0f..1f && v in 0f..1f) continue
            val x = (mirrored(u) * near.w).toInt().coerceIn(0, near.w - 1)
            val y = (mirrored(v) * near.h).toInt().coerceIn(0, near.h - 1)
            if (near.on[y * near.w + x]) return false
        }
        return true
    }

    /** Where a coordinate outside 0..1 lands when the photo is mirrored again and again (Android's MIRROR tiling). */
    private fun mirrored(f: Float): Float {
        val m = f - 2f * floor(f / 2f)
        return if (m > 1f) 2f - m else m
    }

    /** Share of the photo's outermost cells that are garment. */
    private fun borderShare(m: Mask): Float {
        var on = 0
        var n = 0
        for (x in 0 until m.w) { if (m.on[x]) on++; if (m.on[(m.h - 1) * m.w + x]) on++; n += 2 }
        for (y in 0 until m.h) { if (m.on[y * m.w]) on++; if (m.on[y * m.w + m.w - 1]) on++; n += 2 }
        return on.toFloat() / n
    }

    /** The silhouette grown by [r] cells in every direction. */
    fun dilated(m: Mask, r: Int): Mask {
        var cur = m.on
        repeat(r) {
            val next = cur.copyOf()
            for (y in 0 until m.h) for (x in 0 until m.w) {
                if (cur[y * m.w + x]) continue
                if ((x > 0 && cur[y * m.w + x - 1]) || (x < m.w - 1 && cur[y * m.w + x + 1]) ||
                    (y > 0 && cur[(y - 1) * m.w + x]) || (y < m.h - 1 && cur[(y + 1) * m.w + x])) next[y * m.w + x] = true
            }
            cur = next
        }
        return Mask(m.w, m.h, cur)
    }

    // ---------- silhouette clean-up ----------

    /** Keeps the biggest piece and the pieces of at least a tenth of it (a vest with a gap), drops the specks. */
    fun largestParts(m: Mask): Mask? {
        val label = IntArray(m.w * m.h)
        val sizes = ArrayList<Int>()
        val stack = IntArray(m.w * m.h)
        for (start in m.on.indices) {
            if (!m.on[start] || label[start] != 0) continue
            val id = sizes.size + 1
            var top = 0
            var n = 0
            stack[top++] = start
            label[start] = id
            while (top > 0) {
                val p = stack[--top]
                n++
                val x = p % m.w
                val y = p / m.w
                if (x > 0 && m.on[p - 1] && label[p - 1] == 0) { label[p - 1] = id; stack[top++] = p - 1 }
                if (x < m.w - 1 && m.on[p + 1] && label[p + 1] == 0) { label[p + 1] = id; stack[top++] = p + 1 }
                if (y > 0 && m.on[p - m.w] && label[p - m.w] == 0) { label[p - m.w] = id; stack[top++] = p - m.w }
                if (y < m.h - 1 && m.on[p + m.w] && label[p + m.w] == 0) { label[p + m.w] = id; stack[top++] = p + m.w }
            }
            sizes.add(n)
        }
        val biggest = sizes.maxOrNull() ?: return null
        val keep = BooleanArray(sizes.size + 1) { it > 0 && sizes[it - 1] * 10 >= biggest }
        return Mask(m.w, m.h, BooleanArray(m.on.size) { label[it] > 0 && keep[label[it]] })
    }

    // ---------- straightening ----------

    private class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** The silhouette turned [deg] degrees clockwise about its centre, in a square grid big enough for any turn. */
    fun rotated(m: Mask, deg: Float): Mask {
        val n = ceil(hypot(m.w.toDouble(), m.h.toDouble())).toInt() + 2
        val rad = deg * PI / 180.0
        val c = cos(rad)
        val s = sin(rad)
        val out = BooleanArray(n * n)
        val ox = (m.w - 1) / 2.0
        val oy = (m.h - 1) / 2.0
        val on = (n - 1) / 2.0
        for (v in 0 until n) {
            for (u in 0 until n) {
                // inverse turn: where this cell came from in the original
                val px = u - on
                val py = v - on
                val sx = c * px + s * py + ox
                val sy = -s * px + c * py + oy
                out[v * n + u] = m.at(sx.roundToInt(), sy.roundToInt())
            }
        }
        return Mask(n, n, out)
    }

    private fun bounds(m: Mask): Box? {
        val cols = IntArray(m.w)
        val rows = IntArray(m.h)
        var total = 0
        for (y in 0 until m.h) for (x in 0 until m.w) if (m.on[y * m.w + x]) { cols[x]++; rows[y]++; total++ }
        if (total == 0) return null
        fun edges(counts: IntArray): Pair<Int, Int> {
            val cut = max(1, (total * TRIM).toInt())
            var acc = 0
            var lo = 0
            while (lo < counts.size - 1 && acc + counts[lo] <= cut) { acc += counts[lo]; lo++ }
            acc = 0
            var hi = counts.size - 1
            while (hi > lo && acc + counts[hi] <= cut) { acc += counts[hi]; hi-- }
            return lo to hi
        }
        val (x0, x1) = edges(cols)
        val (y0, y1) = edges(rows)
        return Box(x0, y0, x1, y1)
    }

    /** How alike the silhouette is to its own mirror image (1 = identical), about the middle of its width. */
    fun symmetry(m: Mask): Float {
        val box = bounds(m) ?: return 0f
        val sum = box.x0 + box.x1
        var both = 0
        var either = 0
        for (y in box.y0..box.y1) {
            for (x in box.x0..box.x1) {
                val a = m.on[y * m.w + x]
                val b = m.at(sum - x, y)
                if (a && b) both++
                if (a || b) either++
            }
        }
        return if (either == 0) 0f else both.toFloat() / either
    }

    /**
     * The turn (degrees, clockwise) that makes the garment most symmetric, searched within ±[MAX_TILT].
     * Cautious: it only turns when the gain is clear and the garment really is symmetric once turned;
     * for round or lopsided shapes the answer is 0 (leave the photo as it is).
     */
    fun tilt(m: Mask): Float {
        val here = symmetry(m)
        var best = 0f
        var bestScore = here
        fun tryAngle(a: Float) {
            val score = symmetry(rotated(m, a)) - 0.0005f * abs(a)
            if (score > bestScore) { bestScore = score; best = a }
        }
        var a = -MAX_TILT
        while (a <= MAX_TILT + 1e-3f) { if (abs(a) > 1e-3f) tryAngle(a); a += 1f }
        val coarse = best
        if (coarse != 0f) {
            var f = coarse - 0.75f
            while (f <= coarse + 0.75f + 1e-3f) { tryAngle(f); f += 0.25f }
        }
        return if (best != 0f && bestScore - here >= 0.012f && bestScore >= 0.85f) (best * 4).roundToInt() / 4f else 0f
    }
}

/**
 * A silhouette from the photo alone, for when no segmentation model is at hand. Two colour groups are told apart in the
 * Lab colour space: the backdrop, modelled as a smooth surface (lighting changes across a floor or wall) fitted to the
 * photo's borders, and the garment, the colour that stands out most from it. Each pixel goes to the nearer group and the
 * two models are refined a few rounds. If the garment covers the borders the result is implausible and
 * [CoverFrame.place] refuses it.
 */
object Backdrop {
    private const val TERMS = 6
    private const val ROUNDS = 8
    private val WEIGHT = floatArrayOf(0.6f, 1.6f, 1.6f)   // lightness counts less than colour: shadows are not garment

    /** [argb] is the photo (w x h). */
    fun mask(argb: IntArray, w: Int, h: Int): Mask {
        val n = w * h
        val lab = Array(3) { FloatArray(n) }
        for (i in 0 until n) {
            val c = argb[i]
            toLab(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, lab, i)
        }
        // basis of a quadratic surface in x, y (both in -1..1)
        val basis = Array(n) { i ->
            val x = (i % w) / (max(w - 1, 1).toFloat()) * 2f - 1f
            val y = (i / w) / (max(h - 1, 1).toFloat()) * 2f - 1f
            floatArrayOf(1f, x, y, x * x, x * y, y * y)
        }
        val frame = BooleanArray(n) { val x = it % w; val y = it / w; x < 2 || y < 2 || x >= w - 2 || y >= h - 2 }
        val med = FloatArray(3) { c -> lab[c].filterIndexed { i, _ -> frame[i] }.sorted().let { it[it.size / 2] } }
        fun dist(i: Int, a: Float, b: Float, c: Float): Float {
            val d0 = (lab[0][i] - a) * WEIGHT[0]
            val d1 = (lab[1][i] - b) * WEIGHT[1]
            val d2 = (lab[2][i] - c) * WEIGHT[2]
            return Math.sqrt((d0 * d0 + d1 * d1 + d2 * d2).toDouble()).toFloat()
        }
        val d0 = FloatArray(n) { dist(it, med[0], med[1], med[2]) }
        val borderD = (0 until n).filter { frame[it] }.map { d0[it] }.sorted()
        val cut = max(6f, 2.5f * borderD[(borderD.size * 0.4f).toInt()])
        val strong = d0.sorted()[(n * 0.85f).toInt().coerceAtMost(n - 1)]
        var floor = BooleanArray(n) { frame[it] && d0[it] < cut }
        var garment = BooleanArray(n) { d0[it] > strong }
        repeat(ROUNDS) {
            val coef = fit(basis, lab, floor) ?: return Mask(w, h, BooleanArray(n))
            val gc = FloatArray(3)
            var gn = 0
            for (i in 0 until n) if (garment[i]) { for (c in 0 until 3) gc[c] += lab[c][i]; gn++ }
            if (gn < 20) return@repeat
            for (c in 0 until 3) gc[c] /= gn
            val next = BooleanArray(n)
            var on = 0
            for (i in 0 until n) {
                val p = FloatArray(3) { c -> var s = 0.0; for (k in 0 until TERMS) s += coef[c][k] * basis[i][k]; s.toFloat() }
                next[i] = dist(i, gc[0], gc[1], gc[2]) < dist(i, p[0], p[1], p[2])
                if (next[i]) on++
            }
            if (on < 20 || n - on < 50) return@repeat
            garment = next
            floor = BooleanArray(n) { !next[it] }
        }
        // the two groups must really differ, otherwise there is no garment to speak of (texture noise split in two)
        val coef = fit(basis, lab, floor) ?: return Mask(w, h, BooleanArray(n))
        val gap = FloatArray(n) { i ->
            val p = FloatArray(3) { c -> var t = 0.0; for (k in 0 until TERMS) t += coef[c][k] * basis[i][k]; t.toFloat() }
            dist(i, p[0], p[1], p[2])
        }
        val floorGap = (0 until n).filter { !garment[it] }.map { gap[it] }.sorted()
        val garmentGap = (0 until n).filter { garment[it] }.map { gap[it] }.sorted()
        if (floorGap.isEmpty() || garmentGap.isEmpty()) return Mask(w, h, BooleanArray(n))
        if (garmentGap[garmentGap.size / 2] < max(10f, 3f * floorGap[floorGap.size * 9 / 10])) return Mask(w, h, BooleanArray(n))
        var cur = garment
        // two passes of majority vote smooth the texture and fill pin-holes
        repeat(2) {
            val next = BooleanArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                var on = 0
                var tot = 0
                for (j in -1..1) for (i in -1..1) {
                    val xx = x + i
                    val yy = y + j
                    if (xx in 0 until w && yy in 0 until h) { tot++; if (cur[yy * w + xx]) on++ }
                }
                next[y * w + x] = on * 2 > tot
            }
            cur = next
        }
        return fillHoles(Mask(w, h, cur))
    }

    /** Cells not reachable from the border without crossing the garment (a pattern, a gap between arms) become garment. */
    private fun fillHoles(m: Mask): Mask {
        val outside = BooleanArray(m.on.size)
        val stack = IntArray(m.on.size)
        var top = 0
        fun push(x: Int, y: Int) {
            val p = y * m.w + x
            if (!m.on[p] && !outside[p]) { outside[p] = true; stack[top++] = p }
        }
        for (x in 0 until m.w) { push(x, 0); push(x, m.h - 1) }
        for (y in 0 until m.h) { push(0, y); push(m.w - 1, y) }
        while (top > 0) {
            val p = stack[--top]
            val x = p % m.w
            val y = p / m.w
            if (x > 0) push(x - 1, y)
            if (x < m.w - 1) push(x + 1, y)
            if (y > 0) push(x, y - 1)
            if (y < m.h - 1) push(x, y + 1)
        }
        return Mask(m.w, m.h, BooleanArray(m.on.size) { !outside[it] })
    }

    private fun toLab(r: Float, g: Float, b: Float, out: Array<FloatArray>, i: Int) {
        fun lin(c: Float) = if (c > 0.04045f) Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else c / 12.92f
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (0.4124f * rl + 0.3576f * gl + 0.1805f * bl) / 0.95047f
        val y = 0.2126f * rl + 0.7152f * gl + 0.0722f * bl
        val z = (0.0193f * rl + 0.1192f * gl + 0.9505f * bl) / 1.08883f
        fun f(t: Float) = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
        out[0][i] = 116f * f(y) - 16f
        out[1][i] = 500f * (f(x) - f(y))
        out[2][i] = 200f * (f(y) - f(z))
    }

    /** Least squares of each channel on the basis, over the pixels marked in [use]; null if degenerate. */
    private fun fit(basis: Array<FloatArray>, ch: Array<FloatArray>, use: BooleanArray): Array<DoubleArray>? {
        val ata = Array(TERMS) { DoubleArray(TERMS + 3) }          // [A^T A | A^T p0 p1 p2]
        var used = 0
        for (i in basis.indices) {
            if (!use[i]) continue
            used++
            val b = basis[i]
            for (r in 0 until TERMS) {
                for (c in 0 until TERMS) ata[r][c] += (b[r] * b[c]).toDouble()
                for (c in 0 until 3) ata[r][TERMS + c] += (b[r] * ch[c][i]).toDouble()
            }
        }
        if (used < 4 * TERMS) return null
        for (col in 0 until TERMS) {                                // Gauss-Jordan with partial pivot
            var piv = col
            for (r in col + 1 until TERMS) if (Math.abs(ata[r][col]) > Math.abs(ata[piv][col])) piv = r
            if (Math.abs(ata[piv][col]) < 1e-9) return null
            val tmp = ata[col]; ata[col] = ata[piv]; ata[piv] = tmp
            val d = ata[col][col]
            for (c in 0 until TERMS + 3) ata[col][c] /= d
            for (r in 0 until TERMS) if (r != col) {
                val f = ata[r][col]
                if (f != 0.0) for (c in 0 until TERMS + 3) ata[r][c] -= f * ata[col][c]
            }
        }
        return Array(3) { c -> DoubleArray(TERMS) { k -> ata[k][TERMS + c] } }
    }
}
