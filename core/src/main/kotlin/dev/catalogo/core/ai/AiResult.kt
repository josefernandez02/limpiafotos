package dev.catalogo.core.ai

import dev.catalogo.core.listing.Garments
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.listing.TagReader
import dev.catalogo.core.listing.TagReading
import dev.catalogo.core.vinted.Gender
import dev.catalogo.core.vinted.VintedTaxonomy
import kotlinx.serialization.Serializable

/** What the AI decided for an article, kept with it. Titles are per account id. */
@Serializable
data class AiResult(
    /** [Ai.basis] of the listing the titles were written for; other data means stale titles. */
    val basis: String,
    val category: String? = null,
    val colors: List<String> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val model: String = "",
    /** Without "#" and without the SKU, which the description adds last. */
    val hashtags: List<String> = emptyList(),
    /** How long the answer took, to see if the AI is getting slow. */
    val seconds: Int = 0,
    /** Up to 3 of [VintedTaxonomy.materials], as the label (or the fabric) shows. */
    val materials: List<String> = emptyList(),
    /** Fields the AI is not sure about ([Ai.DOUBT_FIELDS]): the user checks them before the draft. */
    val doubts: List<String> = emptyList(),
    /** Composition as read on the label ("100% algodón"); empty when there was no label. */
    val composition: String = "",
)

/** One answer of the AI, already checked against the closed lists. */
data class AiSuggestion(
    val type: String?,
    val keywords: List<String>,
    val gender: Gender?,
    val category: String?,
    val colors: List<String>,
    val sizeLabel: String?,
    /** Account id to title. */
    val titles: Map<String, String>,
    val findings: List<String>,
    val hashtags: List<String> = emptyList(),
    /** Brand and model read in the photos, only when the user left them blank. */
    val brand: String? = null,
    val model: String? = null,
    val materials: List<String> = emptyList(),
    val doubts: List<String> = emptyList(),
    /** What the first call read in the labels (null when it was not made or failed). */
    val reading: TagReading? = null,
) {
    /** The listing with the suggestions applied; what the user typed always wins. */
    fun applyTo(listing: Listing): Listing {
        val newType = listing.type.ifEmpty { type.orEmpty() }
        val group = Garments.type(newType)?.group
        val words = (listing.keywords + keywords).distinct().filter { Garments.keyword(it)?.appliesTo(group) == true }
        val canBrand = !listing.noBrand
        return listing.copy(
            type = newType,
            keywords = words,
            gender = listing.gender ?: gender,
            size = listing.size.ifBlank { sizeLabel.orEmpty() },
            brand = if (canBrand) listing.brand.ifBlank { brand.orEmpty() } else listing.brand,
            model = if (canBrand && !listing.noModel) listing.model.ifBlank { model.orEmpty() } else listing.model,
        )
    }

    fun result(applied: Listing, model: String, seconds: Int = 0) =
        AiResult(Ai.basis(applied), category, colors, titles, model, hashtags, seconds, materials, doubts,
            TagReader.compositionText(reading))
}

class AiException(message: String) : Exception(message)
