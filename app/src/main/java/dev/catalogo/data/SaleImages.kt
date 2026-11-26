package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One small picture per sale, kept in the app: the garment's own photos are
 * deleted when its sale is completed, and old orders only have Vinted's.
 */
class SaleImages(dir: File, private val resolver: ContentResolver) {
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

    /** The picture of a sale: the one kept, or Vinted's, downloaded once. Null if there is none. */
    suspend fun get(saleId: String, url: String): File? = withContext(Dispatchers.IO) {
        val target = file(saleId)
        if (target.exists()) return@withContext target
        val address = runCatching { URL(url) }.getOrNull() ?: return@withContext null
        // Only Vinted's own image servers.
        if (address.protocol != "https" || !(address.host.endsWith(".vinted.net") || address.host.endsWith(".vinted.es"))) return@withContext null
        runCatching {
            val connection = address.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode != 200) return@runCatching null
                val bytes = connection.inputStream.use { it.readBytes() }
                write(target) { it.write(bytes) }
                target
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

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
