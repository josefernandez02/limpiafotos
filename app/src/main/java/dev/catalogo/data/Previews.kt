package dev.catalogo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Small copies (1280 px) of the photos already sent to other phones, kept on
 * the main phone for the cover and for the AI: <files>/previews/<number>/.
 */
class Previews(filesDir: File) {
    private val root = File(filesDir, "previews")

    private fun dir(number: Int) = File(root, number.toString())

    /** Keeps a reduced copy of [photos] sent to [deviceId], up to [MAX] per article. */
    fun keep(number: Int, deviceId: String, photos: List<File>) {
        val dir = dir(number).apply { mkdirs() }
        var room = MAX - files(number).size
        for (photo in photos) {
            if (room <= 0) return
            val out = File(dir, "$deviceId-${photo.nameWithoutExtension}.jpg")
            if (out.exists()) continue
            runCatching { shrink(photo, out) }.onSuccess { room-- }
        }
    }

    fun files(number: Int): List<File> =
        dir(number).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun files(number: Int, deviceId: String): List<File> = files(number).filter { it.name.startsWith("$deviceId-") }

    fun deleteNumber(number: Int) {
        dir(number).deleteRecursively()
    }

    private fun shrink(src: File, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Foto ilegible")
        val scale = SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val tmp = File(out.path + ".part")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (!tmp.renameTo(out)) tmp.delete()
    }

    companion object {
        private const val SIDE = 1280
        private const val MAX = 8
    }
}
