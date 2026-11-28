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
 * Automatic framing of a cover photo: from the garment's silhouette, upright it, centre it and give it
 * always the same size inside a safe box, so every cover looks alike whatever the garment is and
 * wherever it was in the photo. Pure maths (no Android): the silhouette comes from the caller.
 */
object CoverFrame {
    const val FRAME_H = 4f / 3f          // frame height in frame widths (3:4)
    private const val PAD = 0.03f        // breathing room between the garment and the edge of any preview, in frame widths

    /**
     * The ways Vinted shows a cover, as (width/height, share of the bottom hidden by something): item page 3:4, phone grid
     * 4:5, desktop grid 2:3, square 1:1. Nothing is reserved at the bottom: the «Destacar» button and the visit counts are
     * seen only by the owner, so the garment is centred, with the same space above and below, for everybody. A preview cuts the 3:4 frame to its
     * own shape from the middle; the garment must stay inside what ALL of them show, so it is never cut anywhere.
     */
    val PREVIEWS = listOf(0.75f to 0f, 0.80f to 0f, 2f / 3f to 0f, 1f to 0f)

    /** The box (frame widths; x from the left, y from the top) that every preview shows, minus [PAD]. */
    private val safe: FloatArray = run {
        var x0 = 0f; var x1 = 1f; var y0 = 0f; var y1 = FRAME_H
        for ((ratio, label) in PREVIEWS) {
            val w = min(1f, ratio * FRAME_H)                      // visible width / height of the frame in frame widths
            val h = min(FRAME_H, 1f / ratio)
            x0 = max(x0, (1f - w) / 2); x1 = min(x1, 1f - (1f - w) / 2)
            val top = (FRAME_H - h) / 2
            y0 = max(y0, top); y1 = min(y1, top + h * (1f - label))
        }
        // Same space above and below the garment: the box is made symmetric about the frame's middle (the label of the phone
        // grid used to push the garment up, which left more margin below than above on the item page).
        val half = min(FRAME_H / 2 - (y0 + PAD), (y1 - PAD) - FRAME_H / 2)
        floatArrayOf(x0 + PAD, FRAME_H / 2 - half, x1 - PAD, FRAME_H / 2 + half)
    }
    val SAFE_W: Float = safe[2] - safe[0]                        // the garment never takes more than this of the frame's width…
    val SAFE_H: Float = (safe[3] - safe[1]) / FRAME_H            // …or of its height (as a share of it)
    val LIFT: Float = (FRAME_H / 2 - (safe[1] + safe[3]) / 2) / FRAME_H   // garment centre a little above the middle: labels cover the bottom
    const val MAX_TILT = 12f             // degrees it will ever straighten
    private const val MIN_TURN = 1.5f    // it never turns less than this: a degree or so is measuring noise, and would only make a straight garment crooked…
    private const val MIN_GAIN = 1.25f   // …nor unless the turn makes the outline this many times straighter than it is…
    private const val MAX_DISAGREE = 3f  // …nor when the two ways of reading the outline differ by more than this many degrees
    const val MIN_AREA = 0.08f           // silhouettes smaller or bigger than this share of the photo are not trusted
    const val MAX_AREA = 0.92f
    const val MAX_BORDER = 0.6f          // more than this share of the photo's borders being garment: nothing to frame by
    private const val DEPTH_TINY = 0.02f // a strip of mirrored backdrop this deep (share of the frame's side) is not noticed at all…
    private const val DEPTH_OK = 0.055f  // …and this deep is still fine when it is only plain floor…
    const val FILL_MAX = 0.09f       // (public: the editor lets a hand-made framing reach this far too)
    private const val DEPTH_MAX = FILL_MAX  // …and this is the most: tall garments (trousers) shot with little margin need it, and the garment keeps its safe size
    const val TURNED_FILL_MAX = 0.14f   // a photo that had to be turned to put the garment straight leaves its corners bare: this much mirrored floor is accepted there, so the garment can stay centred

