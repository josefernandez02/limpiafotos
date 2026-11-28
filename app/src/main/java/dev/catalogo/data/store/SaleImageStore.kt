package dev.catalogo.data.store

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One small picture per sale, kept in the app: the garment's own photos are
 * deleted when its sale is completed.
 */
class SaleImageStore(dir: File, private val resolver: ContentResolver) {
    private val folder = File(dir, "sales")

    fun file(saleId: String): File = File(folder, saleId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    /** Keeps a copy of the garment's cover before it goes. */
    suspend fun keep(saleId: String, cover: Uri) = withContext(Dispatchers.IO) {
        val target = file(saleId)
        if (target.exists()) return@withContext
        runCatching {
            val bitmap = if (cover.scheme == "file") {
                android.graphics.BitmapFactory.decodeFile(cover.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            } else resolver.loadThumbnail(cover, Size(EDGE, EDGE), null)
            write(target) { out -> bitmap!!.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        }
        Unit
    }

    /** The picture kept for a sale, or null if there is none. The phone asks Vinted for nothing. */
    suspend fun get(saleId: String): File? = withContext(Dispatchers.IO) { file(saleId).takeIf { it.exists() } }

    /** Never leaves half a picture behind. */
    private fun write(target: File, fill: (java.io.OutputStream) -> Unit) {
        folder.mkdirs()
        val part = File(target.path + ".part")
        part.outputStream().use(fill)
        part.renameTo(target)
    }

    private companion object {
        const val EDGE = 480
    }
}
