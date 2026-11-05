package dev.catalogo.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.catalogo.core.Auth
import java.io.File
import java.io.IOException

/** Albums are folders "<base>/<SKU>/" in shared storage; galleries list them as albums. */
class Gallery(context: Context) {
    private val resolver = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    data class Photo(val uri: Uri, val addedSec: Long)

    private fun path(base: String, album: String) = "$base/$album/"

    /** Photo count per album directly under [base]. */
    fun counts(base: String): Map<String, Int> {
        val result = HashMap<String, Int>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$base/%"),
            null,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val rel = c.getString(col) ?: continue
                val album = rel.removePrefix("$base/").trimEnd('/')
                if (album.isNotEmpty() && '/' !in album) result[album] = (result[album] ?: 0) + 1
            }
        }
        return result
    }

    fun albumPhotos(base: String, album: String): List<Uri> = query(
        "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
        arrayOf(path(base, album)),
    ).map { it.uri }

    /** Writes a finished JPEG into the album; invisible to other apps until complete. */
    fun save(base: String, album: String, source: File, name: String = randomName()): Uri =
        save(base, album, name) { out -> source.inputStream().use { it.copyTo(out) } }

    fun save(base: String, album: String, bytes: ByteArray, name: String = randomName()): Uri =
        save(base, album, name) { out -> out.write(bytes) }

    fun contains(base: String, album: String, name: String): Boolean = query(
        "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
        arrayOf(path(base, album), name),
    ).isNotEmpty()

    private fun randomName() = "${Auth.newId().take(12)}.jpg"

    private fun save(base: String, album: String, name: String, writer: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, path(base, album))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear la foto")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("No se pudo escribir la foto")).use(writer)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Deletes what it can; returns the photos that need the user's consent. */
    fun delete(uris: List<Uri>): List<Uri> {
        val needConsent = ArrayList<Uri>()
        for (uri in uris) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: SecurityException) {
                needConsent += uri
            }
        }
        return needConsent
    }

    fun deleteAlbum(base: String, album: String): List<Uri> = delete(albumPhotos(base, album))

    /** Moves every photo of an album to another folder; returns the ones that could not be moved. */
    fun move(fromBase: String, fromAlbum: String, toBase: String, toAlbum: String): Int {
        if (fromBase == toBase && fromAlbum == toAlbum) return 0
        var failed = 0
        val values = ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, path(toBase, toAlbum)) }
        for (uri in albumPhotos(fromBase, fromAlbum)) {
            try {
                if (resolver.update(uri, values, null, null) == 0) failed++
            } catch (_: SecurityException) {
                failed++
            }
        }
        return failed
    }

    /** Photos the camera saved (or the gallery edited) since [sinceMillis]. */
    fun cameraPhotosSince(sinceMillis: Long): List<Photo> {
        val since = (sinceMillis / 1000 - 2).toString()
        return query(
            "(${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_MODIFIED} >= ?) AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf(since, since, "DCIM/Camera%"),
        )
    }

    private fun query(selection: String, args: Array<String>): List<Photo> {
        val result = ArrayList<Photo>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                result += Photo(ContentUris.withAppendedId(collection, c.getLong(id)), c.getLong(added))
            }
        }
        return result
    }
}
