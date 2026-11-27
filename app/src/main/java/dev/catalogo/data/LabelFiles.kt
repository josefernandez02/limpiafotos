package dev.catalogo.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.catalogo.core.CatalogException

/** The label PDFs, kept in the phone's Descargas where any app (and the user) can find them. */
class LabelFiles(private val resolver: ContentResolver) {

    /** Saves [bytes] as [name] (the system renames it if there is one already) and returns its address. */
    fun save(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw CatalogException("No se pudo crear el PDF en Descargas")
        try {
            val out = resolver.openOutputStream(uri) ?: throw CatalogException("No se pudo escribir el PDF en Descargas")
            out.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            // Nothing half written is left behind.
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** True when [uri] is no longer there. The app needs no permission for a PDF it saved itself. */
    fun delete(uri: Uri): Boolean = try {
        resolver.delete(uri, null, null)
        true
    } catch (e: Exception) {
        AppLog.w("etiquetas", "no se pudo borrar un PDF ya cobrado: ${e.message ?: e.javaClass.simpleName}")
        false
    }
}
