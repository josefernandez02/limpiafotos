package dev.catalogo.data

import android.content.Context
import dev.catalogo.core.CropEdit
import dev.catalogo.core.CropHistory

/** Where the crop record of each article's photos is kept between sessions (see [CropHistory]). */
object CropHistoryStore {
    private const val FILE = "crop_log"

    private fun key(accountId: String, number: Int) = "$accountId#$number"

    fun load(context: Context, accountId: String, number: Int): List<CropEdit?> =
        CropHistory.decode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key(accountId, number), "").orEmpty())

    fun save(context: Context, accountId: String, number: Int, list: List<CropEdit?>) {
        val e = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (list.all { it == null }) e.remove(key(accountId, number)) else e.putString(key(accountId, number), CropHistory.encode(list))
        e.apply()
    }
}
