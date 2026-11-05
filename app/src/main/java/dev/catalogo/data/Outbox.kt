package dev.catalogo.data

import dev.catalogo.core.Auth
import java.io.File

/** Clean photos waiting to be sent: <files>/outbox/<deviceId>/<number>/<name>.jpg */
class Outbox(filesDir: File) {
    private val root = File(filesDir, "outbox")

    private fun dir(deviceId: String, number: Int) = File(File(root, deviceId), number.toString())

    fun add(deviceId: String, number: Int, bytes: ByteArray) {
        val dir = dir(deviceId, number).apply { mkdirs() }
        val tmp = File(dir, ".${Auth.newId()}.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, "${Auth.newId()}.jpg"))) {
            tmp.delete()
            error("No se pudo guardar la foto")
        }
    }

    fun files(deviceId: String, number: Int): List<File> =
        dir(deviceId, number).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.lastModified() } ?: emptyList()

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