    /** The deepest strip of mirrored backdrop a framing turned by [rotation] degrees may show (the app's auto framing and its editor agree on it). */
    fun fillLimit(rotation: Float): Float = if (rotation != 0f) TURNED_FILL_MAX else FILL_MAX
    private const val EDGE = 0.002f      // tolerance when asking whether the photo reaches the frame's edge
    private val GROWTHS = floatArrayOf(1f, 1.02f, 1.04f, 1.06f)
    private val VISUAL_FIRST = booleanArrayOf(true, false)    // each centred try: first by the garment's visual middle, then by the middle of its box
    private val DIRT_GROWTHS = floatArrayOf(1f, 1.02f, 1.04f, 1.06f, 1.08f, 1.10f, 1.12f)   // how much a centred garment may grow to keep a strip from doubling something at the photo's edge
    private val CENTRED_GROWTHS = floatArrayOf(1f, 1.02f)          // …but a garment that is kept exactly centred only gets 2 %   // how much bigger than the safe size the garment may get to avoid any fill
    private const val GRID_X = 24        // frame points checked for what the fill would show
    private const val GRID_Y = 32
    private const val FINE_X = 96        // …and the finer grid for what a mirrored strip would show
    private const val FINE_Y = 128
    private const val NEAR_BORDER = 5    // cells from the photo's edge that still count as touching it
    private const val OPEN = 4            // cells (of a 128-cell grid) the opening uses to tell floor stains from the garment
    private const val TRIM = 0.002f      // share of the silhouette ignored at each border (stray specks)
    private const val PULL_SHARE = 0.75f // how much of the way to the visual middle the garment is moved: the corner of a fold must not end up against the edge
    private const val PULL_MAX = 0.03f   // …and never more than this (frame widths) off the middle of its box

    /**
     * Where the garment looks centred, in cells of [grid] from its left edge: the middle of its rows on average, which is the
     * same amount of floor on its left and on its right. The middle of its two extremes is not that for a folded garment: a
     * corner of the fold at the bottom left pulls the box to the left, while the waist and the pocket (what the eye takes in) sit to the right.
     */
    private fun visualMiddle(grid: Mask, box: Box): Float {
        var sum = 0f
        var rows = 0
        for (y in box.y0..box.y1) {
            var lo = -1
            var hi = -1
            for (x in box.x0..box.x1) if (grid.on[y * grid.w + x]) { if (lo < 0) lo = x; hi = x }
            if (lo >= 0) { sum += (lo + hi + 1) / 2f; rows++ }
        }
        return if (rows == 0) (box.x0 + box.x1 + 1) / 2f else sum / rows
    }

    /** How far (frame widths) the garment may be moved off the middle of its box to look centred: only into the room the safe box leaves it, so it is never cut or put against the edge of a narrow preview. */
    private fun pullLimit(boxW: Float): Float = min(PULL_MAX, max(0f, (SAFE_W - boxW) / 2))

    /** The move (frame widths) that puts a garment whose box is [boxW] wide and whose visual middle is [off] frame widths right of the box's middle where it looks centred. */
    private fun pull(off: Float, boxW: Float): Float = (-off * PULL_SHARE).coerceIn(-pullLimit(boxW), pullLimit(boxW))

