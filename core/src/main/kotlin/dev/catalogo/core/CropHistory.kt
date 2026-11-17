package dev.catalogo.core

/** What has been done to one photo: how many times it was cropped, how the last time ([AUTO] or [MANUAL]) and when. */
data class CropEdit(val count: Int, val kind: String, val at: Long)

/** The crop record of an article's photos, kept so a photo already framed is not edited again without knowing. */
object CropHistory {
    const val AUTO = "auto"
    const val MANUAL = "manual"

    /** One entry per photo, in order; «-» for a photo never cropped. */
    fun encode(list: List<CropEdit?>): String =
        list.joinToString(";") { e -> if (e == null) "-" else "${e.count},${e.kind},${e.at}" }

    fun decode(text: String): List<CropEdit?> {
        if (text.isBlank()) return emptyList()
        return text.split(";").map { part ->
            val f = part.split(",")
            val count = f.getOrNull(0)?.toIntOrNull()
            val at = f.getOrNull(2)?.toLongOrNull()
            if (f.size == 3 && count != null && count > 0 && at != null && (f[1] == AUTO || f[1] == MANUAL)) CropEdit(count, f[1], at) else null
        }
    }

    /** The record after one more crop. */
    fun next(prev: CropEdit?, kind: String, now: Long) = CropEdit((prev?.count ?: 0) + 1, kind, now)

    /** The record as saved only applies to the same photos: when their number changed it is not trusted. */
    fun aligned(saved: List<CropEdit?>, photos: Int): List<CropEdit?> =
        if (saved.size == photos) saved else List(photos) { null }

    /** Short sentence for the editor: «Recortada 2 veces · la última a mano, hace 5 min». */
    fun describe(e: CropEdit, now: Long): String {
        val times = if (e.count == 1) "Recortada 1 vez" else "Recortada ${e.count} veces"
        val how = if (e.kind == AUTO) "automático" else "a mano"
        val mins = ((now - e.at) / 60_000L).coerceAtLeast(0)
        val ago = when {
            mins < 1 -> "ahora"
            mins < 60 -> "hace $mins min"
            mins < 24 * 60 -> "hace ${mins / 60} h"
            else -> "hace ${mins / (24 * 60)} d"
        }
        return "$times · la última $how, $ago"
    }
}
