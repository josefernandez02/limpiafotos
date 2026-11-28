package dev.catalogo.core.photo

import dev.catalogo.core.photo.CoverFrame.FRAME_H
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** Whether a placed photo covers the whole frame, and what to do when it does not. */
object FrameCoverage {
    internal const val DEPTH_TINY = 0.02f // a strip of mirrored backdrop this deep (share of the frame's side) is not noticed at all…
    private const val EDGE = 0.002f      // tolerance when asking whether the photo reaches the frame's edge
    private const val GRID_X = 24        // frame points checked for what the fill would show
    private const val GRID_Y = 32
    private const val FINE_X = 96        // …and the finer grid for what a mirrored strip would show
    private const val FINE_Y = 128

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
    internal fun fillIsBackdrop(near: Mask, p: Placement, imageW: Int, imageH: Int): Boolean {
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
}