    /**
     * The garment's silhouette → where to put the photo; null if the silhouette is not believable.
     * [clutter] (same grid): everything in the photo that is not plain floor, the garment included. A mirrored strip doubles whatever
     * touches the photo's edge (a shadow or an object becomes a diamond), so where the edge has something, the garment stays centred
     * and grows a little (never more than [DIRT_GROWTHS]) until the strip that is left shows only plain floor.
     */
    fun place(mask: Mask, imageW: Int, imageH: Int, clutter: Mask? = null): Placement? {
        val clean = largestParts(mask) ?: return null
        val area = clean.count().toFloat() / (clean.w * clean.h)
        if (area < MIN_AREA || area > MAX_AREA) return null
        if (borderShare(clean) > MAX_BORDER) return null      // the garment fills the photo: nothing to frame by
        val near = dilated(clean, 2)
        val dirt = clutter?.takeIf { it.w == clean.w && it.h == clean.h }?.let { dilated(it, 1) }
        val turned = plan(clean, near, dirt, imageW, imageH, tilt(clean)) ?: return null
        // when the garment cannot be framed without going beyond the photo, do not also turn it: leave the shot as it is
        val (p, blend) = if (turned.second > 0.5f && turned.first.rotation != 0f) plan(clean, near, dirt, imageW, imageH, 0f) ?: return null else turned
        return p.copy(tight = blend > 0.5f)             // tight: the garment fills the photo, it may show cut
    }

    /** The garment's box in the photo turned [deg]: edges in photo pixels from the photo's centre (x0, y0, x1, y1). */
    private fun garmentBox(mask: Mask, imageW: Int, deg: Float): FloatArray? {
        val clean = largestParts(mask) ?: return null
        val grid = rotated(clean, deg)
        val box = bounds(grid) ?: return null
        val k = imageW.toFloat() / clean.w
        return floatArrayOf((box.x0 - grid.w / 2f) * k, (box.y0 - grid.h / 2f) * k,
            (box.x1 + 1 - grid.w / 2f) * k, (box.y1 + 1 - grid.h / 2f) * k)
    }

    /**
     * Space between the garment and the frame's edges for this placement, in pixels of a frame [outW] wide:
     * top, bottom, left, right (negative = the garment is cut). Null without a believable silhouette.
     */
    fun margins(mask: Mask, imageW: Int, p: Placement, outW: Int): IntArray? {
        val g = garmentBox(mask, imageW, p.rotation) ?: return null
        val u = p.scale / imageW                                   // frame widths per photo pixel
        val left = 0.5f + p.dx + g[0] * u
        val right = 0.5f + p.dx + g[2] * u
        val top = FRAME_H / 2 + p.dy + g[1] * u
        val bottom = FRAME_H / 2 + p.dy + g[3] * u
        return intArrayOf((top * outW).roundToInt(), ((FRAME_H - bottom) * outW).roundToInt(),
            (left * outW).roundToInt(), ((1f - right) * outW).roundToInt())
    }

    /** The same placement with the garment's centre moved onto the frame's centre (null without a silhouette). */
    fun centered(mask: Mask, imageW: Int, p: Placement): Placement? {
        val g = garmentBox(mask, imageW, p.rotation) ?: return null
        val u = p.scale / imageW
        val mid = (g[0] + g[2]) / 2
        val off = (visualMiddleX(mask, imageW, p.rotation, mid) - mid) * u
        return p.copy(dx = -mid * u + pull(off, (g[2] - g[0]) * u), dy = -(g[1] + g[3]) / 2 * u)
    }

    /** [visualMiddle] in photo pixels from the photo's centre ([fallback] without a silhouette). */
    private fun visualMiddleX(mask: Mask, imageW: Int, deg: Float, fallback: Float): Float {
        val clean = largestParts(mask) ?: return fallback
        val grid = rotated(clean, deg)
        val box = bounds(grid) ?: return fallback
        return (visualMiddle(grid, box) - grid.w / 2f) * (imageW.toFloat() / clean.w)
    }

    /** The box every preview shows (x0, y0, x1, y1 in frame widths from the frame's top-left), for the editor's guide. */
    fun safeBox(): FloatArray = safe.copyOf()

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

