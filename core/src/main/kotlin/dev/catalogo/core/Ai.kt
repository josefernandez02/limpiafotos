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

/** What the AI decided for an article, kept with it. Titles are per device id. */
@Serializable
data class AiResult(
    /** [Ai.basis] of the listing the titles were written for; other data means stale titles. */
    val basis: String,
    val category: String? = null,
    val colors: List<String> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val model: String = "",
)

/** One answer of the AI, already checked against the closed lists. */
data class AiSuggestion(
    val type: String?,
    val keywords: List<String>,
    val gender: Gender?,
    val category: String?,
    val colors: List<String>,
    val sizeLabel: String?,
    /** Device id to title. */
    val titles: Map<String, String>,
    val findings: List<String>,
) {
    /** The listing with the suggestions applied; what the user typed always wins. */
    fun applyTo(listing: Listing): Listing {
        val newType = listing.type.ifEmpty { type.orEmpty() }
        val group = Garments.type(newType)?.group
        val words = (listing.keywords + keywords).distinct().filter { Garments.keyword(it)?.appliesTo(group) == true }
        return listing.copy(
            type = newType,
            keywords = words,
            gender = listing.gender ?: gender,
            size = listing.size.ifBlank { sizeLabel.orEmpty() },
        )
    }

    fun result(applied: Listing, model: String) = AiResult(Ai.basis(applied), category, colors, titles, model)
}

class AiException(message: String) : Exception(message)

/**
 * Prompt, request and answer checking for one Gemini call per article. The
 * call observes the photos first (findings) and then writes, in the same
 * answer, so each article costs a single request.
 */
object Ai {
    const val TITLE_MAX = 90
    const val PHOTOS = 3

    data class Account(val id: String, val prefix: String, val style: Int)

    /** Data the titles depend on; when it changes the stored titles are stale. */
    fun basis(l: Listing): String =
        listOf(l.type, l.brand.trim().lowercase(), l.model.trim().lowercase(), l.keywords.sorted().joinToString(","))
            .joinToString("|")

    private val VOICES = listOf(
        "Directa, estilo tienda vintage: marca y modelo delante, luego estética/época, corte, material, tipo de prenda y un detalle.",
        "Distinta a la otra: empieza por la época o la estética, usa sinónimos (Denim Pants, Jumper, Hooded Sweatshirt, Trainers) y otro orden.",
    )

    fun prompt(listing: Listing, accounts: List<Account>): String {
        val type = Garments.type(listing.type)
        val epochs = listing.keywords.mapNotNull(Garments::keyword).filter { it.kind == KeywordKind.EPOCH }
        val user = buildJsonObject {
            put("marca", listing.brand.trim().ifEmpty { null })
            put("modelo", listing.model.trim().ifEmpty { null })
            put("talla", listing.size.trim().ifEmpty { null })
            put("sexo", listing.gender?.label?.lowercase())
            put("tipo_prenda", type?.id)
            putJsonArray("epoca") { epochs.forEach { add(it.en[0]) } }
            putJsonArray("palabras") {
                listing.keywords.mapNotNull(Garments::keyword).filter { it.kind != KeywordKind.EPOCH }.forEach { add(it.id) }
            }
        }
        return """
            Eres un experto en vender ropa de segunda mano en Vinted España. Analizas las fotos de UNA prenda y devuelves los datos de su anuncio.

            1. REGLAS
            - DATOS_DEL_USUARIO son correctos y mandan: no cambies marca, modelo, talla, sexo ni época si vienen.
            - Describe solo lo que se ve en las fotos. No deduzcas por lo que una marca o modelo "suele tener".
            - Si algo no se ve con seguridad, déjalo vacío (null o lista vacía). Mejor quedarse corto que inventar.
            - Devuelve solo el JSON pedido.

            2. OBSERVA (campo "hallazgos", rellénalo antes que nada)
            Etiquetas, logos, bordados, parches, texto legible, tejido, lavado, corte y detalles. Cada hallazgo con dónde se ve.

            3. CLASIFICA
            - tipo_prenda: uno de TIPOS. Si el usuario ya lo dio, devuelve ese.
            - palabras: de PALABRAS, solo las que se vean claramente. No devuelvas épocas.
            - sexo: "hombre" o "mujer", según corte, marca, modelo, talla y etiquetas. Si dudas, el más habitual para esa prenda en Vinted.
            - categoria: la ruta MÁS CONCRETA de CATEGORIAS que encaje con la prenda y con el sexo elegido.
            - colores: 1 o 2 de COLORES, el color del TEJIDO (no del fondo, la luz, las sombras, etiquetas ni costuras). Principal primero.
            - talla_etiqueta: solo si el usuario no dio talla y se lee en una etiqueta, escrita tal cual. Si no, null.

            4. TÍTULOS (en inglés, uno por cuenta dentro de "titulos")
            El objetivo es salir en más búsquedas de Vinted y que den ganas de abrir el anuncio.
            - Empieza por lo más buscado: la marca y el modelo si existen. El tipo de prenda va siempre.
            - Añade lo que la gente escribe al buscar: estética, la época del usuario, corte, material y detalles de PALABRAS que se vean.
            - Época: solo la de DATOS_DEL_USUARIO. Si no hay, ninguna década ni "Y2K". "Vintage" solo si está entre las palabras.
            - Color: solo si es un tono con nombre que se busca y hace atractiva la prenda (Navy, Burgundy, Olive, Forest Green, Cream, Brown, Baby Blue…). Nunca Black, White, Grey ni Blue genéricos, salvo en vaqueros negros o grises ("Black Jeans").
            - Nunca talla, estado, precio, SKU, emojis, signos, mayúsculas sostenidas ni relleno ("Amazing", "Look", "Must Have", "Rare" si no está entre las palabras).
            - Title Case, máximo $TITLE_MAX caracteres, que se lea natural y no como una lista de etiquetas.
            - Cada cuenta tiene su voz (CUENTAS). Los títulos de cuentas distintas no pueden tener el mismo orden ni la misma redacción.

            DATOS_DEL_USUARIO
            $user

            CUENTAS
            ${accounts.joinToString("\n") { "${it.prefix}: ${VOICES[it.style.mod(VOICES.size)]}" }}

            TIPOS (id|nombre)
            ${Garments.types.joinToString("\n") { "${it.id}|${it.en[0]}" }}

            PALABRAS (id|en inglés|qué significa)
            ${Garments.keywords.filter { it.kind != KeywordKind.EPOCH }.joinToString("\n") { "${it.id}|${it.en[0]}|${it.help}" }}

            CATEGORIAS y COLORES: los valores permitidos están en el esquema de respuesta.
        """.trimIndent()
    }

