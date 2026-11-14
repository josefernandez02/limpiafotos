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
    /** Vinted user name of the account this phone publishes in ("shiorawear"). */
    val account: String = "",
    /** Numeric Vinted member id, taken from the profile link; used to read the wardrobe. */
    val vintedId: String = "",
) {
    /** What the user calls this account: its Vinted name, or the phone's name until there is one. */
    val label: String get() = account.ifBlank { name }
}

/** An article as found in a Vinted wardrobe on the last check. */
@Serializable
data class VintedItem(
    val id: String,
    val price: Long? = null,
    val views: Int? = null,
    val favourites: Int? = null,
    /** "active", "reserved", "hidden" or "sold". */
    val status: String = "active",
    val checkedAt: Long = 0,
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
    val desk: Desk? = null,
    /** Photos the AI looks at, in order (the PC always gets the whole album); empty = all of them. */
    val photos: List<String> = emptyList(),
) {
    /** Chosen accounts; articles from before the choice existed use where they went. */
    val targets: Set<String> get() = accounts.ifEmpty { uploaded + delivered.filterValues { it > 0 }.keys }

    /** Chosen for some account and not yet published in every chosen one. */
    val toUpload: Boolean get() = targets.isEmpty() || !uploaded.containsAll(targets)
}

/**
 * Where an article stands on its way to Vinted through the PC.
 * Local steps: [PREPARING] (AI and checks queued on this phone), [REVIEW] (something
 * needs the user), [SENDING]. Then the PC's own: pendiente, en_curso, borrador,
 * publicado, jugado, revisar, error.
 */
@Serializable
data class Desk(
    /** Unique for this article's life on the PC (numbers are reused after a sale). */
    val ref: String,
    val status: String,
    /** Why it is in review or failed, in plain words. */
    val message: String = "",
    /** Fields to check by hand ([Ai.DOUBT_FIELDS] plus missing data). */
    val doubts: List<String> = emptyList(),
    val itemId: String = "",
    val url: String = "",
    val game: String = "",
    val at: Long = 0,
    /** What the user settled in «Revisar». */
    val fixes: Draft.Fixes = Draft.Fixes(),
    /** What to do with it: [Mode]. */
    val mode: String = Mode.UPLOAD_PLAY,
    /** A state set by hand that the PC still has to hear about ("" = nothing pending). */
    val pending: String = "",
) {
    val needsUser: Boolean get() = status == REVIEW || status == "revisar" || status == "error"
    val working: Boolean get() = status == PREPARING || status == SENDING || status == "pendiente" || status == "en_curso"

    companion object {
        const val PREPARING = "preparando"
        const val REVIEW = "revisar_movil"
        const val SENDING = "enviando"

        /** What the user reads for each status. */
        fun label(status: String): String = when (status) {
            PREPARING -> "Preparando con IA"
            REVIEW, "revisar" -> "Revisar"
            SENDING -> "Enviando al PC"
            "pendiente" -> "En cola en el PC"
            "en_curso" -> "Creando borrador"
            "borrador" -> "Borrador listo"
            "publicado" -> "Publicado"
            "jugado" -> "Jugado"
            "error" -> "Error"
            else -> status
        }
    }
}

/** What the user wants done with an article in Vinted. */
object Mode {
    /** Only the draft: the PC never publishes it. */
    const val DRAFT = "borrador"
    /** Draft, published when a game needs a link, and played (the usual). */
    const val UPLOAD_PLAY = "subir_jugar"
    /** Already on Vinted (uploaded by hand): only its link goes to a game. */
    const val PLAY = "jugar"

    fun label(mode: String): String = when (mode) {
        DRAFT -> "Solo borrador"
        PLAY -> "Solo jugar"
        else -> "Subir y jugar"
    }
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

/** The PC that turns articles into Vinted drafts (whatsapp-likes), reached on the LAN or Tailscale. */
@Serializable
data class PcLink(val url: String, val token: String)

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
    /** The PC that creates the drafts; stays on this phone, never synced. */
    val pc: PcLink? = null,
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