    /**
     * The ideal placement for the given turn (garment centred, always the same size) and how it was reached:
     * 0 = the photo covers the whole frame, so there is nothing to fill (or only a sliver of mirrored backdrop);
     * 1 = the garment does not fit in the photo, so the photo is just made to cover the frame.
     * Order of preference: no gap at all, the garment up to a few % bigger and moved around inside the room the safe box
     * leaves it; then a thin strip ([DEPTH_TINY], then [DEPTH_OK]) of mirrored backdrop that shows no garment; then the photo
     * as it is. Depth, not area, is what the eye notices: a tall garment in a photo with little margin needs a thin strip on
     * every side, which the mirrored floor hides well.
     */
    private fun plan(clean: Mask, near: Mask, dirt: Mask?, imageW: Int, imageH: Int, tilt: Float): Pair<Placement, Float>? {
        val grid = rotated(clean, tilt)
        val box = bounds(grid) ?: return null
        val k = imageW.toFloat() / clean.w                      // image pixels per mask cell
        val bw = (box.x1 - box.x0 + 1) * k
        val bh = (box.y1 - box.y0 + 1) * k
        if (bw < 1f || bh < 1f) return null
        val bcx = ((box.x0 + box.x1 + 1) / 2f - grid.w / 2f) * k
        val bcy = ((box.y0 + box.y1 + 1) / 2f - grid.h / 2f) * k
        val ideal = min(SAFE_W * imageW / bw, SAFE_H * FRAME_H * imageW / bh).coerceIn(0.3f, 6f)
        val moves = (-2..2).flatMap { kx -> (-2..2).map { ky -> kx to ky } }.sortedBy { abs(it.first) + abs(it.second) }
        val vcx = (visualMiddle(grid, box) - grid.w / 2f) * k    // where the garment looks centred (photo pixels from the photo's centre)
        // [visual]: the garment centred by the same floor on its left and right (see [visualMiddle]) instead of by the middle of its box.
        fun at(g: Float, kx: Int, ky: Int, visual: Boolean = false): Placement {
            val s = ideal * g
            val roomX = max(0f, (SAFE_W - bw * s / imageW) / 2) + PAD * 0.7f
            val roomY = max(0f, (SAFE_H * FRAME_H - bh * s / imageW) / 2) + PAD * 0.7f
            val moved = if (visual) pull((vcx - bcx) * s / imageW, bw * s / imageW) else 0f
            return Placement(s, -bcx * s / imageW + moved + kx / 2f * roomX, -bcy * s / imageW - LIFT * FRAME_H + ky / 2f * roomY, tilt)
        }
        // Symmetry first: the garment stays exactly in the middle (the same space above and below, left and right) whenever
        // that costs nothing the eye notices: a few % bigger, or a strip of plain mirrored backdrop at one edge (the thinnest
        // that does it; a garment lying low in a photo whose frame already reaches the photo's bottom needs one, and a floor
        // mirrored over a few % of the cover is far less noticeable than a garment with more air above than below).
        // The garment never grows more than [CENTRED_GROWTHS]: the more it grows, the less breathing room it has in the narrow previews.
        val centredLimits = if (tilt != 0f) floatArrayOf(0f, DEPTH_TINY, DEPTH_OK, DEPTH_MAX, TURNED_FILL_MAX) else floatArrayOf(0f, DEPTH_TINY, DEPTH_OK, DEPTH_MAX)
        var dirty: Placement? = null      // the first centred framing whose strip would double something that is not floor
        for (limit in centredLimits) for (g in CENTRED_GROWTHS) for (visual in VISUAL_FIRST) {
            val p = at(g, 0, 0, visual)
            val fine = if (limit == 0f) uncoveredShare(p, imageW, imageH) == 0f
                       else fillDepth(p, imageW, imageH) <= limit && fillIsBackdrop(near, p, imageW, imageH)
            if (!fine) continue
            if (dirt == null || fillDepth(p, imageW, imageH) == 0f || fillIsBackdrop(dirt, p, imageW, imageH)) return p to 0f
            if (dirty == null) dirty = p
        }
        // Something other than floor at the photo's edge: the strip it would double is avoided by growing the garment a little more
        // (a few % cover a shadow's tip; never more than [DIRT_GROWTHS]), still centred, and only a thin strip of plain floor is left.
        if (dirt != null && dirty != null) {
            for (limit in floatArrayOf(0f, DEPTH_TINY)) for (g in DIRT_GROWTHS) for (visual in VISUAL_FIRST) {
                val p = at(g, 0, 0, visual)
                val fine = if (limit == 0f) uncoveredShare(p, imageW, imageH) == 0f else fillDepth(p, imageW, imageH) <= limit && fillIsBackdrop(dirt, p, imageW, imageH)
                if (fine) return p to 0f
            }
            return dirty to 0f      // not even that gets rid of it: the strip stays (as before), the garment is not moved off-centre for it
        }
        // Only when that is not possible is the garment moved off-centre (inside the room the safe box leaves it).
        for (g in GROWTHS) for ((kx, ky) in moves) {
            val p = at(g, kx, ky)
            if (uncoveredShare(p, imageW, imageH) == 0f) return p to 0f
        }
        for (limit in floatArrayOf(DEPTH_TINY, DEPTH_OK, DEPTH_MAX)) for (g in GROWTHS) for ((kx, ky) in moves) {
            val p = at(g, kx, ky)
            if (fillDepth(p, imageW, imageH) <= limit && fillIsBackdrop(near, p, imageW, imageH)) return p to 0f
        }
        // The garment does not fit in the photo at the safe size: the photo just covers the frame. It is still centred as far as
        // a thin strip of plain floor allows (the photo's own margin is rarely the same on both sides), never deeper than [DEPTH_OK].
        val cover = coverScale(imageW, imageH, tilt)
        val goalX = -bcx * cover / imageW + pull((vcx - bcx) * cover / imageW, bw * cover / imageW)
        val goalY = -bcy * cover / imageW - LIFT * FRAME_H
        val shares = floatArrayOf(1f, 0.75f, 0.5f, 0.25f)
        val tries = shares.flatMap { tx -> shares.map { ty -> tx to ty } }.sortedByDescending { it.first + it.second }
        for (clean in booleanArrayOf(true, false)) for ((tx, ty) in tries) {
            val p = Placement(cover, goalX * tx, goalY * ty, tilt)
            val depth = fillDepth(p, imageW, imageH)
            if (depth > DEPTH_OK) continue
            if (depth == 0f || (fillIsBackdrop(near, p, imageW, imageH) && (!clean || dirt == null || fillIsBackdrop(dirt, p, imageW, imageH)))) return p to 1f
        }
        return Placement(cover, 0f, 0f, tilt) to 1f
    }

