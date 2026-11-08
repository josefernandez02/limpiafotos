package dev.catalogo.core

import kotlinx.serialization.Serializable

@Serializable
enum class Role { PARENT, CHILD }

/** A phone taking part. [secret] is the pairing key (children only). */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val prefix: String,
    val secret: String = "",
    /** Listing style (vocabulary and phrasing family) used for this phone's texts. */
    val style: Int = 0,
    /** Description template; blank means the style's default. */
    val template: String = "",
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
)

/** Photos being taken with the system camera for [deviceId]'s album of [number]. */
@Serializable
data class Session(val number: Int, val deviceId: String, val startedAt: Long)

@Serializable
data class ParentState(
    val self: Device,
    val digits: Int = 4,
    val children: List<Device> = emptyList(),
    val articles: List<Article> = emptyList(),
    val session: Session? = null,
    val configVersion: Long = 1,
    val lots: List<Lot> = emptyList(),
    val sales: List<Sale> = emptyList(),
)

@Serializable
data class ChildState(
    val self: Device,
    val parentId: String,
    val secret: String,
    val digits: Int,
    val configVersion: Long = 0,
    /** Numbers released on this device that the parent has not acknowledged yet. */
    val pendingReleases: Set<Int> = emptySet(),
    /** Sale price (cents) entered here for each pending release. */
    val salePrices: Map<Int, Long> = emptyMap(),
    /** Title and description for each article, written by the parent for this phone. */
    val listings: Map<Int, ListingText> = emptyMap(),
)

@Serializable
data class AppState(
    val deviceId: String,
    val baseFolder: String = "Pictures",
    val parent: ParentState? = null,
    val child: ChildState? = null,
    /** Chosen as secondary but not paired yet. */
    val secondary: Boolean = false,
    /** Google AI Studio key; stays on this phone, never synced. */
    val geminiKey: String = "",
) {
    val role: Role? get() = when {
        parent != null -> Role.PARENT
        child != null || secondary -> Role.CHILD
        else -> null
    }

    companion object {
        /** Folders where galleries list albums; the app picks one per phone automatically. */
        val BASE_FOLDERS = listOf("DCIM", "Pictures/Gallery/owner", "Pictures")
    }
}
