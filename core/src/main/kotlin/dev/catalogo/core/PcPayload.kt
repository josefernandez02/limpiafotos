package dev.catalogo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the PC (whatsapp-likes) needs to fill Vinted's upload form for one
 * article and one account, checked here before anything is sent.
 *
 * [Built.problems] block the draft (the user has to fix them in the editor);
 * [Built.doubts] are fields filled without certainty that the user confirms
 * in «Revisar» before the article goes to the PC.
 */
object PcPayload {
    data class Built(
        val ref: String,
        val payload: JsonObject?,
        val problems: List<String>,
        val doubts: List<String>,
    ) {
        val ready: Boolean get() = payload != null && problems.isEmpty() && doubts.isEmpty()
    }

    /** Overrides chosen by the user in «Revisar»: they win over the AI. */
    @Serializable
    data class Fixes(
        val category: String? = null,
        val size: String? = null,
        val colors: List<String>? = null,
        val materials: List<String>? = null,
        /** The user looked at the brand the AI was unsure about and it is right. */
        val brandChecked: Boolean = false,
    )

    /** Unique for this article's life: the number plus when it was created (numbers are reused), and which listing it is (re-listed ones are new to the PC). */
    fun ref(vintedAccount: VintedAccount, article: Article, digits: Int): String =
        Sku.format(vintedAccount.prefix, article.number, digits) + "-" + article.createdAt.toString(36) +
            if (article.round > 0) "r${article.round}" else ""

    fun condition(c: Condition): String = when (c) {
        Condition.NEW_TAGS -> "nuevo_con_etiquetas"
        Condition.NEW -> "nuevo_sin_etiquetas"
        Condition.VERY_GOOD -> "muy_bueno"
        Condition.GOOD -> "bueno"
        Condition.FAIR -> "satisfactorio"
    }

    fun build(p: CatalogState, article: Article, vintedAccount: VintedAccount, fixes: Fixes = Fixes()): Built {
        val ref = ref(vintedAccount, article, p.digits)
        val problems = mutableListOf<String>()
        val doubts = linkedSetOf<String>()
        val listing = article.listing
        val ai = Catalog.freshAi(p, article.number)
        if (listing == null) problems += "Faltan los datos del artículo"
        if (vintedAccount.vintedName.isBlank()) problems += "La cuenta de ${vintedAccount.name} no tiene nombre de Vinted (Ajustes)"
        if (article.price == null || article.price <= 0) problems += "Falta el precio de Vinted"
        // Always written by hand now (also old «Sin marca» articles: «Vintage Dressing», «Vintage»…).
        if (listing != null && listing.brand.isBlank()) problems += "Falta la marca (escríbela en Editar)"
        if (ai == null) problems += "Falta generar el anuncio con IA"
        if (article.relist != null) problems += "Al resubir, el título y los hashtags tienen que cambiar (los escribe la IA al preparar el borrador)"
        if (listing == null || ai == null) return Built(ref, null, problems, emptyList())

        val text = Catalog.listingText(p, article.number, vintedAccount)
        if (text == null || text.title.isBlank()) problems += "No hay título"

        val category = (fixes.category ?: ai.category)?.takeIf { it in VintedTaxonomy.categories }
        if (category == null) doubts += "categoria"
        // The size exactly as the form offers it for this category; without certainty, «Revisar».
        val options = category?.let(VintedSizes::options).orEmpty()
        val size = when {
            category == null || options.isEmpty() -> ""          // no category yet, or it asks for no size
            fixes.size != null && fixes.size in options -> fixes.size
            else -> VintedSizes.resolve(category, listing) ?: "".also { doubts += "talla" }
        }
        val colors = (fixes.colors ?: ai.colors.mapNotNull(VintedTaxonomy::color)).filter { it in VintedTaxonomy.colors }
            .distinct().take(VintedTaxonomy.MAX_COLORS)
        if (colors.isEmpty()) doubts += "colores"
        val materials = (fixes.materials ?: ai.materials).filter { it in VintedTaxonomy.materials }.distinct()
            .take(VintedTaxonomy.MAX_MATERIALS)

        // The AI's own doubts, unless the user already settled that field.
        for (d in ai.doubts) {
            val settled = when (d) {
                "categoria" -> fixes.category != null
                "talla" -> (fixes.size != null && fixes.size in options) || listing.noLabel
                "colores" -> fixes.colors != null
                "materiales" -> fixes.materials != null
                else -> false
            }
            if (!settled && d != "marca") doubts += d     // "marca" is handled below
        }
        // Without a label the AI estimates the size from the measures and photos: the
        // seller could not say better, so it is not asked (only if it cannot be used at all).
        if ("marca" in ai.doubts && !listing.noBrand && !fixes.brandChecked) doubts += "marca"

        if (problems.isNotEmpty()) return Built(ref, null, problems, doubts.toList())
        val payload = buildJsonObject {
            put("ref", ref)
            put("cuenta", vintedAccount.vintedName)
            put("modo", article.pcTask?.mode ?: Mode.UPLOAD_PLAY)
            put("titulo", text!!.title.take(ListingWriter.TITLE_MAX))
            put("descripcion", text.description.take(ListingWriter.DESCRIPTION_MAX))
            put("categoria", category)
            put("marca", listing.brand.trim())
            put("talla", size.ifBlank { null })
            put("estado", condition(listing.condition))
            putJsonArray("colores") { colors.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("materiales") { materials.forEach { add(JsonPrimitive(it)) } }
            put("precio", article.price!! / 100.0)
            put("envio", "pequeno")
        }
        return Built(ref, payload, emptyList(), doubts.toList())
    }
}
