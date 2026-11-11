package dev.catalogo.data

import dev.catalogo.core.Auth
import java.io.File

/** Clean photos waiting to be sent: <files>/outbox/<deviceId>/<number>/<name>.jpg */
class Outbox(filesDir: File) {
    private val root = File(filesDir, "outbox")

    private fun dir(deviceId: String, number: Int) = File(File(root, deviceId), number.toString())

    fun add(deviceId: String, number: Int, bytes: ByteArray): File {
        val dir = dir(deviceId, number).apply { mkdirs() }
        val tmp = File(dir, ".${Auth.newId()}.part")
        tmp.writeBytes(bytes)
        val out = File(dir, "${Auth.newId()}.jpg")
        if (!tmp.renameTo(out)) {
            tmp.delete()
            error("No se pudo guardar la foto")
        }
        return out
    }

    fun files(deviceId: String, number: Int): List<File> =
        dir(deviceId, number).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedWith(compareBy({ it.lastModified() }, { it.name })) ?: emptyList()

    /** Replaces the photos waiting for [deviceId] with [photos], in this order. */
    fun replace(deviceId: String, number: Int, photos: List<ByteArray>) {
        val old = files(deviceId, number)
        val start = System.currentTimeMillis()
        // Random names: the modification time, a second apart, keeps the order.
        photos.forEachIndexed { i, bytes -> add(deviceId, number, bytes).setLastModified(start + i * 1000L) }
        old.forEach { it.delete() }
    }

    /** number → files, for every album waiting for [deviceId]. */
    fun pending(deviceId: String): Map<Int, List<File>> =
        File(root, deviceId).listFiles()?.mapNotNull { d -> d.name.toIntOrNull()?.let { it to files(deviceId, it) } }
            ?.filter { it.second.isNotEmpty() }?.sortedBy { it.first }?.toMap() ?: emptyMap()

    fun count(deviceId: String, number: Int): Int = files(deviceId, number).size

    fun delete(deviceId: String, number: Int) {
        dir(deviceId, number).deleteRecursively()
    }

    fun deleteNumber(number: Int) {
        root.listFiles()?.forEach { File(it, number.toString()).deleteRecursively() }
    }

    fun deleteDevice(deviceId: String) {
        File(root, deviceId).deleteRecursively()
    }
}
