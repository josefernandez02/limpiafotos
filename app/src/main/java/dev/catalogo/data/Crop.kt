package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import dev.catalogo.core.JpegClean
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Framing of a photo inside the 3:4 template. Sizes are in frame widths:
 * [scale] is the photo's width, [dx]/[dy] move the photo's centre away from
 * the frame's centre. Where the photo does not reach, the frame is filled
 * with the colour of the photo's own edges (the floor or backdrop).
 */
data class Framing(val scale: Float, val dx: Float, val dy: Float)

object Crop {
    const val RATIO = 3f / 4f // width / height, as Vinted shows the grid
    private const val OUT_W = 1536
    private const val OUT_H = 2048
    private const val MAX_DECODE = 3072

    /** Framing that fills the frame with the photo, centred (no empty borders). */
    fun cover(imageW: Int, imageH: Int): Framing =
        Framing(max(1f, (1f / RATIO) * imageW / imageH), 0f, 0f)

    /** Framing that shows the whole photo, centred. */
    fun fit(imageW: Int, imageH: Int): Framing =
        Framing(minOf(1f, (1f / RATIO) * imageW / imageH), 0f, 0f)

    /** The framed photo as a clean JPEG of [OUT_W]x[OUT_H]. */
    fun render(resolver: ContentResolver, uri: Uri, f: Framing): ByteArray {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > MAX_DECODE) {
                val s = MAX_DECODE.toFloat() / edge
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
        val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(edgeColor(src))
            val w = f.scale * OUT_W
            val h = w * src.height / src.width
            val cx = OUT_W / 2f + f.dx * OUT_W
            val cy = OUT_H / 2f + f.dy * OUT_W
            canvas.drawBitmap(src, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val bytes = ByteArrayOutputStream(2 * 1024 * 1024).also { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
            return JpegClean.strip(bytes, keepIcc = false)
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    /** Average colour of the photo's outer border. */
    fun edgeColor(b: Bitmap): Int {
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0
        val step = max(1, max(b.width, b.height) / 200)
        fun add(x: Int, y: Int) {
            val c = b.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
        }
        for (x in 0 until b.width step step) { add(x, 0); add(x, b.height - 1) }
        for (y in 0 until b.height step step) { add(0, y); add(b.width - 1, y) }
        return if (n == 0) Color.WHITE else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }
}
