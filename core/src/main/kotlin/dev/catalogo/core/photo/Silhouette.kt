package dev.catalogo.core.photo

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

/** Operations on the garment's silhouette: clean-up, size and the turn that puts it straight. */
object Silhouette {
    const val MAX_TILT = 12f             // degrees it will ever straighten
    private const val MIN_TURN = 1.5f    // it never turns less than this: a degree or so is measuring noise, and would only make a straight garment crooked…
    private const val MIN_GAIN = 1.25f   // …nor unless the turn makes the outline this many times straighter than it is…
    private const val MAX_DISAGREE = 3f  // …nor when the two ways of reading the outline differ by more than this many degrees
    private const val NEAR_BORDER = 5    // cells from the photo's edge that still count as touching it
    private const val OPEN = 4            // cells (of a 128-cell grid) the opening uses to tell floor stains from the garment
    private const val TRIM = 0.002f      // share of the silhouette ignored at each border (stray specks)

    /** Share of [small] that lies inside [big] (same grid): 1 = [small] is entirely within [big]. */
    fun containment(big: Mask, small: Mask): Float {
        if (big.w != small.w || big.h != small.h) return 0f
        var inside = 0
        var total = 0
        for (i in small.on.indices) if (small.on[i]) { total++; if (big.on[i]) inside++ }
        return if (total == 0) 0f else inside.toFloat() / total
    }

    /** How much two silhouettes (same grid) agree: shared area over joint area (1 = identical). */
    fun agreement(a: Mask, b: Mask): Float {
        if (a.w != b.w || a.h != b.h) return 0f
        var both = 0
        var either = 0
        for (i in a.on.indices) { if (a.on[i] && b.on[i]) both++; if (a.on[i] || b.on[i]) either++ }
        return if (either == 0) 0f else both.toFloat() / either
    }

