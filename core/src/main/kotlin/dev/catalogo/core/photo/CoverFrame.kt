package dev.catalogo.core.photo

import dev.catalogo.core.photo.FrameCoverage.DEPTH_TINY
import dev.catalogo.core.photo.FrameCoverage.coverScale
import dev.catalogo.core.photo.FrameCoverage.fillDepth
import dev.catalogo.core.photo.FrameCoverage.fillIsBackdrop
import dev.catalogo.core.photo.FrameCoverage.isCovered
import dev.catalogo.core.photo.FrameCoverage.uncoveredShare
import dev.catalogo.core.photo.Silhouette.Box
import dev.catalogo.core.photo.Silhouette.borderShare
import dev.catalogo.core.photo.Silhouette.bounds
import dev.catalogo.core.photo.Silhouette.dilated
import dev.catalogo.core.photo.Silhouette.largestParts
import dev.catalogo.core.photo.Silhouette.rotated
import dev.catalogo.core.photo.Silhouette.tilt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    const val MIN_AREA = 0.08f           // silhouettes smaller or bigger than this share of the photo are not trusted
    const val MAX_AREA = 0.92f
    const val MAX_BORDER = 0.6f          // more than this share of the photo's borders being garment: nothing to frame by
    private const val DEPTH_OK = 0.055f  // …and this deep is still fine when it is only plain floor…
    const val FILL_MAX = 0.09f       // (public: the editor lets a hand-made framing reach this far too)
    private const val DEPTH_MAX = FILL_MAX  // …and this is the most: tall garments (trousers) shot with little margin need it, and the garment keeps its safe size
    const val TURNED_FILL_MAX = 0.14f   // a photo that had to be turned to put the garment straight leaves its corners bare: this much mirrored floor is accepted there, so the garment can stay centred

    /** The deepest strip of mirrored backdrop a framing turned by [rotation] degrees may show (the app's auto framing and its editor agree on it). */
    fun fillLimit(rotation: Float): Float = if (rotation != 0f) TURNED_FILL_MAX else FILL_MAX

    private val GROWTHS = floatArrayOf(1f, 1.02f, 1.04f, 1.06f)
    private val VISUAL_FIRST = booleanArrayOf(true, false)    // each centred try: first by the garment's visual middle, then by the middle of its box
    private val DIRT_GROWTHS = floatArrayOf(1f, 1.02f, 1.04f, 1.06f, 1.08f, 1.10f, 1.12f)   // how much a centred garment may grow to keep a strip from doubling something at the photo's edge
    private val CENTRED_GROWTHS = floatArrayOf(1f, 1.02f)          // …but a garment that is kept exactly centred only gets 2 %   // how much bigger than the safe size the garment may get to avoid any fill
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
}