    /**
     * How far, in pixels of a frame [outW] wide, [p] is from the placement «Centrar» would give it, or 0 when that placement
     * would leave the photo (the garment is then as centred as this photo allows). It is what the editor's indicator calls
     * «not centred»: unequal margins are not, because a folded garment is centred by its body, not by its fold.
     */
    fun offCentre(mask: Mask, imageW: Int, imageH: Int, p: Placement, outW: Int): Int {
        val c = centered(mask, imageW, p) ?: return 0
        if (!isCovered(c, imageW, imageH, fillLimit(c.rotation))) return 0
        return (max(abs(c.dx - p.dx), abs(c.dy - p.dy)) * outW).roundToInt()
    }

    /**
     * The same placement made a little bigger (about the frame's middle) until the photo covers the whole frame, so nothing
     * has to be filled in: a few % more garment is not noticed, an invented backdrop is. When even that is not enough,
     * the photo just covers the frame.
     */
    fun noFill(p: Placement, imageW: Int, imageH: Int): Placement {
        if (uncoveredShare(p, imageW, imageH) == 0f) return p
        var s = p.scale
        repeat(60) {
            s *= 1.01f
            val k = s / p.scale
            val q = p.copy(scale = s, dx = p.dx * k, dy = p.dy * k)
            if (uncoveredShare(q, imageW, imageH) == 0f) return q
        }
        return Placement(coverScale(imageW, imageH, p.rotation), 0f, 0f, p.rotation, p.tight)
    }

