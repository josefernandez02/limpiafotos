package dev.catalogo.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A silhouette from the photo alone, for when no segmentation model is at hand. The backdrop is modelled as a smooth
 * surface (lighting changes across a floor or wall) fitted to the photo's borders in the Lab colour space; the garment is
 * whatever deviates from it (any colour, patterned or not) plus, as a second opinion, the colour group that stands out
 * from it (a garment as pale or as grey as the floor). If the garment covers the borders the result is implausible and
 * [CoverFrame.place] refuses it.
 */
object Backdrop {
    private const val TERMS = 6
    private const val ROUNDS = 8
    private val WEIGHT = floatArrayOf(0.6f, 1.6f, 1.6f)   // lightness counts less than colour: shadows are not garment

    /** [argb] is the photo (w x h). */
    fun mask(argb: IntArray, w: Int, h: Int): Mask {
        val n = w * h
        val empty = Mask(w, h, BooleanArray(n))
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
        val ring = BooleanArray(n) { val x = it % w; val y = it / w; x < 3 || y < 3 || x >= w - 3 || y >= h - 3 }
        val med = FloatArray(3) { c -> lab[c].filterIndexed { i, _ -> ring[i] }.sorted().let { it[it.size / 2] } }
        fun dist(i: Int, a: Float, b: Float, c: Float): Float {
            val d0 = (lab[0][i] - a) * WEIGHT[0]
            val d1 = (lab[1][i] - b) * WEIGHT[1]
            val d2 = (lab[2][i] - c) * WEIGHT[2]
            return Math.sqrt((d0 * d0 + d1 * d1 + d2 * d2).toDouble()).toFloat()
        }
        fun surface(coef: Array<DoubleArray>, i: Int) = FloatArray(3) { c ->
            var s = 0.0; for (k in 0 until TERMS) s += coef[c][k] * basis[i][k]; s.toFloat()
        }
        fun median(v: List<Float>) = v.sorted().let { it[it.size / 2] }
        val d0 = FloatArray(n) { dist(it, med[0], med[1], med[2]) }
        val ringD = (0 until n).filter { ring[it] }.map { d0[it] }.sorted()
        val cut = max(6f, 2.5f * ringD[(ringD.size * 0.6f).toInt()])

        // (1) deviation from the backdrop surface fitted to the photo's borders: finds any garment that differs
        //     from the floor, patterned or not
        var floor = BooleanArray(n) { ring[it] && d0[it] < cut }
        var sigma = 0f
        var gap = FloatArray(n)
        repeat(4) {
            val coef = fit(basis, lab, floor) ?: return empty
            gap = FloatArray(n) { i -> val p = surface(coef, i); dist(i, p[0], p[1], p[2]) }
            val r = (0 until n).filter { ring[it] }.map { gap[it] }
            val m = median(r)
            sigma = 1.4826f * median(r.map { abs(it - m) }) + m
            floor = BooleanArray(n) { ring[it] && gap[it] < max(8f, 3f * sigma) }
        }
        val deviates = BooleanArray(n) { gap[it] > max(9f, 4f * sigma) }

        // (2) two colour groups, floor surface against garment colour: finds a garment as pale or as grey as the floor
        val strong = d0.sorted()[(n * 0.85f).toInt().coerceAtMost(n - 1)]
        var fl2 = BooleanArray(n) { ring[it] && d0[it] < cut }
        var garment = BooleanArray(n) { d0[it] > strong }
        repeat(ROUNDS) {
            val coef = fit(basis, lab, fl2) ?: return@repeat
            val gc = FloatArray(3)
            var gn = 0
            for (i in 0 until n) if (garment[i]) { for (c in 0 until 3) gc[c] += lab[c][i]; gn++ }
            if (gn < 20) return@repeat
            for (c in 0 until 3) gc[c] /= gn
            val next = BooleanArray(n)
            var on = 0
            for (i in 0 until n) {
                val p = surface(coef, i)
                next[i] = dist(i, gc[0], gc[1], gc[2]) < dist(i, p[0], p[1], p[2])
                if (next[i]) on++
            }
            if (on < 20 || n - on < 50) return@repeat
            garment = next
            fl2 = BooleanArray(n) { !next[it] }
        }
        // that second opinion only counts if its two groups really differ (else it is texture noise split in two)
        val coef2 = fit(basis, lab, fl2)
        var separated = false
        if (coef2 != null) {
            val g = (0 until n).filter { garment[it] }.map { i -> val p = surface(coef2, i); dist(i, p[0], p[1], p[2]) }
            val f = (0 until n).filter { !garment[it] }.map { i -> val p = surface(coef2, i); dist(i, p[0], p[1], p[2]) }
            separated = g.isNotEmpty() && f.isNotEmpty() && median(g) >= max(10f, 3f * f.sorted()[f.size * 9 / 10])
        }
        val union = BooleanArray(n) { deviates[it] || (separated && garment[it]) }
        if (union.count { it } < n / 50) return empty
        var cur = union
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
        // closing: a pattern (spots, diamonds) must not leave the garment in pieces
        return fillHoles(CoverFrame.eroded(CoverFrame.dilated(Mask(w, h, cur), 3), 3))
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
