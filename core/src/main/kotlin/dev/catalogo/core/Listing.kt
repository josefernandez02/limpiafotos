package dev.catalogo.core

import kotlinx.serialization.Serializable

/** Wording family of a garment. */
enum class Group(val label: String) {
    PANTS("Pantalones"),
    TOP("Arriba"),
    OUTER("Chaquetas y abrigos"),
    DRESS("Vestidos y faldas"),
    SET("Conjuntos"),
    SHOES("Calzado"),
    ACCESSORY("Accesorios"),
}

/**
 * [en] holds one English name per listing style, so accounts never share
 * wording. [aliases] are extra Spanish/English words for the search box.
 */
data class GarmentType(
    val id: String,
    val label: String,
    val group: Group,
    val en: List<String>,
    val aliases: String = "",
)

enum class KeywordKind(val label: String) {
    EPOCH("Época"),
    AESTHETIC("Estética"),
    FIT("Corte"),
    MATERIAL("Material"),
    DETAIL("Detalles"),
}

/** A search word; [groups] limits it to the garments where it makes sense (null = all). */
data class Keyword(
    val id: String,
    val label: String,
    val kind: KeywordKind,
    val en: List<String>,
    val help: String,
    val groups: Set<Group>? = null,
) {
    fun appliesTo(group: Group?): Boolean = groups == null || group == null || group in groups
}

@Serializable
enum class Condition(val label: String) {
    NEW_TAGS("Nuevo con etiquetas"),
    NEW("Nuevo sin etiquetas"),
    VERY_GOOD("Muy bueno"),
    GOOD("Bueno"),
    FAIR("Satisfactorio"),
}

/** What the user enters about an article; titles and descriptions derive from it. */
@Serializable
data class Listing(
    val type: String = "",
    val brand: String = "",
    val model: String = "",
    val size: String = "",
    val condition: Condition = Condition.VERY_GOOD,
    val keywords: List<String> = emptyList(),
    /** Free text about defects ("Tiene manchas"), added after the condition. */
    val notes: String = "",
    /** Chosen by the AI or the user; gives the letter size of W sizes. */
    val gender: Gender? = null,
    /** Old articles only: the brand is now always written by hand («Vintage Dressing»). */
    val noBrand: Boolean = false,
    /** No model to look for: the AI leaves it out of the title. */
    val noModel: Boolean = false,
    /** No size label: [size], if any, is an estimate ("aprox.") made from [flat] and the photos. */
    val noLabel: Boolean = false,
    /** Flat measures in cm, shown under the size when filled in. */
    val flat: Measures = Measures(),
    /** What the seller wants the AI to take into account ("Made in Argentina"). */
    val aiNotes: String = "",
)

/** Main flat measures in centimetres; blank when not taken. */
@Serializable
data class Measures(val waist: String = "", val chest: String = "", val length: String = "", val rise: String = "") {
    fun isEmpty() = waist.isBlank() && chest.isBlank() && length.isBlank() && rise.isBlank()

    /** "Pecho 55 cm · Largo 70 cm" or "Cintura 48 cm · Tiro 29 cm · Largo 105 cm": only the ones written. */
    fun text(): String = listOf("Pecho" to chest, "Cintura" to waist, "Tiro" to rise, "Largo" to length)
        .filter { it.second.isNotBlank() }
        .joinToString(" · ") { "${it.first} ${it.second.trim()} cm" }
}

@Serializable
data class ListingText(val title: String, val description: String)
