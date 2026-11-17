package dev.catalogo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
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
    const val SAFE_W = 0.90f             // the garment never takes more than this of the frame's width…
    const val SAFE_H = 0.82f             // …or of its height (so the 4:5 grid crop of Vinted never cuts it)
    const val LIFT = 0.025f              // garment centre a bit above the middle (the grid's «Destacado» band covers the bottom)
    const val MAX_TILT = 10f             // degrees it will ever straighten
    const val MIN_AREA = 0.08f           // silhouettes smaller or bigger than this share of the photo are not trusted
    const val MAX_AREA = 0.92f
    private const val TRIM = 0.002f      // share of the silhouette ignored at each border (stray specks)

    /** The garment's silhouette → where to put the photo; null if the silhouette is not believable. */
    fun place(mask: Mask, imageW: Int, imageH: Int): Placement? {
        val clean = largestParts(mask) ?: return null
        val area = clean.count().toFloat() / (clean.w * clean.h)
        if (area < MIN_AREA || area > MAX_AREA) return null
        val tilt = tilt(clean)
        val grid = rotated(clean, tilt)
        val box = bounds(grid) ?: return null
        val k = imageW.toFloat() / mask.w                       // image pixels per mask cell
        val bw = (box.x1 - box.x0 + 1) * k
        val bh = (box.y1 - box.y0 + 1) * k
        if (bw < 1f || bh < 1f) return null
        val bcx = ((box.x0 + box.x1 + 1) / 2f - grid.w / 2f) * k
        val bcy = ((box.y0 + box.y1 + 1) / 2f - grid.h / 2f) * k
        val scale = min(SAFE_W * imageW / bw, SAFE_H * FRAME_H * imageW / bh).coerceIn(0.3f, 6f)
        return Placement(
            scale = scale,
            dx = -bcx * scale / imageW,
            dy = -bcy * scale / imageW - LIFT * FRAME_H,
            rotation = tilt,
        )
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
        return if (best != 0f && bestScore - here >= 0.012f && bestScore >= 0.80f) (best * 4).roundToInt() / 4f else 0f
    }
}

/**
 * A silhouette from the photo alone, for when no segmentation model is at hand. The backdrop is modelled as a smooth
 * surface (lighting changes across a floor or wall), fitted to the photo's borders and refined on every pixel that
 * agrees with it; the garment is whatever differs clearly from that surface. If the garment covers the borders
 * the fit is wrong and the silhouette comes out implausible: [CoverFrame.place] then refuses it.
 */
object Backdrop {
    private const val TERMS = 6
    private const val ROUNDS = 6

    /** [argb] is the photo (w x h). */
    fun mask(argb: IntArray, w: Int, h: Int): Mask {
        val n = w * h
        val ch = Array(3) { c -> FloatArray(n) { ((argb[it] shr (16 - 8 * c)) and 0xFF).toFloat() } }
        // basis of a quadratic surface in x, y (both in -1..1)
        val basis = Array(n) { i ->
            val x = (i % w) / (max(w - 1, 1).toFloat()) * 2f - 1f
            val y = (i / w) / (max(h - 1, 1).toFloat()) * 2f - 1f
            floatArrayOf(1f, x, y, x * x, x * y, y * y)
        }
        // start: the frame of the photo, minus what is far from its median colour
        val frame = BooleanArray(n) { val x = it % w; val y = it / w; x < 2 || y < 2 || x >= w - 2 || y >= h - 2 }
        val med = FloatArray(3) { c -> ch[c].filterIndexed { i, _ -> frame[i] }.sorted().let { it[it.size / 2] } }
        fun flat(i: Int) = hypot(hypot((ch[0][i] - med[0]).toDouble(), (ch[1][i] - med[1]).toDouble()), (ch[2][i] - med[2]).toDouble()).toFloat()
        val d0 = (0 until n).filter { frame[it] }.map { flat(it) }.sorted()
        val cut = max(24f, 3f * d0[(d0.size * 0.35f).toInt()])
        var bg = BooleanArray(n) { frame[it] && flat(it) < cut }
        var res = FloatArray(n)
        var thr = cut
        repeat(ROUNDS) {
            val coef = fit(basis, ch, bg) ?: return Mask(w, h, BooleanArray(n))
            var sum = 0.0
            var cnt = 0
            for (i in 0 until n) {
                var s = 0.0
                for (c in 0 until 3) {
                    var pred = 0.0
                    for (k in 0 until TERMS) pred += coef[c][k] * basis[i][k]
                    val d = ch[c][i] - pred
                    s += d * d
                }
                res[i] = Math.sqrt(s).toFloat()
                if (bg[i]) { sum += s; cnt++ }
            }
            thr = max(20f, 2.6f * Math.sqrt(sum / max(cnt, 1)).toFloat())
            bg = BooleanArray(n) { res[it] < thr }
        }
        var cur = BooleanArray(n) { !bg[it] }
        // two passes of majority vote smooth the texture of the backdrop and fill pin-holes
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
        return Mask(w, h, cur)
    }

    /** Least squares of each colour channel on the basis, over the pixels marked in [use]; null if degenerate. */
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
