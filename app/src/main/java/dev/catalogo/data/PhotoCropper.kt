package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import dev.catalogo.core.JpegClean
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Framing of a photo inside the 3:4 template. Sizes are in frame widths:
 * [scale] is the photo's width, [dx]/[dy] move the photo's centre away from
 * the frame's centre; [rotation] turns it (degrees, clockwise) around its centre. Where the photo does not reach, the frame is filled
 * with the colour of the photo's own edges (the floor or backdrop).
 */
data class Framing(val scale: Float, val dx: Float, val dy: Float, val rotation: Float = 0f)

object PhotoCropper {
    const val RATIO = 3f / 4f // width / height, as Vinted shows the grid
    const val OUT_W = 1536
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
            draw(canvas, src, f, 0f, 0f, OUT_W.toFloat())
            val bytes = ByteArrayOutputStream(2 * 1024 * 1024).also { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
            return JpegClean.strip(bytes, keepIcc = false)
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    /**
     * Draws [src] framed by [f] in a frame of [fw] x [fw]/[RATIO] with its top-left corner at ([left], [top]).
     * Where the photo does not reach, the photo is mirrored (pieces of its own backdrop, no stretched pixels).
     * The editor, its previews and the final JPEG all go through here, so what is seen is what is saved.
     */
    fun draw(canvas: Canvas, src: Bitmap, f: Framing, left: Float, top: Float, fw: Float) {
        val w = f.scale * fw
        val h = w * src.height / src.width
        val cx = left + fw / 2 + f.dx * fw
        val cy = top + fw / RATIO / 2 + f.dy * fw
        val m = Matrix().apply {
            postScale(w / src.width, h / src.height)
            postTranslate(cx - w / 2, cy - h / 2)
            postRotate(f.rotation, cx, cy)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).also { it.setLocalMatrix(m) }
        canvas.drawRect(left, top, left + fw, top + fw / RATIO, paint)
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
