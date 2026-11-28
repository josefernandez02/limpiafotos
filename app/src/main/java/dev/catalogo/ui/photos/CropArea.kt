package dev.catalogo.ui.photos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.catalogo.core.photo.CoverFrame
import dev.catalogo.core.photo.FrameCoverage
import dev.catalogo.core.photo.Placement
import dev.catalogo.data.photo.Framing
import dev.catalogo.data.photo.PhotoCropper

internal enum class Grip { NONE, MOVE, TL, TR, BL, BR, T, B, L, R }

/** Where the photo sits on screen: [d] screen pixels per image pixel, its centre at ([cx], [cy]); the crop box moves over it. */
internal class Geo(val cx: Float, val cy: Float, val d: Float, val imgW: Int, val imgH: Int) {
    val minW = d * imgW * 0.15f
}

internal fun Framing.placement() = Placement(scale, dx, dy, rotation)

internal fun Placement.framing() = Framing(scale, dx, dy, rotation)

internal fun Framing.covers(w: Int, h: Int) = FrameCoverage.isCovered(placement(), w, h, CoverFrame.fillLimit(rotation))   // or only a strip of mirrored backdrop, which the box already shows

/** The crop box (left, top, right, bottom, in screen pixels) of a framing: the 3:4 frame, with the photo fixed under it. */
private fun boxOf(f: Framing, g: Geo): FloatArray {
    val bw = g.d * g.imgW / f.scale
    val bx = g.cx - f.dx * bw
    val by = g.cy - f.dy * bw
    val bh = bw / PhotoCropper.RATIO
    return floatArrayOf(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
}

private fun framingOf(l: Float, t: Float, r: Float, b: Float, rotation: Float, g: Geo): Framing {
    val bw = r - l
    return Framing(g.d * g.imgW / bw, (g.cx - (l + r) / 2) / bw, (g.cy - (t + b) / 2) / bw, rotation)
}

private fun gripAt(at: Offset, b: FloatArray, slop: Float): Grip {
    val edge = minOf(slop * 0.6f, (b[2] - b[0]) * 0.2f, (b[3] - b[1]) * 0.2f)
    fun near(x: Float, y: Float) = kotlin.math.abs(at.x - x) <= slop && kotlin.math.abs(at.y - y) <= slop
    return when {
        near(b[0], b[1]) -> Grip.TL
        near(b[2], b[1]) -> Grip.TR
        near(b[0], b[3]) -> Grip.BL
        near(b[2], b[3]) -> Grip.BR
        // a side: only that side grows (the box keeps 3:4, so the other axis follows around its middle)
        at.y in b[1]..b[3] && kotlin.math.abs(at.x - b[0]) <= edge -> Grip.L
        at.y in b[1]..b[3] && kotlin.math.abs(at.x - b[2]) <= edge -> Grip.R
        at.x in b[0]..b[2] && kotlin.math.abs(at.y - b[1]) <= edge -> Grip.T
        at.x in b[0]..b[2] && kotlin.math.abs(at.y - b[3]) <= edge -> Grip.B
        at.x in b[0]..b[2] && at.y in b[1]..b[3] -> Grip.MOVE
        else -> Grip.NONE
    }
}

/** The framing after dragging [grip] by ([mx], [my]) screen pixels; a corner resizes the box around the opposite corner. */
internal fun dragged(f: Framing, grip: Grip, mx: Float, my: Float, g: Geo): Framing? {
    val b = boxOf(f, g)
    var l = b[0]; var t = b[1]; var r = b[2]; var bt = b[3]
    val w0 = r - l
    val grow = when (grip) {
        Grip.BR -> (mx + my * PhotoCropper.RATIO) / 2
        Grip.BL -> (-mx + my * PhotoCropper.RATIO) / 2
        Grip.TR -> (mx - my * PhotoCropper.RATIO) / 2
        Grip.TL -> (-mx - my * PhotoCropper.RATIO) / 2
        else -> 0f
    }
    val h0 = bt - t
    val w = when (grip) {
        Grip.T -> (h0 - my) * PhotoCropper.RATIO
        Grip.B -> (h0 + my) * PhotoCropper.RATIO
        Grip.L -> w0 - mx
        Grip.R -> w0 + mx
        else -> w0 + grow
    }.coerceAtLeast(g.minW)
    val h = w / PhotoCropper.RATIO
    val midX = (l + r) / 2
    val midY = (t + bt) / 2
    when (grip) {
        Grip.T -> { t = bt - h; l = midX - w / 2; r = midX + w / 2 }
        Grip.B -> { bt = t + h; l = midX - w / 2; r = midX + w / 2 }
        Grip.L -> { l = r - w; t = midY - h / 2; bt = midY + h / 2 }
        Grip.R -> { r = l + w; t = midY - h / 2; bt = midY + h / 2 }
        Grip.MOVE -> { l += mx; r += mx; t += my; bt += my }
        Grip.BR -> { r = l + w; bt = t + h }
        Grip.BL -> { l = r - w; bt = t + h }
        Grip.TR -> { r = l + w; t = bt - h }
        Grip.TL -> { l = r - w; t = bt - h }
        Grip.NONE -> return null
    }
    return framingOf(l, t, r, bt, f.rotation, g)
}

/** The photo stays still; the 3:4 box is dragged by its corners or sides (size) and its inside (position), never beyond the photo. */
@Composable
internal fun CropArea(img: ImageBitmap, f: Framing, guide: Boolean, garment: IntArray?, onChange: (Framing) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(f)
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val geo = Geo(areaW / 2f, areaH / 2f, minOf(areaW / img.width, areaH / img.height) * 0.97f, img.width, img.height)
        val slop = with(density) { 40.dp.toPx() }
        var grip by remember { mutableStateOf(Grip.NONE) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(img, areaW, areaH) {
                detectDragGestures(
                    onDragStart = { at -> grip = gripAt(at, boxOf(current, geo), slop) },
                    onDragEnd = { grip = Grip.NONE },
                    onDragCancel = { grip = Grip.NONE },
                    onDrag = { change, drag ->
                        change.consume()
                        val cur = current
                        // Never beyond the photo: if the whole step does not fit, try a smaller one (and, moving, each axis alone).
                        val tries = ArrayList<Pair<Float, Float>>()
                        for (k in floatArrayOf(1f, 0.5f, 0.25f)) tries.add(drag.x * k to drag.y * k)
                        if (grip == Grip.MOVE) { tries.add(drag.x to 0f); tries.add(0f to drag.y) }
                        for ((mx, my) in tries) {
                            val c = dragged(cur, grip, mx, my, geo) ?: break
                            if (c.covers(img.width, img.height)) { onChange(c); break }
                        }
                    },
                )
            },
        ) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.rotate(f.rotation, geo.cx, geo.cy)
                val pw = geo.d * img.width
                val ph = geo.d * img.height
                nc.drawBitmap(img.asAndroidBitmap(), null, android.graphics.RectF(geo.cx - pw / 2, geo.cy - ph / 2, geo.cx + pw / 2, geo.cy + ph / 2), paint)
                nc.restore()
            }
            val b = boxOf(f, geo)
            val bw = b[2] - b[0]
            drawFramed(img, f, b[0], b[1], bw)      // inside the box: exactly what will be saved, mirrored backdrop included (no black strips)
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(0f, 0f), GSize(size.width, b[1].coerceAtLeast(0f)))
            drawRect(dim, Offset(0f, b[3]), GSize(size.width, (size.height - b[3]).coerceAtLeast(0f)))
            drawRect(dim, Offset(0f, b[1]), GSize(b[0].coerceAtLeast(0f), b[3] - b[1]))
            drawRect(dim, Offset(b[2], b[1]), GSize((size.width - b[2]).coerceAtLeast(0f), b[3] - b[1]))
            if (!guide) {       // thirds, for ordinary photos (the cover has its own guides, below)
                val faint = Color.White.copy(alpha = 0.35f)
                for (k in 1..2) {
                    drawLine(faint, Offset(b[0] + bw * k / 3, b[1]), Offset(b[0] + bw * k / 3, b[3]), 1.dp.toPx())
                    drawLine(faint, Offset(b[0], b[1] + (b[3] - b[1]) * k / 3), Offset(b[2], b[1] + (b[3] - b[1]) * k / 3), 1.dp.toPx())
                }
            }
            if (guide) {
                // the margin the garment should touch, so every cover has the same space around it
                val sb = CoverFrame.safeBox()
                drawRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(b[0] + sb[0] * bw, b[1] + sb[1] * bw),
                    size = GSize((sb[2] - sb[0]) * bw, (sb[3] - sb[1]) * bw),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
            }
            if (guide && garment != null) {
                // where the garment is: the space between this box and the frame is what the numbers above measure
                val k = bw / PhotoCropper.OUT_W
                drawRect(
                    color = Color(0xFF4DD0E1).copy(alpha = 0.9f),
                    topLeft = Offset(b[0] + garment[2] * k, b[1] + garment[0] * k),
                    size = GSize(bw - (garment[2] + garment[3]) * k, (b[3] - b[1]) - (garment[0] + garment[1]) * k),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            if (guide) {
                // legend: what each guide is
                drawIntoCanvas { c ->
                    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.dp.toPx(); setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK) }
                    val sb = CoverFrame.safeBox()
                    tp.color = android.graphics.Color.WHITE
                    c.nativeCanvas.drawText("Zona segura (la prenda debe caber)", b[0] + sb[0] * bw + 6.dp.toPx(), b[1] + sb[1] * bw + 14.dp.toPx(), tp)
                    if (garment != null) {
                        tp.color = 0xFF4DD0E1.toInt()
                        c.nativeCanvas.drawText("Prenda detectada", b[0] + garment[2] * (bw / PhotoCropper.OUT_W) + 6.dp.toPx(), b[3] - garment[1] * (bw / PhotoCropper.OUT_W) - 6.dp.toPx(), tp)
                    }
                }
            }
            drawRect(Color.White, Offset(b[0], b[1]), GSize(bw, b[3] - b[1]), style = Stroke(width = 1.5.dp.toPx()))
            val len = 24.dp.toPx()
            val th = 4.dp.toPx()
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                drawLine(Color.White, Offset(x - sx * th / 2, y), Offset(x + sx * len, y), th)
                drawLine(Color.White, Offset(x, y - sy * th / 2), Offset(x, y + sy * len), th)
            }
            corner(b[0], b[1], 1f, 1f); corner(b[2], b[1], -1f, 1f); corner(b[0], b[3], 1f, -1f); corner(b[2], b[3], -1f, -1f)
            // a short bar in the middle of each side: they can be dragged on their own
            val bar = 14.dp.toPx()
            val mx = (b[0] + b[2]) / 2
            val my = (b[1] + b[3]) / 2
            drawLine(Color.White, Offset(mx - bar, b[1]), Offset(mx + bar, b[1]), th)
            drawLine(Color.White, Offset(mx - bar, b[3]), Offset(mx + bar, b[3]), th)
            drawLine(Color.White, Offset(b[0], my - bar), Offset(b[0], my + bar), th)
            drawLine(Color.White, Offset(b[2], my - bar), Offset(b[2], my + bar), th)
        }
    }
}

