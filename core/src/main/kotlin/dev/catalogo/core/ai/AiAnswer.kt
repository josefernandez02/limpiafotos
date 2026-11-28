package dev.catalogo.core.ai

import dev.catalogo.core.ai.Ai.Account
import dev.catalogo.core.ai.Ai.DOUBT_FIELDS
import dev.catalogo.core.ai.AiCategories.categoriesFor
import dev.catalogo.core.ai.AiCategories.matchCategory
import dev.catalogo.core.listing.Garments
import dev.catalogo.core.listing.KeywordKind
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.listing.ListingWriter
import dev.catalogo.core.listing.TagReader
import dev.catalogo.core.listing.TagReading
import dev.catalogo.core.listing.TitleGuard
import dev.catalogo.core.listing.TrouserFits
import dev.catalogo.core.vinted.Gender
import dev.catalogo.core.vinted.VintedTaxonomy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Reading and checking the model's answer. */
object AiAnswer {
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
    fun parse(answer: String, listing: Listing, accounts: List<Account>, reading: TagReading? = null): AiSuggestion {
        val o = runCatching { json.parseToJsonElement(answer.trim().removePrefix("```json").removeSuffix("```")).jsonObject }
            .getOrNull() ?: throw AiException("Respuesta de la IA ilegible")
        val type = o["tipo_prenda"]?.str()?.takeIf { Garments.type(it) != null }
        val group = Garments.type(listing.type.ifEmpty { type.orEmpty() })?.group
        val candidateKeywords = o.strings("palabras").distinct().filter { id ->
            Garments.keyword(id)?.let { it.kind != KeywordKind.EPOCH && it.appliesTo(group) } == true
        }
        val gender = when (o["sexo"]?.str()?.lowercase()) {
            "hombre" -> Gender.MEN
            "mujer" -> Gender.WOMEN
            else -> null
        }
        // A gender read on a label (or clear from the cut) wins over the listing call's guess.
        val readGender = when (reading?.gender) { "hombre" -> Gender.MEN; "mujer" -> Gender.WOMEN; else -> null }
        val finalGender = listing.gender ?: readGender ?: gender
        var category = o["categoria"]?.str()?.let(::matchCategory)?.let { c ->
            if (finalGender == null) c else TrouserFits.forGender(c, finalGender) ?: c.takeIf { TrouserFits.isJeans(it) }   // same category, right gender
        }
        val colors = o.strings("colores").filter { it in VintedTaxonomy.colors }.distinct().take(2).let { c ->
            if (c.size == 2 && SHADES.any { c[0] in it && c[1] in it }) c.take(1) else c
        }
        // A composition label decides the materials (a 100% cotton pair of jeans is «Algodón», not «Vaquero»).
        // Then what the seller wrote («es lana»): nothing to ask about it.
        val read = reading?.materials.orEmpty().ifEmpty { TagReader.materials(listOf(listing.aiNotes)) }
        val materials = read.ifEmpty { o.strings("materiales").filter { it in VintedTaxonomy.materials }.distinct().take(VintedTaxonomy.MAX_MATERIALS) }
        // With type, materials and colours settled, everything the AI writes about the garment is checked against them
        // (see [TitleGuard]): a wool and nylon jumper is never «Cashmere», a grey one never «Blue», a sweater never a «Puffer».
        val facts = TitleGuard.facts(listing, listing.type.ifEmpty { type.orEmpty() }.ifEmpty { null }, materials, colors)
        val keywords = candidateKeywords.filter { TitleGuard.keywordTrue(it, facts) }
        val codes = accounts.map { Regex("^${Regex.escape(it.prefix.lowercase())}\\d+$") }
        val hashtags = ListingWriter.clean(o.strings("hashtags")).filterNot { t -> codes.any { it.matches(t) } }
            .filter { TitleGuard.hashtagTrue(it, facts) }
        val titleObj = o["titulos"] as? JsonObject
        val seen = HashSet<String>()
        val titles = accounts.mapNotNull { a ->
            titleObj?.get(a.prefix)?.str()?.let(::cleanTitle)?.let { TitleGuard.fix(it, facts) }
                ?.takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }?.let { a.id to it }
        }.toMap()
        // What was read on a label wins over what the listing call estimated.
        val sizeLabel = (reading?.sizeLabel ?: (o["talla"] ?: o["talla_etiqueta"])?.str())?.trim()?.take(20)
            ?.takeIf { it.isNotEmpty() && listing.size.isBlank() }
        val brand = (o["marca"]?.str() ?: reading?.brand)?.let(::cleanName)?.takeIf { listing.brand.isBlank() && !listing.noBrand }
            ?.takeUnless { it.equals("sin marca", ignoreCase = true) }
        val model = (reading?.model ?: o["modelo"]?.str())?.let(::cleanName)
            ?.takeIf { listing.model.isBlank() && !listing.noBrand && !listing.noModel }
        val findings = (o["hallazgos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("detalle")?.str() }
        // Jeans: the cut decides the category. A model number is a fact (Levi's 511 → slim);
        // then the reading call's cut, then the listing call's.
        val modelFit = TrouserFits.ofModel(listing.brand.ifBlank { brand.orEmpty() }, listing.model.ifBlank { model.orEmpty() })
        val fit = modelFit ?: reading?.fit ?: TrouserFits.normalize(o["corte"]?.str())
        val byFit = category?.takeIf { TrouserFits.isJeans(it) }?.let { c ->
            val g = finalGender ?: if (c.startsWith(Gender.WOMEN.label)) Gender.WOMEN else Gender.MEN
            fit?.let { TrouserFits.jeansCategory(g, it) }
        }
        if (byFit != null) category = byFit
        if (finalGender != null && category?.startsWith(finalGender.label + " ") == false) category = null   // never the wrong gender
        // A category outside the garment's family (jersey → Camisetas) is a doubt, not a fact.
        val outOfFamily = category != null && type != null && category !in categoriesFor(finalGender, type)
        val doubts = (o.strings("dudas") + listOfNotNull("categoria".takeIf { outOfFamily })).filter { it in DOUBT_FIELDS }.distinct()
            .filterNot { it == "materiales" && read.isNotEmpty() }
            .filterNot { it == "talla" && listing.noLabel }      // the seller has no label either: the AI decides
            .filterNot { it == "categoria" && byFit != null && modelFit != null }   // the model number settles it
        return AiSuggestion(type, keywords, finalGender, category, colors, sizeLabel, titles, findings, hashtags, brand, model,
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
