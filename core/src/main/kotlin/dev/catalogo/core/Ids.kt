package dev.catalogo.core

import java.security.SecureRandom

/** Random identifiers (device, lot, temporary file names). */
object Ids {
    private val random = SecureRandom()

    fun newId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
