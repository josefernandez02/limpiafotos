package dev.catalogo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An article as found in a Vinted wardrobe on the last check. */
@Serializable
data class VintedItem(
    val id: String,
    val price: Long? = null,
    val views: Int? = null,
    val favourites: Int? = null,
    /** "active", "reserved", "hidden" or "sold". */
    val status: String = "active",
)

/**
 * What a re-listed article must not repeat: the titles (per device id) and hashtags it was published with.
 * Kept until the AI has written new ones that differ enough ([RelistRules]); nothing is sent to the PC before.
 */
@Serializable
data class Relist(
    val titles: Map<String, String> = emptyMap(),
    val hashtags: List<String> = emptyList(),
)

/**
 * One catalogued article, identified by its number (shared by every device).
 * Once [released], it stays here until every device in [pendingRelease] has
 * confirmed deleting its album; only then the number becomes free again.
 */
@Serializable
data class Article(
    val number: Int,
    val createdAt: Long,
    val released: Boolean = false,
    val pendingRelease: Set<String> = emptySet(),
    val delivered: Map<String, Int> = emptyMap(),
    val listing: Listing? = null,
    val lotId: String? = null,
    /** Manual cost in cents, used when the article has no lot. */
    val cost: Long? = null,
    /** Devices whose Vinted account already has this article published. */
    val uploaded: Set<String> = emptySet(),
    val ai: AiResult? = null,
    /** Per device id: the listing found in that account's Vinted wardrobe. */
    val vinted: Map<String, VintedItem> = emptyMap(),
    /** Accounts (device ids) chosen to publish this article in; usually one. */
    val accounts: Set<String> = emptySet(),
    /** Price in cents it is listed at in Vinted. */
    val price: Long? = null,
    /** Its draft on the PC that uploads it (null = never sent). */
    @SerialName("desk") val pcTask: PcTask? = null,
    /** Photos the AI looks at, in order (the PC always gets the whole album); empty = all of them. */
    val photos: List<String> = emptyList(),
    /** How many times it was listed again (new photos, new title and hashtags); part of its code on the PC. */
    val round: Int = 0,
    /** Set while a re-listing waits for its new title and hashtags. */
    val relist: Relist? = null,
    /** Id of the sale in progress that holds this article ("" = none). It keeps its number, data and photos until it ends. */
    val soldIn: String = "",
) {
    /** Sold, but the order can still be cancelled: out of stock without being gone. */
    val held: Boolean get() = soldIn.isNotEmpty()

    /** There to be published, played and sold. */
    val inStock: Boolean get() = !released && !held

    /** Chosen accounts; articles from before the choice existed use where they went. */
    val targets: Set<String> get() = accounts.ifEmpty { uploaded + delivered.filterValues { it > 0 }.keys }

    /** Chosen for some account and not yet published in every chosen one. */
    val toUpload: Boolean get() = targets.isEmpty() || !uploaded.containsAll(targets)
}

/**
 * Where an article stands in an account, in one word: what the list and the
 * status tab show. Uploaded and played are different steps.
 */
enum class Stage(val label: String) {
    TO_UPLOAD("Por subir"),
    REVIEW("Revisar"),
    WORKING("En curso"),
    DRAFT("Borrador"),
    UPLOADED("Subido"),
    PLAYED("Jugado"),
    SOLD("Vendido"),
}
