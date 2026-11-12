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
object Draft {
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

    /** Unique for this article's life: the number plus when it was created (numbers are reused). */
    fun ref(device: Device, article: Article, digits: Int): String =
        Sku.format(device.prefix, article.number, digits) + "-" + article.createdAt.toString(36)

    /** Size as the form lists it: the W of a waist size ("W32 L34" → "W32"), else the label. */
    fun vintedSize(listing: Listing): String {
        val size = listing.size.trim()
        val w = Regex("(?i)\\bW\\s?(\\d{2})\\b").find(size)?.groupValues?.get(1)
        return if (w != null) "W$w" else size
    }

    fun condition(c: Condition): String = when (c) {
        Condition.NEW_TAGS -> "nuevo_con_etiquetas"
        Condition.NEW -> "nuevo_sin_etiquetas"
        Condition.VERY_GOOD -> "muy_bueno"
        Condition.GOOD -> "bueno"
        Condition.FAIR -> "satisfactorio"
    }

    fun build(p: ParentState, article: Article, device: Device, fixes: Fixes = Fixes()): Built {
        val ref = ref(device, article, p.digits)
        val problems = mutableListOf<String>()
        val doubts = linkedSetOf<String>()
        val listing = article.listing
        val ai = Catalog.freshAi(p, article.number)
        if (listing == null) problems += "Faltan los datos del artículo"
        if (device.account.isBlank()) problems += "La cuenta de ${device.name} no tiene nombre de Vinted (Ajustes)"
        if (article.price == null || article.price <= 0) problems += "Falta el precio de Vinted"
        if (listing != null && listing.brand.isBlank() && !listing.noBrand) problems += "Falta la marca (o marca «Sin marca»)"
        if (ai == null) problems += "Falta generar el anuncio con IA"
        if (listing == null || ai == null) return Built(ref, null, problems, emptyList())

        val text = Catalog.listingText(p, article.number, device)
        if (text == null || text.title.isBlank()) problems += "No hay título"

        val category = (fixes.category ?: ai.category)?.takeIf { it in Vinted.categories }
        if (category == null) doubts += "categoria"
        val size = fixes.size ?: vintedSize(listing)
        if (size.isBlank()) doubts += "talla"
        val colors = (fixes.colors ?: ai.colors.mapNotNull(Vinted::color)).filter { it in Vinted.colors }
            .distinct().take(Vinted.MAX_COLORS)
        if (colors.isEmpty()) doubts += "colores"
        val materials = (fixes.materials ?: ai.materials).filter { it in Vinted.materials }.distinct()
            .take(Vinted.MAX_MATERIALS)

        // The AI's own doubts, unless the user already settled that field.
        for (d in ai.doubts) {
            val settled = when (d) {
                "categoria" -> fixes.category != null
                "talla" -> fixes.size != null
                "colores" -> fixes.colors != null
                "materiales" -> fixes.materials != null
                else -> false
            }
            if (!settled && d != "marca") doubts += d     // "marca" is handled below
        }
        // An estimated size (no label) is always checked by the user.
        if (listing.noLabel && fixes.size == null) doubts += "talla"
        if ("marca" in ai.doubts && !listing.noBrand && !fixes.brandChecked) doubts += "marca"

        if (problems.isNotEmpty()) return Built(ref, null, problems, doubts.toList())
        val payload = buildJsonObject {
            put("ref", ref)
            put("cuenta", device.account)
            put("titulo", text!!.title.take(ListingWriter.TITLE_MAX))
            put("descripcion", text.description.take(ListingWriter.DESCRIPTION_MAX))
            put("categoria", category)
            put("marca", if (listing.noBrand) null else listing.brand.trim())
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
