package dev.catalogo.data.store

import dev.catalogo.core.model.Ids
import java.io.File

/** Clean photos of the other accounts, kept inside the app: <files>/outbox/<accountId>/<number>/<name>.jpg */
class AccountPhotoStore(filesDir: File) {
    private val root = File(filesDir, "outbox")

    private fun dir(accountId: String, number: Int) = File(File(root, accountId), number.toString())

    fun add(accountId: String, number: Int, bytes: ByteArray, prefix: String = ""): File {
        val dir = dir(accountId, number).apply { mkdirs() }
        val tmp = File(dir, ".${Ids.newId()}.part")
        tmp.writeBytes(bytes)
        val out = File(dir, "$prefix${Ids.newId()}.jpg")
        if (!tmp.renameTo(out)) {
            tmp.delete()
            error("No se pudo guardar la foto")
        }
        return out
    }

    fun files(accountId: String, number: Int): List<File> =
        dir(accountId, number).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedWith(compareBy({ it.lastModified() }, { it.name })) ?: emptyList()

    /** Replaces the photos waiting for [accountId] with [photos], in this order. */
    fun replace(accountId: String, number: Int, photos: List<ByteArray>) {
        val old = files(accountId, number)
        val start = System.currentTimeMillis()
        // Random names: the modification time, a second apart, keeps the order.
        photos.forEachIndexed { i, bytes -> add(accountId, number, bytes).setLastModified(start + i * 1000L) }
        old.forEach { it.delete() }
    }

    /**
     * Copies of the main account's album made for another account. Only these
     * are ever replaced or removed automatically; photos taken for that
     * account (the only copy there is) are never touched.
     */
    fun replaceCopies(accountId: String, number: Int, photos: List<ByteArray>) {
        val old = copies(accountId, number)
        val start = System.currentTimeMillis()
        photos.forEachIndexed { i, bytes -> add(accountId, number, bytes, COPY).setLastModified(start + i * 1000L) }
        old.forEach { it.delete() }
    }

    fun deleteCopies(accountId: String, number: Int) {
        copies(accountId, number).forEach { it.delete() }
    }

    /** Photos waiting for [accountId] that are not copies: never deleted automatically. */
    fun hasOriginals(accountId: String, number: Int): Boolean = files(accountId, number).any { !it.name.startsWith(COPY) }

    private fun copies(accountId: String, number: Int) = files(accountId, number).filter { it.name.startsWith(COPY) }

    /** number → files, for every album waiting for [accountId]. */
    fun pending(accountId: String): Map<Int, List<File>> =
        File(root, accountId).listFiles()?.mapNotNull { d -> d.name.toIntOrNull()?.let { it to files(accountId, it) } }
            ?.filter { it.second.isNotEmpty() }?.sortedBy { it.first }?.toMap() ?: emptyMap()

    fun count(accountId: String, number: Int): Int = files(accountId, number).size

    fun delete(accountId: String, number: Int) {
        dir(accountId, number).deleteRecursively()
    }

    fun deleteNumber(number: Int) {
        root.listFiles()?.forEach { File(it, number.toString()).deleteRecursively() }
    }

    fun deleteAccount(accountId: String) {
        File(root, accountId).deleteRecursively()
    }

    companion object {
        private const val COPY = "copy-"
    }
}
