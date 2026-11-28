package dev.catalogo.data.photo

import android.os.Build

/** The folder this phone's gallery lists as normal albums. */
object GalleryFolders {
    fun auto(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "poco", "redmi" -> "Pictures/Gallery/owner"
        "samsung" -> "DCIM"
        else -> "Pictures"
    }
}
