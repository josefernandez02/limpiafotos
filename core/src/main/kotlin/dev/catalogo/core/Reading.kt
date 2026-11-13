package dev.catalogo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the labels and the garment say, read before writing the listing.
 *
 * The listing call looks at a few photos at once and decides many things, so it
 * tends to skim: a «100% ALGODÓN» label on the fourth photo ended up as
 * «Vaquero». A first, narrow call only reads (all chosen photos, larger, low
 * temperature) and its answer is given to the second call as facts. The
 * composition also decides the materials in code, whatever the second call says.
 */
@Serializable
data class Reading(
    /** "100% algodón", "98% algodón 2% elastano": as on the label, normalised. */
    val composition: List<String> = emptyList(),
    /** Country after «Made in» / «Hecho en», as written ("Argentina"). */
    val origin: String? = null,
    val sizeLabel: String? = null,
    val brand: String? = null,
    val model: String? = null,
    /** Visible flaws (stain, hole, fading) with where they are. */
    val flaws: List<String> = emptyList(),
    /** Other legible text and details worth knowing (lot number, patch, wash). */
    val details: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = composition.isEmpty() && origin == null && sizeLabel == null && brand == null && model == null &&
            flaws.isEmpty() && details.isEmpty()

    /** Vinted materials from the composition, largest share first (empty when it was not read). */
    val materials: List<String> get() = Reader.materials(composition)
}

object Reader {
    /** Photos read in the first call: enough to include the labels, usually the last ones taken. */
    const val PHOTOS = 8

    fun prompt(): String = """
        Eres un experto en ropa de segunda mano. Mira TODAS las fotos de UNA prenda con calma y transcribe lo que se lee y se ve. No escribas anuncios ni opines: solo lees.

        - composicion: cada línea de la etiqueta de composición con su porcentaje, tal cual (p. ej. "100% algodón", "98% cotton 2% elastane" → "98% algodón 2% elastano"). Tradúcela al español. Si no hay etiqueta legible, lista vacía. Nunca la deduzcas del aspecto del tejido.
        - hecho_en: el país de «Made in» / «Hecho en» / «Fabricado en», en español ("Argentina", "Estados Unidos"). Si no se lee, null.
        - talla_etiqueta: la talla impresa en la etiqueta, tal cual ("W32 L34", "M", "38"). Si no se lee, null.
        - marca y modelo: solo si se leen en etiquetas, parches o botones (p. ej. parche "505"). Si no, null.
        - defectos: manchas, rotos, desgaste o decoloración que se vean, con dónde. Si no se ve ninguno, lista vacía.
        - detalles: otros textos legibles o detalles útiles para venderla (lote, número de patrón, pestaña roja, lavado, botones). Máximo 6, cortos.
        Si dudas entre dos lecturas, no pongas ninguna. Es mejor dejar un campo vacío que inventar.
    """.trimIndent()