/** Rectangle (x0, y0, x1, y1 as shares of the photo) around the garment, each side dragged on its own: «Marcar prenda». */
@Composable
internal fun MarkArea(img: ImageBitmap, rect: FloatArray, onChange: (FloatArray) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(rect)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val d = minOf(areaW / img.width, areaH / img.height) * 0.97f
        val pw = d * img.width
        val ph = d * img.height
        val ox = (areaW - pw) / 2
        val oy = (areaH - ph) / 2
        val slop = with(density) { 36.dp.toPx() }
        var sides by remember { mutableStateOf(booleanArrayOf(false, false, false, false)) }   // left, top, right, bottom being dragged
        Canvas(
            Modifier.fillMaxSize().pointerInput(img, areaW, areaH) {
                detectDragGestures(
                    onDragStart = { at ->
                        val r = current
                        val x = floatArrayOf(ox + r[0] * pw, oy + r[1] * ph, ox + r[2] * pw, oy + r[3] * ph)
                        val inX = at.x in x[0] - slop..x[2] + slop
                        val inY = at.y in x[1] - slop..x[3] + slop
                        sides = booleanArrayOf(
                            inY && kotlin.math.abs(at.x - x[0]) <= slop,
                            inX && kotlin.math.abs(at.y - x[1]) <= slop,
                            inY && kotlin.math.abs(at.x - x[2]) <= slop,
                            inX && kotlin.math.abs(at.y - x[3]) <= slop,
                        )
                    },
                    onDragEnd = { sides = booleanArrayOf(false, false, false, false) },
                    onDragCancel = { sides = booleanArrayOf(false, false, false, false) },
                    onDrag = { change, drag ->
                        change.consume()
                        val r = current.copyOf()
                        val gap = 0.05f
                        if (sides[0]) r[0] = (r[0] + drag.x / pw).coerceIn(0f, r[2] - gap)
                        if (sides[1]) r[1] = (r[1] + drag.y / ph).coerceIn(0f, r[3] - gap)
                        if (sides[2]) r[2] = (r[2] + drag.x / pw).coerceIn(r[0] + gap, 1f)
                        if (sides[3]) r[3] = (r[3] + drag.y / ph).coerceIn(r[1] + gap, 1f)
                        onChange(r)
                    },
                )
            },
        ) {
            drawIntoCanvas { c -> c.nativeCanvas.drawBitmap(img.asAndroidBitmap(), null, android.graphics.RectF(ox, oy, ox + pw, oy + ph), null) }
            val l = ox + rect[0] * pw; val t = oy + rect[1] * ph; val r = ox + rect[2] * pw; val b = oy + rect[3] * ph
            val dim = Color.Black.copy(alpha = 0.5f)
            drawRect(dim, Offset(ox, oy), GSize(pw, t - oy))
            drawRect(dim, Offset(ox, b), GSize(pw, oy + ph - b))
            drawRect(dim, Offset(ox, t), GSize(l - ox, b - t))
            drawRect(dim, Offset(r, t), GSize(ox + pw - r, b - t))
            val cyan = Color(0xFF4DD0E1)
            drawRect(cyan, Offset(l, t), GSize(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
            val bar = 18.dp.toPx(); val th = 5.dp.toPx()
            val mx = (l + r) / 2; val my = (t + b) / 2
            drawLine(cyan, Offset(mx - bar, t), Offset(mx + bar, t), th); drawLine(cyan, Offset(mx - bar, b), Offset(mx + bar, b), th)
            drawLine(cyan, Offset(l, my - bar), Offset(l, my + bar), th); drawLine(cyan, Offset(r, my - bar), Offset(r, my + bar), th)
            for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) drawCircle(cyan, 6.dp.toPx(), Offset(cx, cy))
        }
    }
}
