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
    /** Without "#" and without the SKU, which the description adds last. */
    val hashtags: List<String> = emptyList(),
    /** How long the answer took, to see if the AI is getting slow. */
    val seconds: Int = 0,
    /** Up to 3 of [Vinted.materials], as the label (or the fabric) shows. */
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
    /** Device id to title. */
    val titles: Map<String, String>,
    val findings: List<String>,
    val hashtags: List<String> = emptyList(),
    /** Brand and model read in the photos, only when the user left them blank. */
    val brand: String? = null,
    val model: String? = null,
    val materials: List<String> = emptyList(),
    val doubts: List<String> = emptyList(),
    /** What the first call read in the labels (null when it was not made or failed). */
    val reading: Reading? = null,
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
            Reader.compositionText(reading))
}

class AiException(message: String) : Exception(message)

/**
 * Prompt, request and answer checking for the listing call. A first call
 * ([Reader]) reads the labels; this one gets that reading as facts, observes the
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

    /** How each listing style orders its title. */
    private val VOICES = listOf(
        """Marca Modelo [Corte con nombre propio] Prenda Vintage Época Classic [Corte] Fit Color
           Levi's 501 Jeans Vintage 90s Classic Straight Fit Blue
           Levi's 517 Bootcut Jeans Vintage 90s Classic Straight Fit Khaki
           Levi's 711 Skinny Jeans Vintage 2000s Classic Skinny Fit Black
           Levi's 512 Made in USA Slim Fit Tapered Leg Jeans Vintage 90s Classic White
           Sin marca: Flare Jeans Vintage Y2K Low Rise Dark Blue · Leather Jacket Vintage 90s Biker Black""",
        """Vintage Época Marca Modelo Corte Prenda [Estilo] Color, con sinónimos (Straight Leg, Denim, Trousers…)
           Vintage 90s Levis 501 Straight Leg Jeans Washed Blue
           Vintage 2000s Levis 711 Skinny Denim Jeans Black
           Sin marca: Vintage Y2K Low Rise Flared Jeans Dark Blue · Vintage 90s Biker Leather Jacket Black""",
    )

    fun prompt(listing: Listing, accounts: List<Account>, reading: Reading? = null): String {
        val type = Garments.type(listing.type)
        val epochs = listing.keywords.mapNotNull(Garments::keyword).filter { it.kind == KeywordKind.EPOCH }
        val user = buildJsonObject {
            put("marca", if (listing.noBrand) "SIN MARCA" else listing.brand.trim().ifEmpty { null })
            put("modelo", if (listing.noModel) "SIN MODELO" else listing.model.trim().ifEmpty { null })
            put("talla", listing.size.trim().ifEmpty { null })
            put("sin_etiqueta_de_talla", listing.noLabel)
            putJsonObject("medidas_en_plano_cm") {
                put("cintura", listing.flat.waist.trim().ifEmpty { null })
                put("pecho", listing.flat.chest.trim().ifEmpty { null })
                put("largo", listing.flat.length.trim().ifEmpty { null })
            }
            put("sexo", listing.gender?.label?.lowercase())
            put("tipo_prenda", type?.id)
            putJsonArray("epoca") { epochs.forEach { add(it.en[0]) } }
            putJsonArray("palabras") {
                listing.keywords.mapNotNull(Garments::keyword).filter { it.kind != KeywordKind.EPOCH }.forEach { add(it.id) }
            }
            put("notas_del_vendedor", listing.aiNotes.trim().ifEmpty { null })
        }
        return """
            Eres un experto en vender ropa de segunda mano en Vinted España. Analizas las fotos de UNA prenda y devuelves los datos de su anuncio. Puede ser de cualquier marca o no tener marca, y de cualquier tipo: vaqueros, chaquetas, sudaderas, camisas, calzado, bolsos…

            1. REGLAS
            - DATOS_DEL_USUARIO son correctos y mandan: no cambies lo que viene relleno.
            - notas_del_vendedor son hechos que da el vendedor aunque no se vean (p. ej. "Made in Argentina"). Van en los hashtags si alguien lo buscaría. En el título solo si es algo que los compradores buscan de verdad (Made in USA, Made in Italy, Selvedge, Big E); un país cualquiera no aporta en el título.
            - LECTURA_DE_ETIQUETAS (si viene) es lo que se ha leído con detalle en todas las fotos: créetelo antes que a tu impresión. La composición manda sobre los materiales y la talla de la etiqueta sobre cualquier estimación.
            - Describe solo lo que se ve en las fotos o dice el vendedor. Si algo no se ve con seguridad, déjalo vacío (null o lista vacía).
            - Devuelve solo el JSON pedido.

            2. OBSERVA ("hallazgos", rellénalo antes que nada)
            Etiquetas, logos, parches, bordados, texto legible, tejido, lavado, corte y detalles; cada uno con dónde se ve.

            3. COMPLETA LO QUE FALTA
            - marca: solo si marca es null. Escríbela si se lee o es inconfundible (p. ej. la pestaña roja de Levi's en el bolsillo trasero). Si no, null. Si es "SIN MARCA", null.
            - modelo: solo si modelo es null y se lee en una etiqueta o parche (p. ej. "501" en el parche de cuero). Si no, null.
            - talla: solo si talla es null. Primero la que se lea en una etiqueta, tal cual. Si no hay etiqueta, estímala con las medidas en plano, la forma y las fotos:
              · Pantalones de hombre: W = cintura en plano × 2 / 2,54, redondeada (42 cm → W33). Nunca confundas los cm con la W.
              · Pantalones de mujer y todo lo demás: talla en letra XS, S, M, L, XL o XXL (el pecho en plano manda en prendas de arriba).
              · Si no hay base razonable, null.
            - tipo_prenda: uno de TIPOS. Si viene, devuelve ese.
            - palabras: de PALABRAS, solo las que se vean claramente. No devuelvas épocas.
            - sexo: "hombre" o "mujer", según corte, marca, modelo, talla y etiquetas. Si viene, devuelve ese.
            - categoria: copia EXACTA de una línea de CATEGORIAS, la más concreta que encaje con la prenda y el sexo.
            - materiales: de MATERIALES, hasta 3. Si hay composición (en LECTURA_DE_ETIQUETAS o en una foto), SOLO los de la composición (100% algodón → Algodón; 98% algodón 2% elastano → Algodón, Elastano), aunque la prenda sea vaquera. Sin composición, solo si el tejido es inconfundible (vaquero → Vaquero). Si no, lista vacía.
            - dudas: nombres de los campos (de categoria, talla, colores, materiales, marca) que hayas rellenado SIN seguridad, para que el vendedor los revise. Nunca pongas talla si sin_etiqueta_de_talla es true (el vendedor tampoco la sabe: decide tú con las medidas y las fotos) ni materiales si notas_del_vendedor dice el material. Por ejemplo un corte que no se distingue en las fotos (slim o recto) → categoria. Si todo está claro, lista vacía. Sé honesto: es mejor una duda que un error.
            - colores: de COLORES, el color del TEJIDO (no fondo, luz, sombras, etiquetas ni costuras). Normalmente UNO. Un segundo solo si la prenda tiene de verdad dos colores distintos (bloques, rayas, estampado); nunca dos tonos parecidos (Beige y Caqui, Azul y Azul claro).

            4. TÍTULOS (en inglés, uno por cuenta dentro de "titulos")
            Vinted muestra la foto, no el título: el título sirve para salir en las búsquedas. Mete las palabras que la gente escribe, en un orden natural.
            - Sigue el orden y los ejemplos de la cuenta (CUENTAS). Si falta un dato (marca, modelo, época), sáltalo; nunca lo inventes.
            - Época: solo la de DATOS_DEL_USUARIO y una sola vez (Y2K o 2000s, nunca las dos). "Vintage" si hay época o la prenda es claramente vintage.
            - Corte o forma visible: Straight, Slim, Skinny, Bootcut, Flare, Baggy, Wide Leg, Oversized, Boxy, Cropped…
            - Hasta 2 palabras de estilo SOLO si encajan de verdad: Workwear, Streetwear, Old Money, Retro, Preppy, Grunge, Washed, Faded, Denim…
            - Termina con el color en inglés y con el matiz real: Blue, Light Blue, Dark Blue, Black, Grey, White, Khaki, Beige, Brown, Navy, Burgundy, Cream, Olive…
            - Nunca talla, estado, precio, SKU, emojis, signos ni relleno (Amazing, Must Have, Rare…).
            - Cada palabra una sola vez: si ya pusiste el corte (Skinny) no lo repitas más adelante.
            - Title Case, máximo $TITLE_MAX caracteres. Las cuentas no pueden tener el mismo título.

            5. HASHTAGS ("hashtags": de 10 a 12, sin #, minúsculas, sin tildes ni espacios)
            Son palabras que los compradores escriben al buscar; tienen que atraer ventas, no quedar bonitos. Vinted España comparte catálogo con otros países: mezcla inglés y español.
            - En este orden: marca, marca+modelo y marca+vintage si es vintage (levis, levis505, levisvintage); prenda en inglés y en español (jeans, vaqueros, vaquerosvintage); corte en inglés y en español (straightjeans, vaquerosrectos); época (90s; con Y2K pon y2k y 2000s); estilo si encaja de verdad (streetwear, workwear, oldmoney, grunge, skater); vintage; lo útil de notas_del_vendedor (madeinusa, madeinargentina).
            - Cada hashtag es una búsqueda distinta que puede traer visitas. Una traducción (jeans / vaqueros) sí suma; una variante de lo mismo no (90s y 90sfashion, vintage y vintagestyle, denim y denimjeans): pon solo una.
            - Nada que no sea cierto ni genéricos que no venden (ootd, aesthetic, outfit, fashion, style, vinted, vintedfrance, instafashion).
            - Sin SKU ni códigos: la app los añade al final.

            DATOS_DEL_USUARIO
            $user
            ${Reader.facts(reading)?.let { "\nLECTURA_DE_ETIQUETAS\n$it\n" }.orEmpty()}
            CUENTAS (orden del título y ejemplos)
            ${accounts.joinToString("\n") { "${it.prefix}: ${VOICES[it.style.mod(VOICES.size)].trimIndent()}" }}

            TIPOS (id|nombre)
            ${Garments.types.joinToString("\n") { "${it.id}|${it.en[0]}" }}

            PALABRAS (id|en inglés|qué significa)
            ${Garments.keywords.filter { it.kind != KeywordKind.EPOCH }.joinToString("\n") { "${it.id}|${it.en[0]}|${it.help}" }}

            COLORES: ${Vinted.colors.joinToString(", ")}

            MATERIALES: ${Vinted.materials.joinToString(", ")}

            CATEGORIAS
            ${categoriesFor(listing.gender).joinToString("\n")}
        """.trimIndent()
    }

    /** Categories for the user's gender, or all when it is not known. */
    private fun categoriesFor(gender: Gender?): List<String> =
        if (gender == null) Vinted.categories else Vinted.categories.filter { it.startsWith(gender.label + " ") }

    /** Families of close shades: two colours of the same family are one colour seen in two lights. */
    private val SHADES = listOf(
        setOf("Beige", "Crema", "Caqui", "Naranja pastel"),
        setOf("Azul", "Azul claro", "Azul marino", "Turquesa"),
        setOf("Verde", "Verde oscuro", "Menta", "Caqui"),
        setOf("Rojo", "Burdeos", "Coral"),
        setOf("Rosa", "Fucsia", "Lila", "Morado", "Coral"),
        setOf("Amarillo", "Mostaza", "Dorado"),
        setOf("Gris", "Plateado"),
        setOf("Marrón", "Caqui", "Beige"),
    )

    /** A category as the AI wrote it, matched to the list even with small differences. */
    fun matchCategory(raw: String): String? {
        val text = raw.trim()
        Vinted.categories.firstOrNull { it == text }?.let { return it }
        fun norm(x: String) = x.lowercase().replace(Regex("\\s*[›>/|]\\s*"), "›").replace(Regex("\\s+"), " ").trim()
        val n = norm(text)
        Vinted.categories.firstOrNull { norm(it) == n }?.let { return it }
        // The last two levels are usually enough to tell which one it is.
        val tail = n.split('›').takeLast(2).joinToString("›")
        return Vinted.categories.filter { norm(it).endsWith(tail) }.singleOrNull()
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
        reading: Reading? = null,
    ): String = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt(listing, accounts, reading)) }
                    for (p in photos.take(PHOTOS)) {
                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", p) } }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema(accounts))
            put("temperature", 0.5)
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
            "categoria" to str(nullable = true),
            "colores" to arr(str(Vinted.colors), max = Vinted.MAX_COLORS),
            "materiales" to arr(str(Vinted.materials), max = Vinted.MAX_MATERIALS),
            "dudas" to arr(str(DOUBT_FIELDS)),
            "marca" to str(nullable = true),
            "modelo" to str(nullable = true),
            "talla" to str(nullable = true),
            "titulos" to obj(accounts.associate { it.prefix to str() }),
            "hashtags" to arr(str(), max = 14),
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
    fun parse(answer: String, listing: Listing, accounts: List<Account>, reading: Reading? = null): AiSuggestion {
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
        val category = o["categoria"]?.str()?.let(::matchCategory)
            ?.takeIf { finalGender == null || it.startsWith(finalGender.label + " ") }
        val colors = o.strings("colores").filter { it in Vinted.colors }.distinct().take(2).let { c ->
            if (c.size == 2 && SHADES.any { c[0] in it && c[1] in it }) c.take(1) else c
        }
        // What was read on a label wins over what the listing call estimated.
        val sizeLabel = (reading?.sizeLabel ?: (o["talla"] ?: o["talla_etiqueta"])?.str())?.trim()?.take(20)
            ?.takeIf { it.isNotEmpty() && listing.size.isBlank() }
        val brand = (o["marca"]?.str() ?: reading?.brand)?.let(::cleanName)?.takeIf { listing.brand.isBlank() && !listing.noBrand }
            ?.takeUnless { it.equals("sin marca", ignoreCase = true) }
        val model = (reading?.model ?: o["modelo"]?.str())?.let(::cleanName)
            ?.takeIf { listing.model.isBlank() && !listing.noBrand && !listing.noModel }
        val codes = accounts.map { Regex("^${Regex.escape(it.prefix.lowercase())}\\d+$") }
        val hashtags = ListingWriter.clean(o.strings("hashtags")).filterNot { t -> codes.any { it.matches(t) } }
        val titleObj = o["titulos"] as? JsonObject
        val seen = HashSet<String>()
        val titles = accounts.mapNotNull { a ->
            titleObj?.get(a.prefix)?.str()?.let(::cleanTitle)?.takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }?.let { a.id to it }
        }.toMap()
        val findings = (o["hallazgos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("detalle")?.str() }
        // A composition label decides the materials (a 100% cotton pair of jeans is «Algodón», not «Vaquero»).
        // Then what the seller wrote («es lana»): nothing to ask about it.
        val read = reading?.materials.orEmpty().ifEmpty { Reader.materials(listOf(listing.aiNotes)) }
        val materials = read.ifEmpty { o.strings("materiales").filter { it in Vinted.materials }.distinct().take(Vinted.MAX_MATERIALS) }
        val doubts = o.strings("dudas").filter { it in DOUBT_FIELDS }.distinct()
            .filterNot { it == "materiales" && read.isNotEmpty() }
            .filterNot { it == "talla" && listing.noLabel }      // the seller has no label either: the AI decides
        return AiSuggestion(type, keywords, gender, category, colors, sizeLabel, titles, findings, hashtags, brand, model,
            materials, doubts, reading)
    }

    private fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().trim('"', '.').take(40)

    /**
     * Each word once: «Skinny Jeans … Classic Skinny Fit» wastes characters and reads
     * like spam in Vinted's search. Later repeats are dropped (case and accents ignored).
     */
    fun distinctWords(title: String): String {
        val seen = HashSet<String>()
        return title.split(' ').filter { w ->
            val k = java.text.Normalizer.normalize(w, java.text.Normalizer.Form.NFD).lowercase().filter { it.isLetterOrDigit() }
            k.isEmpty() || seen.add(k)
        }.joinToString(" ")
    }

    private fun cleanTitle(raw: String): String {
        var t = distinctWords(raw.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s+"), " ").trim().trim('"', '.', '-', ' '))
        if (t.length > ListingWriter.TITLE_MAX) t = t.take(ListingWriter.TITLE_MAX).substringBeforeLast(' ')
        return t
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it.str() }
}
