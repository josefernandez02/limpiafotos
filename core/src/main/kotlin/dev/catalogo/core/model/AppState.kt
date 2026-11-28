package dev.catalogo.core.model

import dev.catalogo.core.sales.LabelFile
import dev.catalogo.core.sales.LabelJob
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Photos being taken with the system camera for [accountId]'s album of [number]. */
@Serializable
data class Session(val number: Int, @SerialName("deviceId") val accountId: String, val startedAt: Long)

@Serializable
data class CatalogState(
    @SerialName("self") val mainAccount: VintedAccount,
    val digits: Int = 4,
    @SerialName("children") val otherAccounts: List<VintedAccount> = emptyList(),
    val articles: List<Article> = emptyList(),
    val session: Session? = null,
    val lots: List<Lot> = emptyList(),
    val sales: List<Sale> = emptyList(),
    /** When the PC last finished reading the sales, as it told this phone (0 = never). */
    val salesReadAt: Long = 0,
    /** Jobs of sold articles that no longer exist here, still to be taken off the PC. */
    val retired: List<PcTask> = emptyList(),
)

@Serializable
data class AppState(
    val deviceId: String,
    val baseFolder: String = "Pictures",
    @SerialName("parent") val catalog: CatalogState? = null,
    /** Google AI Studio key; stays on this phone, never synced. */
    val geminiKey: String = "",
    /** The PC that creates the drafts; stays on this phone, never synced. */
    val pc: PcLink? = null,
    /** Game groups switched off ("ava", "likes", "discord"): none of their games is ever played. Decided here, the PC just obeys. */
    val gamesOff: Set<String> = emptySet(),
    /** The shipping labels this phone last asked the PC for. */
    val labels: LabelJob = LabelJob(),
    /** The label PDFs saved here, until their sales are paid and they are deleted. */
    val labelPdfs: List<LabelFile> = emptyList(),
) {
    companion object {
        /** Folders where galleries list albums; the app picks the one that suits the phone. */
        val BASE_FOLDERS = listOf("DCIM", "Pictures/Gallery/owner", "Pictures")
    }
}
