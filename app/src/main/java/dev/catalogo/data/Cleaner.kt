package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dev.catalogo.core.JpegClean
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns any photo (JPEG, HEIF…) into a new JPEG with nothing but the image:
 * pixels are decoded with their orientation applied, re-encoded at full
 * resolution, and every non-image block is stripped and verified absent.
 */
object Cleaner {
    private const val QUALITY = 95
    private const val MAX_EDGE = 8192 // only guards against 200 MP modes exhausting memory

    fun clean(resolver: ContentResolver, uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val edge = max(w, h)
            if (edge > MAX_EDGE) {
                val s = MAX_EDGE.toDouble() / edge
                decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
            }
        }
        val encoded = try {
            ByteArrayOutputStream(4 * 1024 * 1024).also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) { "No se pudo codificar la foto" }
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val clean = JpegClean.strip(encoded)
        check(JpegClean.isClean(clean)) { "La foto no quedó limpia" }
        return clean
    }
}
