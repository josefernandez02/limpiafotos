package dev.catalogo.core.model

import dev.catalogo.core.pc.PcPayload
import kotlinx.serialization.Serializable

/**
 * Where an article stands on its way to Vinted through the PC.
 * Local steps: [PREPARING] (AI and checks queued on this phone), [REVIEW] (something
 * needs the user), [SENDING]. Then the PC's own: pendiente, en_curso, borrador,
 * publicado, jugado, revisar, error.
 */
@Serializable
data class PcTask(
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
    val fixes: PcPayload.Fixes = PcPayload.Fixes(),
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

/** The PC that turns articles into Vinted drafts (whatsapp-likes), reached on the LAN or Tailscale. */
@Serializable
data class PcLink(val url: String, val token: String)
