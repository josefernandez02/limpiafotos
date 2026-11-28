package dev.catalogo.core.ai

import dev.catalogo.core.ai.AiPrompt.prompt
import dev.catalogo.core.listing.Garments
import dev.catalogo.core.listing.KeywordKind
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.listing.TagReader
import dev.catalogo.core.listing.TagReading
import dev.catalogo.core.listing.TrouserFits
import dev.catalogo.core.model.Relist
import dev.catalogo.core.vinted.VintedTaxonomy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Prompt, request and answer checking for the listing call. A first call
 * ([TagReader]) reads the labels; this one gets that reading as facts, observes the
 * photos (findings) and writes the listing in the same answer.
 */
object Ai {
    const val TITLE_MAX = 90
    const val PHOTOS = 3

    /** Fields the AI can flag as doubtful ("dudas"). */
    val DOUBT_FIELDS = listOf("categoria", "talla", "colores", "materiales", "marca")

    data class Account(val id: String, val prefix: String, val style: Int)

    /** Data the titles depend on; when it changes the stored titles are stale. */
    fun basis(l: Listing): String {
        val base = listOf(l.type, l.brand.trim().lowercase(), l.model.trim().lowercase(), l.keywords.sorted().joinToString(","))
            .joinToString("|")
        // Appended only when used, so results saved by older versions stay valid.
        val extra = buildString {
            if (l.noBrand) append("|sin-marca")
            if (l.aiNotes.isNotBlank()) append("|n:").append(l.aiNotes.trim().lowercase())
        }
        return base + extra
    }

    /**
     * Gemini generateContent body: prompt, photos (base64 JPEG) and the strict
     * answer schema. [thinking] keeps the model's reasoning short (not off):
     * enough to read labels and choose the category, much faster than the default.
     */
    fun request(
        listing: Listing,
        accounts: List<Account>,
        photos: List<String>,
        model: String = "",
        thinking: Boolean = true,
        reading: TagReading? = null,
        avoid: Relist? = null,
        rejected: Relist? = null,
    ): String = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt(listing, accounts, reading, avoid, rejected)) }
                    for (p in photos.take(PHOTOS)) {
                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", p) } }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema(accounts))
            put("temperature", if (rejected != null) 0.9 else 0.5)   // after a refused answer, more variety
            if (thinking) thinkingConfig(model)?.let { put("thinkingConfig", it) }
        }
    }.toString()

    /** Short reasoning: a small budget on 2.5 models, level "low" on 3 and later; nothing for unknown names. */
    fun thinkingConfig(model: String): JsonObject? {
        val version = Regex("gemini-(\\d+)(?:\\.(\\d+))?").find(model)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            version >= 3 -> buildJsonObject { put("thinkingLevel", "low") }
            model.contains("2.5") && !model.endsWith("-lite") -> buildJsonObject { put("thinkingBudget", 512) }
            else -> null
        }
    }

    private fun schema(accounts: List<Account>): JsonObject {
        fun str(enum: List<String>? = null, nullable: Boolean = false) = buildJsonObject {
            put("type", "STRING")
            if (nullable) put("nullable", true)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
        }
        fun arr(item: JsonObject, max: Int? = null) = buildJsonObject {
            put("type", "ARRAY"); put("items", item); if (max != null) put("maxItems", max)
        }
        fun obj(props: Map<String, JsonElement>, required: List<String> = props.keys.toList()) = buildJsonObject {
            put("type", "OBJECT")
            put("properties", JsonObject(props))
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("propertyOrdering", JsonArray(props.keys.map(::JsonPrimitive)))
        }
        val props = linkedMapOf<String, JsonElement>(
            "hallazgos" to arr(obj(mapOf("detalle" to str(), "prueba" to str())), max = 6),
            "tipo_prenda" to str(Garments.types.map { it.id }, nullable = true),
            "palabras" to arr(str(Garments.keywords.filter { it.kind != KeywordKind.EPOCH }.map { it.id })),
            "sexo" to str(listOf("hombre", "mujer"), nullable = true),
            // Free text checked afterwards: an enum of hundreds of paths makes the answer much slower.
            "corte" to str(TrouserFits.ALL, nullable = true),
            "categoria" to str(nullable = true),
            "colores" to arr(str(VintedTaxonomy.colors), max = VintedTaxonomy.MAX_COLORS),
            "materiales" to arr(str(VintedTaxonomy.materials), max = VintedTaxonomy.MAX_MATERIALS),
            "dudas" to arr(str(DOUBT_FIELDS)),
            "marca" to str(nullable = true),
            "modelo" to str(nullable = true),
            "talla" to str(nullable = true),
            "titulos" to obj(accounts.associate { it.prefix to str() }),
            "hashtags" to arr(str(), max = 14),
        )
        return obj(props)
    }
}
