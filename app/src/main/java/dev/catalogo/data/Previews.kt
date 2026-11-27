package dev.catalogo.data

import java.io.File

/**
 * Reduced copies of photos that earlier versions sent to other phones: <files>/previews/<number>/.
 * Nothing writes them any more; they are still shown for the articles that have them.
 */
class Previews(filesDir: File) {
    private val root = File(filesDir, "previews")

    private fun dir(number: Int) = File(root, number.toString())

    fun files(number: Int): List<File> =
        dir(number).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun files(number: Int, deviceId: String): List<File> = files(number).filter { it.name.startsWith("$deviceId-") }

    fun deleteNumber(number: Int) {
        dir(number).deleteRecursively()
    }
}