    /** True when the photo covers the frame, or leaves at most a strip of mirrored backdrop too thin to be noticed ([DEPTH_TINY]). */
    fun isCovered(p: Placement, imageW: Int, imageH: Int, tolerance: Float = DEPTH_TINY): Boolean = fillDepth(p, imageW, imageH) <= tolerance

    /**
     * Like [noFill], but a strip of mirrored backdrop that thin is left alone: the garment is made bigger only as much as needed
     * to get under that depth (never more than ~12 %), so it stays centred and keeps the room it has in the narrow previews.
     * When the photo is too far from covering the frame, the photo grows until it does (as [noFill]).
     */
    fun settle(p: Placement, imageW: Int, imageH: Int, tolerance: Float = DEPTH_TINY): Placement {
        if (isCovered(p, imageW, imageH, tolerance)) return p
        var s = p.scale
        repeat(12) {
            s *= 1.01f
            val k = s / p.scale
            val q = p.copy(scale = s, dx = p.dx * k, dy = p.dy * k)
            if (isCovered(q, imageW, imageH, tolerance)) return q
        }
        return noFill(p, imageW, imageH)
    }

    /** Share of the frame the photo does not reach with this placement (0 = it covers everything). */
    fun uncoveredShare(p: Placement, imageW: Int, imageH: Int): Float {
        var out = 0
        var n = 0
        forEachFramePoint(p, imageW, imageH) { u, v ->
            n++
            if (u < -EDGE || u > 1f + EDGE || v < -EDGE || v > 1f + EDGE) out++
        }
        return if (n == 0) 0f else out.toFloat() / n
    }

    /** How far the fill has to reach beyond the photo's edge at the deepest point, as a share of the frame's width (across) or
     *  height (down), so a strip looks equally thin on every side; 0 when the photo covers the frame. */
    fun fillDepth(p: Placement, imageW: Int, imageH: Int): Float {
        var deepest = 0f
        val ph = p.scale * imageH / imageW
        forEachFramePoint(p, imageW, imageH) { u, v ->
            val dx = max(-u, u - 1f).coerceAtLeast(0f) * p.scale
            val dy = max(-v, v - 1f).coerceAtLeast(0f) * ph
            deepest = max(deepest, max(dx, dy / FRAME_H))
        }
        return deepest
    }

    /** For a grid of points over the frame, where each falls in the photo (0..1 across and down; outside = not reached). */
    private inline fun forEachFramePoint(p: Placement, imageW: Int, imageH: Int, nx: Int = GRID_X, ny: Int = GRID_Y, f: (Float, Float) -> Unit) {
        val rad = p.rotation * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val ph = p.scale * imageH / imageW
        for (j in 0..ny) for (i in 0..nx) {
            val px = i.toFloat() / nx - 0.5f - p.dx
            val py = j.toFloat() / ny * FRAME_H - FRAME_H / 2 - p.dy
            f((c * px + s * py) / p.scale + 0.5f, (-s * px + c * py) / ph + 0.5f)
        }
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
        var clean = true
        // finer than the other checks: what a strip would double can be a sliver of a couple of cells (a shadow's tip at the edge)
        forEachFramePoint(p, imageW, imageH, FINE_X, FINE_Y) { u, v ->
            if (!clean || (u in 0f..1f && v in 0f..1f)) return@forEachFramePoint
            val x = (mirrored(u) * near.w).toInt().coerceIn(0, near.w - 1)
            val y = (mirrored(v) * near.h).toInt().coerceIn(0, near.h - 1)
            if (near.on[y * near.w + x]) clean = false
        }
        return clean
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