    fun request(photos: List<String>, model: String = "", thinking: Boolean = true): String = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt()) }
                    for (p in photos.take(PHOTOS)) {
                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", p) } }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema())
            put("temperature", 0.1)
            if (thinking) Ai.thinkingConfig(model)?.let { put("thinkingConfig", it) }
        }
    }.toString()

    private fun schema(): JsonObject {
        fun str(nullable: Boolean = false) = buildJsonObject { put("type", "STRING"); if (nullable) put("nullable", true) }
        fun arr(max: Int) = buildJsonObject { put("type", "ARRAY"); put("items", str()); put("maxItems", max) }
        val props = linkedMapOf<String, JsonElement>(
            "composicion" to arr(6),
            "hecho_en" to str(true),
            "talla_etiqueta" to str(true),
            "marca" to str(true),
            "modelo" to str(true),
            "defectos" to arr(5),
            "detalles" to arr(6),
        )
        return buildJsonObject {
            put("type", "OBJECT")
            put("properties", JsonObject(props))
            put("required", JsonArray(props.keys.map(::JsonPrimitive)))
            put("propertyOrdering", JsonArray(props.keys.map(::JsonPrimitive)))
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(answer: String): Reading {
        val o = runCatching { json.parseToJsonElement(answer.trim().removePrefix("```json").removeSuffix("```")).jsonObject }
            .getOrNull() ?: throw AiException("Respuesta de la IA ilegible")
        fun s(k: String) = o[k]?.str()?.replace(Regex("\\s+"), " ")?.trim()?.trim('"', '.')?.takeIf {
            it.isNotEmpty() && it.lowercase() !in setOf("null", "none", "n/a", "desconocido", "no se lee")
        }
        fun l(k: String, max: Int, len: Int) = (o[k] as? JsonArray).orEmpty().mapNotNull { it.str() }
            .map { it.replace(Regex("\\s+"), " ").trim().take(len) }.filter { it.isNotEmpty() }.distinct().take(max)
        return Reading(
            composition = l("composicion", 6, 60).filter { Regex("\\d").containsMatchIn(it) },
            origin = s("hecho_en")?.take(30),
            sizeLabel = s("talla_etiqueta")?.take(20),
            brand = s("marca")?.take(40),
            model = s("modelo")?.take(40),
            flaws = l("defectos", 5, 80),
            details = l("detalles", 6, 80),
        )
    }

    /** Words on composition labels (Spanish and English) to Vinted's materials. */
    private val WORDS: List<Pair<Regex, String>> = listOf(
        "algod[oó]n|cotton|coton|baumwolle" to "Algodón",
        "elastano|elastane|spandex|lycra|elasthan" to "Elastano",
        "poli[eé]ster|polyester" to "Poliéster",
        "nailon|nylon|poliamida|polyamide" to "Nailon",
        "viscosa|viscose|ray[oó]n|modal" to "Viscosa",
        "lino|linen|lin\\b" to "Lino",
        "seda|silk|soie" to "Seda",
        "merino" to "Merino",
        "cachemira|cashmere|cachemire" to "Cachemira",
        "alpaca" to "Alpaca",
        "angora" to "Angora",
        "lana|wool|laine" to "Lana",
        "acr[ií]lico|acrylic" to "Acrílico",
        "piel sint[eé]tica|cuero sint[eé]tico|polipiel|faux leather|pu\\b" to "Cuero sintético",
        "cuero|piel|leather" to "Piel",
        "ante|suede" to "Ante",
        "pana|corduroy" to "Pana",
        "plum[oó]n|down\\b" to "Plumón",
        "bamb[uú]|bamboo" to "Bambú",
    ).map { (re, m) -> Regex("(?i)\\b($re)") to m }

    /** Composition lines to up to [Vinted.MAX_MATERIALS] materials, largest share first. */
    fun materials(composition: List<String>): List<String> {
        val found = mutableListOf<Pair<String, Int>>()
        for (line in composition) {
            // «98% algodón 2% elastano» → (98, algodón), (2, elastano)
            val parts = Regex("(\\d{1,3})\\s*%\\s*([^\\d%]+)").findAll(line).toList()
                .ifEmpty { listOf(null) }
            for (part in parts) {
                val pct = part?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val name = part?.groupValues?.get(2) ?: line
                val m = WORDS.firstOrNull { (re, _) -> re.containsMatchIn(name) }?.second ?: continue
                found += m to pct
            }
        }
        return found.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }.map { it.key }
            .filter { it in Vinted.materials }.take(Vinted.MAX_MATERIALS)
    }

    /** The reading as compact facts for the listing call (null when nothing was read). */
    fun facts(r: Reading?): JsonObject? {
        if (r == null || r.isEmpty) return null
        return buildJsonObject {
            putJsonArray("composicion") { r.composition.forEach { add(it) } }
            put("hecho_en", r.origin)
            put("talla_etiqueta", r.sizeLabel)
            put("marca", r.brand)
            put("modelo", r.model)
            putJsonArray("defectos") { r.flaws.forEach { add(it) } }
            putJsonArray("detalles") { r.details.forEach { add(it) } }
        }
    }

    /** «100% algodón»: lower case, one space, no trailing dot. */
    fun compositionText(r: Reading?): String = r?.composition.orEmpty()
        .map { it.replace(Regex("\\s+"), " ").replace(Regex("(\\d)\\s*%"), "$1%").trim().trimEnd('.').lowercase() }
        .distinct().joinToString(", ")

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull
}