    /** Gemini generateContent body: prompt, photos (base64 JPEG) and the strict answer schema. */
    fun request(listing: Listing, accounts: List<Account>, photos: List<String>): String = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt(listing, accounts)) }
                    for (p in photos.take(PHOTOS)) {
                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", p) } }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema(accounts))
            put("temperature", 0.6)
        }
    }.toString()

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
            "hallazgos" to arr(obj(mapOf("detalle" to str(), "prueba" to str()))),
            "tipo_prenda" to str(Garments.types.map { it.id }, nullable = true),
            "palabras" to arr(str(Garments.keywords.filter { it.kind != KeywordKind.EPOCH }.map { it.id })),
            "sexo" to str(listOf("hombre", "mujer"), nullable = true),
            "categoria" to str(Vinted.categories, nullable = true),
            "colores" to arr(str(Vinted.colors), max = 2),
            "talla_etiqueta" to str(nullable = true),
            "titulos" to obj(accounts.associate { it.prefix to str() }),
        )
        return obj(props)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Text of the first candidate of a generateContent response. */
    fun answerText(response: String): String {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: throw AiException("Respuesta de la IA ilegible")
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AiException("La IA no ha respondido (${(root["promptFeedback"] as? JsonObject)?.get("blockReason")?.str() ?: "sin motivo"})")
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
        return parts?.mapNotNull { (it as? JsonObject)?.get("text")?.str() }?.joinToString("")?.takeIf { it.isNotBlank() }
            ?: throw AiException("La IA ha devuelto una respuesta vacía")
    }

    /** Checks every value against the closed lists; anything unknown is dropped. */
    fun parse(answer: String, listing: Listing, accounts: List<Account>): AiSuggestion {
        val o = runCatching { json.parseToJsonElement(answer.trim().removePrefix("```json").removeSuffix("```")).jsonObject }
            .getOrNull() ?: throw AiException("Respuesta de la IA ilegible")
        val type = o["tipo_prenda"]?.str()?.takeIf { Garments.type(it) != null }
        val group = Garments.type(listing.type.ifEmpty { type.orEmpty() })?.group
        val keywords = o.strings("palabras").distinct().filter { id ->
            Garments.keyword(id)?.let { it.kind != KeywordKind.EPOCH && it.appliesTo(group) } == true
        }
        val gender = when (o["sexo"]?.str()?.lowercase()) {
            "hombre" -> Gender.MEN
            "mujer" -> Gender.WOMEN
            else -> null
        }
        val finalGender = listing.gender ?: gender
        val category = o["categoria"]?.str()?.takeIf { it in Vinted.categories }
            ?.takeIf { finalGender == null || it.startsWith(finalGender.label + " ") }
        val colors = o.strings("colores").filter { it in Vinted.colors }.distinct().take(2)
        val sizeLabel = o["talla_etiqueta"]?.str()?.trim()?.take(20)?.takeIf { it.isNotEmpty() && listing.size.isBlank() }
        val titleObj = o["titulos"] as? JsonObject
        val seen = HashSet<String>()
        val titles = accounts.mapNotNull { a ->
            titleObj?.get(a.prefix)?.str()?.let(::cleanTitle)?.takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }?.let { a.id to it }
        }.toMap()
        val findings = (o["hallazgos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("detalle")?.str() }
        return AiSuggestion(type, keywords, gender, category, colors, sizeLabel, titles, findings)
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s+"), " ").trim().trim('"', '.', '-', ' ')
        if (t.length > ListingWriter.TITLE_MAX) t = t.take(ListingWriter.TITLE_MAX).substringBeforeLast(' ')
        return t
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it.str() }
}
