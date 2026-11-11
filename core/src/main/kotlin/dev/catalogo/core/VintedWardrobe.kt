package dev.catalogo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToLong

/**
 * Reading what each account has published, the way Vinted's own wardrobe page
 * does: GET /api/v2/wardrobe/{member id}/items, one page of up to 100 at a time.
 * Listings are matched to articles by their title (each account has its own).
 */
object VintedWardrobe {
    const val PER_PAGE = 100
    const val MAX_PAGES = 20

    data class Remote(
        val id: String,
        val title: String,
        val price: Long?,
        val views: Int?,
        val favourites: Int?,
        val status: String,
    )

    data class Result(val found: Int, val newlyUploaded: Int, val sold: List<Int>, val unmatched: List<String>)

    fun path(memberId: String, page: Int) = "/api/v2/wardrobe/$memberId/items?page=$page&per_page=$PER_PAGE&order=relevance"

    /** (member id, user name) from a profile link such as vinted.es/member/123456-shiorawear. */
    fun profile(link: String): Pair<String, String>? {
        val m = Regex("(?:member|members)/(\\d{3,})(?:-([A-Za-z0-9_.\\-]+))?").find(link.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Items of one page; an empty list is the end. Throws if Vinted changed the format. */
    fun parse(body: String): List<Remote> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw CatalogException("Vinted ha respondido algo inesperado")
        val items = root["items"] as? JsonArray ?: throw CatalogException("Vinted ha cambiado el formato del armario")
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.str() ?: return@mapNotNull null
            val price = when (val p = o["price"]) {
                is JsonObject -> p["amount"]?.str()
                else -> p?.str()
            }?.replace(',', '.')?.toDoubleOrNull()?.let { (it * 100).roundToLong() }
            val status = when {
                o.flag("is_closed") || o.flag("is_sold") -> "sold"
                o.flag("is_reserved") -> "reserved"
                o.flag("is_hidden") || o.flag("is_draft") -> "hidden"
                else -> "active"
            }
            Remote(
                id = id,
                title = o["title"]?.str().orEmpty(),
                price = price,
                views = o["view_count"]?.str()?.toIntOrNull(),
                favourites = (o["favourite_count"] ?: o["favorite_count"])?.str()?.toIntOrNull(),
                status = status,
            )
        }
    }

    /** Lowercase letters and digits only, so "Levi's 501…" and "Levis 501…" compare equal. */
    fun key(title: String): String = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]"), "")

    /**
     * Applies one account's wardrobe: articles found are marked as uploaded
     * and keep their price, views and favourites. An article already linked
     * to a listing is recognised by id even if its title changed.
     */
    fun apply(s: ParentState, deviceId: String, items: List<Remote>, now: Long): Pair<ParentState, Result> {
        val device = Catalog.device(s, deviceId) ?: return s to Result(0, 0, emptyList(), emptyList())
        val byId = items.associateBy { it.id }
        val byTitle = items.groupBy { key(it.title) }
        val used = HashSet<String>()
        var found = 0
        var newly = 0
        val sold = ArrayList<Int>()
        val articles = s.articles.map { a ->
            if (a.released) return@map a
            val linked = a.vinted[deviceId]?.id?.let(byId::get)
            val remote = linked ?: Catalog.listingText(s, a.number, device)?.title?.let(::key)?.takeIf { it.isNotEmpty() }
                ?.let { k -> byTitle[k]?.firstOrNull { it.id !in used } }
            if (remote == null) return@map a
            used += remote.id
            found++
            if (deviceId !in a.uploaded) newly++
            if (remote.status == "sold") sold += a.number
            a.copy(
                uploaded = a.uploaded + deviceId,
                accounts = a.targets + deviceId,
                vinted = a.vinted + (deviceId to VintedItem(remote.id, remote.price, remote.views, remote.favourites, remote.status, now)),
            )
        }
        val unmatched = items.filter { it.id !in used && it.status != "sold" }.map { it.title }
        return s.copy(articles = articles) to Result(found, newly, sold, unmatched)
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
