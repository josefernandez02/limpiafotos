package dev.catalogo.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Vinted account the catalogue publishes in (the main one, or another that a second PC handles). */
@Serializable
data class VintedAccount(
    val id: String,
    val name: String,
    val prefix: String,
    /** Listing style (vocabulary and phrasing family) used for this phone's texts. */
    val style: Int = 0,
    /** Description template; blank means the style's default. */
    val template: String = "",
    /** Vinted user name of the account this phone publishes in ("shiorawear"). */
    @SerialName("account") val vintedName: String = "",
    /** Numeric Vinted member id, taken from the profile link; used to read the wardrobe. */
    val vintedId: String = "",
) {
    /** What the user calls this account: its Vinted name, or the phone's name until there is one. */
    val label: String get() = vintedName.ifBlank { name }
}
