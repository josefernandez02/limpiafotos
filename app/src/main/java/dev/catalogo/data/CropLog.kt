package dev.catalogo.data

import android.content.Context
import dev.catalogo.core.CropEdit
import dev.catalogo.core.CropHistory

/** Where the crop record of each article's photos is kept between sessions (see [CropHistory]). */
object CropLog {
    private const val FILE = "crop_log"

    private fun key(deviceId: String, number: Int) = "$deviceId#$number"

    fun load(context: Context, deviceId: String, number: Int): List<CropEdit?> =
        CropHistory.decode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key(deviceId, number), "").orEmpty())

    fun save(context: Context, deviceId: String, number: Int, list: List<CropEdit?>) {
        val e = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (list.all { it == null }) e.remove(key(deviceId, number)) else e.putString(key(deviceId, number), CropHistory.encode(list))
        e.apply()
    }
}
