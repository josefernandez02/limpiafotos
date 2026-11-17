package dev.catalogo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.catalogo.core.Backdrop
import dev.catalogo.core.CoverFrame
import dev.catalogo.core.Mask
import dev.catalogo.core.Placement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Automatic framing of a cover: finds the garment (an on-device model, or the colour of the backdrop if
 * the model is not available), and lets [CoverFrame] straighten, centre and size it the same way every time.
 * It never makes things worse: when it cannot find a believable garment it returns no framing.
 */
object AutoFrame {
    /** [how]: «IA» (model), «fondo» (backdrop colour) or empty (nothing found). */
    data class Result(val framing: Framing?, val how: String, val tight: Boolean = false)

    private const val DECODE_EDGE = 640
    private const val MASK_EDGE = 128
    private const val AGREE = 0.8f             // overlap of the two silhouettes from which they count as the same
    private const val MODEL_WAIT_MS = 25_000L   // the first time Google Play services may have to download the model

    suspend fun analyze(context: Context, uri: Uri): Result = withContext(Dispatchers.Default) {
        val bmp = runCatching { decode(context, uri) }.getOrNull() ?: return@withContext Result(null, "")
        try {
            val model = runCatching { modelMask(bmp) }.getOrNull()
            val floor = runCatching { backdropMask(bmp, model?.w, model?.h) }.getOrNull()
            val byModel = model?.let { CoverFrame.place(it, bmp.width, bmp.height) }
            val byFloor = floor?.let { CoverFrame.place(it, bmp.width, bmp.height) }
            // Two independent opinions. When they agree, the model's smoother edge wins; when they do not (the model
            // loses a sleeve or a pattern, or the floor is tricky), the one that gave a believable silhouette is used.
            val agree = model != null && floor != null && CoverFrame.agreement(CoverFrame.largestParts(model) ?: model, CoverFrame.largestParts(floor) ?: floor) >= AGREE
            val first = when {
                byModel != null && (agree || byFloor == null) -> byModel to "IA"
                byFloor != null -> byFloor to "fondo"
                else -> null
            }
            // when the chosen silhouette leaves the garment «tight» but the other one frames it comfortably, the second opinion wins
            val (p, how) = when {
                first != null && first.first.tight && first.second == "IA" && byFloor != null && !byFloor.tight -> byFloor to "fondo"
                first != null && first.first.tight && first.second == "fondo" && byModel != null && !byModel.tight -> byModel to "IA"
                first != null -> first
                else -> {
                    note(bmp, model, floor, byModel, byFloor, agree, null, "nada")
                    return@withContext Result(null, "")
                }
            }
            note(bmp, model, floor, byModel, byFloor, agree, p, how)
            Result(p.toFraming(), how, p.tight)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * What the framing saw, to the app's log (which the PC keeps): the numbers always, and the two silhouettes as text when
     * the result is «tight» or nothing was found, so a photo that frames badly can be understood without guessing.
     */
    private fun note(bmp: Bitmap, model: Mask?, floor: Mask?, byModel: Placement?, byFloor: Placement?, agree: Boolean, chosen: Placement?, how: String) {
        fun box(m: Mask?): String {
            val c = m?.let { CoverFrame.largestParts(it) } ?: return "-"
            var x0 = c.w; var y0 = c.h; var x1 = -1; var y1 = -1
            for (y in 0 until c.h) for (x in 0 until c.w) if (c.on[y * c.w + x]) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
            if (x1 < 0) return "vacía"
            return "%d%%..%d%% x %d%%..%d%%".format(x0 * 100 / c.w, (x1 + 1) * 100 / c.w, y0 * 100 / c.h, (y1 + 1) * 100 / c.h)
        }
        fun cells(m: Mask?) = m?.count() ?: -1
        fun dump(name: String, m: Mask?): String {
            if (m == null) return "$name: sin máscara"
            val c = CoverFrame.largestParts(m) ?: m
            val sb = StringBuilder("$name (${c.w}x${c.h}, cada carácter = 3x3 celdas):")
            for (y in 0 until c.h step 3) {
                sb.append('\n')
                for (x in 0 until c.w step 3) {
                    var n = 0
                    for (j in 0 until 3) for (i in 0 until 3) if (y + j < c.h && x + i < c.w && c.on[(y + j) * c.w + x + i]) n++
                    sb.append(if (n >= 5) '#' else '.')
                }
            }
            return sb.toString()
        }
        fun show(p: Placement?) = p?.let { "esc=%.2f dx=%.3f dy=%.3f giro=%.1f%s".format(java.util.Locale.ROOT, it.scale, it.dx, it.dy, it.rotation, if (it.tight) " JUSTA" else "") } ?: "ninguno"
        val tight = chosen == null || chosen.tight
        AppLog.i("encuadre", "foto ${bmp.width}x${bmp.height} · elegido=$how · modelo: ${cells(model)} celdas, caja ${box(model)}, resultado ${show(byModel)} · " +
            "fondo: ${cells(floor)} celdas, caja ${box(floor)}, resultado ${show(byFloor)} · coinciden=$agree")
        if (tight) AppLog.i("encuadre", dump("modelo", model) + "\n" + dump("fondo", floor))
    }

    private fun Placement.toFraming() = Framing(scale, dx, dy, rotation)

    private fun decode(context: Context, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > DECODE_EDGE) {
                val s = DECODE_EDGE.toFloat() / edge
                decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
        }

    /** The model's confidence per pixel, boiled down to a small yes/no silhouette. */
    private suspend fun modelMask(bmp: Bitmap): Mask? {
        val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        try {
            val result = try {
                withTimeoutOrNull(MODEL_WAIT_MS) { segmenter.process(InputImage.fromBitmap(bmp, 0)).await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            val conf = result.foregroundConfidenceMask ?: return null
            val w = bmp.width
            val h = bmp.height
            if (conf.capacity() < w * h) return null
            val mw = max(1, w * MASK_EDGE / max(w, h))
            val mh = max(1, h * MASK_EDGE / max(w, h))
            val on = BooleanArray(mw * mh)
            for (y in 0 until mh) {
                val y0 = y * h / mh
                val y1 = max(y0 + 1, (y + 1) * h / mh)
                for (x in 0 until mw) {
                    val x0 = x * w / mw
                    val x1 = max(x0 + 1, (x + 1) * w / mw)
                    var sum = 0f
                    var n = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) { sum += conf.get(yy * w + xx); n++ }
                    on[y * mw + x] = n > 0 && sum / n >= 0.5f
                }
            }
            return Mask(mw, mh, on)
        } finally {
            segmenter.close()
        }
    }

    /** [mw]x[mh]: the grid of the model's mask, so both can be compared cell by cell. */
    private fun backdropMask(bmp: Bitmap, mw: Int?, mh: Int?): Mask {
        val s = MASK_EDGE.toFloat() / max(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, mw ?: (bmp.width * s).toInt().coerceAtLeast(1), mh ?: (bmp.height * s).toInt().coerceAtLeast(1), true)
        try {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            return Backdrop.mask(px, small.width, small.height)
        } finally {
            if (small !== bmp) small.recycle()
        }
    }
}
