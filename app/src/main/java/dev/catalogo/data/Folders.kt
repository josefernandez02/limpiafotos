package dev.catalogo.data

import android.os.Build

/** The folder each phone's own gallery lists as normal albums. */
object Folders {
    fun auto(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "poco", "redmi" -> "Pictures/Gallery/owner"
        "samsung" -> "DCIM"
        else -> "Pictures"
    }
}