    /** Share of the photo's outermost cells that are garment. */
    internal fun borderShare(m: Mask): Float {
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

    /** Labels the connected pieces of the silhouette (1, 2, …; 0 = off) and gives each one's size. */
    private fun pieces(m: Mask): Pair<IntArray, List<Int>> {
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
        return label to sizes
    }

    /**
     * A piece that touches the photo's border while the main one does not is floor (the shadow in a corner, the edge of a
     * rug or a wall), not garment: it is cut away, even when a thin neck joins it to the garment. When the main piece
     * itself reaches the border the photo cuts the garment and nothing is removed.
     */
    private fun withoutBorderPieces(m: Mask): Mask {
        val opened = dilated(eroded(m, OPEN), OPEN)             // the opening severs necks thinner than ~2·OPEN cells (legs and sleeves are wider)
        val (label, sizes) = pieces(opened)
        if (sizes.isEmpty()) return m
        val main = sizes.indices.maxByOrNull { sizes[it] }!! + 1
        val touches = BooleanArray(sizes.size + 1)
        fun mark(x: Int, y: Int) { val l = label[y * m.w + x]; if (l > 0) touches[l] = true }
        for (d in 0 until NEAR_BORDER) {                       // «touching» allows for a thin frame around the photo
            for (x in 0 until m.w) { mark(x, d); mark(x, m.h - 1 - d) }
            for (y in 0 until m.h) { mark(d, y); mark(m.w - 1 - d, y) }
        }
        if (touches[main] || (1..sizes.size).none { touches[it] && it != main }) return m
        val mainPart = dilated(Mask(m.w, m.h, BooleanArray(m.on.size) { label[it] == main }), OPEN + 1)
        val floor = dilated(Mask(m.w, m.h, BooleanArray(m.on.size) { label[it] > 0 && label[it] != main && touches[label[it]] }), OPEN + 1)
        return Mask(m.w, m.h, BooleanArray(m.on.size) { m.on[it] && !(floor.on[it] && !mainPart.on[it]) })
    }

    /** Keeps the biggest piece and the pieces of at least a tenth of it (a vest with a gap), drops the specks and the floor. */
    fun largestParts(whole: Mask): Mask? {
        val m = withoutBorderPieces(whole)
        val (label, sizes) = pieces(m)
        val biggest = sizes.maxOrNull() ?: return null
        val keep = BooleanArray(sizes.size + 1) { it > 0 && sizes[it - 1] * 10 >= biggest }
        return Mask(m.w, m.h, BooleanArray(m.on.size) { label[it] > 0 && keep[label[it]] })
    }

    /** The silhouette shrunk by [r] cells (the photo's own border does not eat into it). */
    fun eroded(m: Mask, r: Int): Mask {
        var cur = m.on
        repeat(r) {
            val next = cur.copyOf()
            for (y in 0 until m.h) for (x in 0 until m.w) {
                if (!cur[y * m.w + x]) continue
                if ((x > 0 && !cur[y * m.w + x - 1]) || (x < m.w - 1 && !cur[y * m.w + x + 1]) ||
                    (y > 0 && !cur[(y - 1) * m.w + x]) || (y < m.h - 1 && !cur[(y + 1) * m.w + x])) next[y * m.w + x] = false
            }
            cur = next
        }
        return Mask(m.w, m.h, cur)
    }

    // ---------- straightening ----------

    internal class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

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

    internal fun bounds(m: Mask): Box? {
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

    /** The cells on the silhouette's outline (a photo's edge is not the garment's edge), as their centres. */
    private fun outlinePoints(m: Mask): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        fun on(x: Int, y: Int) = x < 0 || y < 0 || x >= m.w || y >= m.h || m.on[y * m.w + x]
        for (y in 0 until m.h) for (x in 0 until m.w) {
            if (!m.on[y * m.w + x]) continue
            if (!on(x - 1, y) || !on(x + 1, y) || !on(x, y - 1) || !on(x, y + 1)) { xs.add(x + 0.5f); ys.add(y + 0.5f) }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    /**
     * How well the outline lines up with a pair of perpendicular axes turned by [deg]: the outline cells are projected on each
     * axis, and cells of one straight edge fall in the same bin, so the sum of squares of the bin counts is big when edges are
     * straight along that direction. The two axes count the same (geometric mean), so a single long edge that happens to
     * be slanted, like the splayed leg of a pair of trousers, does not pass for the garment's own «straight».
     */
    private fun straightness(xs: FloatArray, ys: FloatArray, deg: Float): Double {
        val rad = deg * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        var product = 1.0
        for (axis in 0..1) {
            val rho = FloatArray(xs.size) { if (axis == 0) xs[it] * c + ys[it] * s else -xs[it] * s + ys[it] * c }
            val lo = rho.min()
            val bins = IntArray(ceil(rho.max() - lo).toInt() + 2)
            for (r in rho) bins[(r - lo).toInt()]++
            var sum = 0.0
            for (n in bins) sum += n.toDouble() * n
            product *= sum
        }
        return Math.sqrt(product)
    }

    /** Area of the smallest rectangle, with sides along the axes turned by [deg], that holds the whole outline. */
    private fun boxArea(xs: FloatArray, ys: FloatArray, deg: Float): Double {
        val rad = deg * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in xs.indices) {
            val u = xs[i] * c + ys[i] * s
            val v = -xs[i] * s + ys[i] * c
            x0 = min(x0, u); x1 = max(x1, u); y0 = min(y0, v); y1 = max(y1, v)
        }
        return (x1 - x0 + 1.0) * (y1 - y0 + 1.0)
    }

    /**
     * The turn (degrees, clockwise) that puts the garment's own outline straight, searched within ±[MAX_TILT].
     * It looks at the silhouette only, never at what is printed or sewn on it (a label, a pocket), and not at how
     * symmetric it is: a folded garment is lopsided, and a pair of trousers with one leg splayed is not symmetric
     * either, yet both have a clear «straight». Two independent readings of the outline have to agree before it turns:
     *  - the turn at which most of the outline runs in straight edges along the photo's axes (waistband, hems and sides
     *    of trousers, folds of a folded garment, hem and sides of a jumper), and
     *  - the turn at which the smallest rectangle that holds the garment is the tightest.
     * Cautious: a garment whose edges are already straight, one whose edges are not clear (round or lopsided shapes), or
     * one on which the two readings disagree is left as it is.
     */
    fun tilt(m: Mask): Float {
        val (xs, ys) = outlinePoints(m)
        if (xs.size < 12) return 0f
        val here = straightness(xs, ys, 0f)
        val boxHere = boxArea(xs, ys, 0f)
        if (here <= 0.0) return 0f
        var edges = 0f
        var edgesScore = here
        var box = 0f
        var boxScore = boxHere
        var c = -MAX_TILT
        while (c <= MAX_TILT + 1e-3f) {
            val e = straightness(xs, ys, -c) * (1.0 - 0.0005 * abs(c))      // between equals, the smaller turn
            if (e > edgesScore) { edgesScore = e; edges = c }
            val b = boxArea(xs, ys, -c) * (1.0 + 0.0005 * abs(c))
            if (b < boxScore) { boxScore = b; box = c }
            c += 0.25f
        }
        if (edges == 0f || edgesScore / here < MIN_GAIN) return 0f
        if (abs(edges - box) > MAX_DISAGREE) return 0f
        val turn = ((edges + box) / 2 * 4).roundToInt() / 4f
        return if (abs(turn) >= MIN_TURN) turn else 0f
    }
}
