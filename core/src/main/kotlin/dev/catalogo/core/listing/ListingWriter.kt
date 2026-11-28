package dev.catalogo.core.listing

import dev.catalogo.core.vinted.VintedTaxonomy

/**
 * Titles, descriptions and hashtags. Each listing style ([style] 0 or 1)
 * uses its own order and phrasing so two accounts never publish the same
 * text. AI titles and hashtags replace the deterministic ones when present.
 */
object ListingWriter {
    const val TITLE_MAX = 100
    const val DESCRIPTION_MAX = 2000
    const val HASHTAGS_MAX = 12

    /** Short and scannable (few people read it); the call to action is one short line. */
    private val TEMPLATES = listOf(
        """
        🏷️ Talla: {talla}
        📏 Medidas: {medidas}
        ✅ Estado: {estado}
        🚚 Envío: 24 horas

        👀 Descubre más prendas similares en mi perfil

        {hashtags}
        """,
        """
        🏷️ Talla {talla}
        📏 {medidas} (en plano)
        ✨ {estado}
        🚚 Lo envío en 24h

        🛍️ Más prendas así en mi perfil

        {hashtags}
        """,
    ).map { it.trimIndent() }


    /** Lines of older templates: dropped (null) or replaced by the current wording. */
    private val LEGACY_LINES: Map<String, String?> = mapOf(
        "👀 Más prendas de este estilo en mi perfil" to "👀 Descubre más prendas similares en mi perfil",
        "📏 Medidas en plano: {medidas}" to "📏 Medidas: {medidas}",
        "✨ Estado: {estado}" to "✅ Estado: {estado}",
        "🛍️ Más prendas de este estilo en mi perfil" to "👀 Descubre más prendas similares en mi perfil",
        "✅ {estado}" to "✨ {estado}",
        "👀 Más prendas así en mi perfil" to "🛍️ Más prendas así en mi perfil",
        "📝 Medidas tomadas en plano, aproximadas. Tómalas como referencia junto a la talla." to null,
        "📝 Medidas tomadas en plano, aproximadas. Tómalas como referencia junto a la talla" to null,
        "ℹ️ Nota: medidas aproximadas en plano, úsalas como guía junto con la talla." to null,
        "💬 ¿Tienes alguna duda? Escríbeme y te respondo lo antes posible." to null,
        "🤝 Si te surge cualquier pregunta, mándame un mensaje sin compromiso." to null,
        "👕 Talla: {talla}" to "🏷️ Talla: {talla}",
        "📦 Envío: En 24 horas" to "🚚 Envío: 24 horas",
        "🛍️ Echa un vistazo a mi perfil, tengo más prendas de este estilo que te pueden gustar." to
            "👀 Descubre más prendas similares en mi perfil",
        "👀 Tengo más prendas de este estilo en mi perfil, ¡échales un ojo!" to
            "🛍️ Más prendas así en mi perfil",
        "🛍️ ¡Echa un vistazo a mi perfil, tengo más prendas de este estilo que te pueden gustar!" to
            "👀 Descubre más prendas similares en mi perfil",
        "👀 ¡Tengo más prendas de este estilo en mi perfil, échales un ojo!" to
            "🛍️ Más prendas así en mi perfil",
    )

    fun defaultTemplate(style: Int): String = TEMPLATES[style.mod(TEMPLATES.size)]

    /**
     * Brings a template saved by an older version up to date: old lines are
     * replaced, a line of fixed hashtags becomes {hashtags} and the measures
     * line goes under the size.
     */
    fun upgrade(template: String, style: Int): String {
        if (template.isBlank()) return defaultTemplate(style)
        val lines = template.lines().mapNotNull { line ->
            val t = line.trim()
            when {
                t in LEGACY_LINES -> LEGACY_LINES[t]
                t.startsWith("#") && t.split(Regex("\\s+")).all { it.startsWith("#") } -> "{hashtags}"
                else -> line
            }
        }.toMutableList()
        if (lines.none { "{medidas}" in it }) {
            val at = lines.indexOfFirst { "{talla}" in it }
            if (at >= 0) lines.add(at + 1, defaultTemplate(style).lines().first { "{medidas}" in it })
        }
        return lines.joinToString("\n")
    }

    fun title(listing: Listing, style: Int): String {
        val s = style.mod(Garments.STYLES)
        val type = Garments.type(listing.type)
        val typeName = type?.en?.get(0).orEmpty()
        val brand = if (s == 1) listing.brand.replace("'", "").replace("’", "") else listing.brand
        val words = listing.keywords.mapNotNull(Garments::keyword).filter { it.appliesTo(type?.group) }
        fun of(kind: KeywordKind) = words.filter { it.kind == kind }.map { it.en[s] }
        val epoch = of(KeywordKind.EPOCH)
        val vintage = if (epoch.isNotEmpty() || "vintage" in listing.keywords) "Vintage" else ""
        val aesthetic = words.filter { it.kind == KeywordKind.AESTHETIC && it.id != "vintage" }.map { it.en[0] }
        val fit = words.filter { it.kind == KeywordKind.FIT }.map { it.en[if (s == 0) 1 else 0] }
        val material = of(KeywordKind.MATERIAL)
        val detail = of(KeywordKind.DETAIL)

        // (text, priority): lower priority is dropped first when the title is too long.
        val parts: List<Pair<String, Int>> = if (s == 0) {
            listOf(brand to 9, listing.model to 8, typeName to 9, vintage to 7) + epoch.map { it to 7 } +
                fit.map { it to 5 } + aesthetic.map { it to 4 } + material.map { it to 4 } + detail.map { it to 3 }
        } else {
            listOf(vintage to 7) + epoch.map { it to 7 } + listOf(brand to 9, listing.model to 8) + fit.map { it to 5 } +
                listOf(typeName to 9) + material.map { it to 4 } + aesthetic.reversed().map { it to 4 } + detail.reversed().map { it to 3 }
        }
        val seen = HashSet<String>()
        val clean = parts.map { (t, p) -> t.trim().replace(Regex("\\s+"), " ") to p }
            .filter { (t, _) -> t.isNotEmpty() && seen.add(t.lowercase()) }
            .toMutableList()
        while (clean.joinToString(" ") { it.first }.length > TITLE_MAX) {
            val drop = clean.withIndex().filter { it.value.second < 8 }.minByOrNull { it.value.second * 1000 - it.index }
                ?: break
            clean.removeAt(drop.index)
        }
        return clean.joinToString(" ") { it.first }.take(TITLE_MAX).trim()
    }

    /** [hashtags] (without #) come from the AI; null uses the ones made from the listing. */
    fun description(
        listing: Listing,
        style: Int,
        template: String,
        sku: String,
        hashtags: List<String>? = null,
    ): String {
        val s = style.mod(Garments.STYLES)
        val tags = (hashtags?.takeIf { it.isNotEmpty() } ?: hashtags(listing)).let { if (s == 1) reorder(it) else it }
        val values = mapOf(
            "{talla}" to size(listing, s),
            "{medidas}" to listing.flat.text(),
            "{estado}" to state(listing, s),
            "{hashtags}" to (tags.map { "#$it" } + "#$sku").joinToString(" "),
            "{sku}" to sku,
        )
        val body = upgrade(template, s).lines().mapNotNull { line ->
            var out = line
            for ((key, value) in values) {
                if (key in out) {
                    if (value.isEmpty()) return@mapNotNull null // a line about a missing value disappears
                    out = out.replace(key, value)
                }
            }
            out.trimEnd()
        }
        return body.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(DESCRIPTION_MAX)
    }

    fun text(
        listing: Listing,
        style: Int,
        template: String,
        sku: String,
        hashtags: List<String>? = null,
    ) = ListingText(title(listing, style), description(listing, style, template, sku, hashtags))

    /**
     * "W32 L34 (aprox. una M)": the letter is only a guide (brands cut differently),
     * so it always says «aprox.». A size without label says it too.
     */
    fun size(listing: Listing, style: Int): String {
        val size = listing.size.trim()
        if (size.isEmpty()) return ""
        val letter = VintedTaxonomy.letterSize(size, listing.gender)
        val s0 = style.mod(Garments.STYLES) == 0
        return when {
            letter == null -> if (listing.noLabel) "$size aprox." else size
            listing.noLabel -> if (s0) "$size aprox. (una $letter)" else "$size aprox. · $letter"
            else -> if (s0) "$size (aprox. una $letter)" else "$size · aprox. $letter"
        }
    }

    /** Condition phrase plus the notes: "Muy buen estado. Tiene manchas". */
    fun state(listing: Listing, style: Int): String {
        val base = condition(listing.condition, style.mod(Garments.STYLES))
        val note = cleanNote(listing.notes)
        return if (note.isEmpty()) base else "$base. $note"
    }

    fun condition(c: Condition, style: Int): String = if (style.mod(Garments.STYLES) == 0) {
        when (c) {
            Condition.NEW_TAGS -> "Nuevo con etiquetas"
            Condition.NEW -> "Nuevo, sin estrenar"
            Condition.VERY_GOOD -> "Muy buen estado"
            Condition.GOOD -> "Buen estado, con ligeros signos de uso"
            Condition.FAIR -> "Usado, con signos de uso visibles"
        }
    } else {
        when (c) {
            Condition.NEW_TAGS -> "A estrenar, conserva su etiqueta"
            Condition.NEW -> "A estrenar, sin etiqueta"
            Condition.VERY_GOOD -> "En muy buen estado"
            Condition.GOOD -> "En buen estado, con un uso ligero"
            Condition.FAIR -> "Usado, con señales de uso visibles"
        }
    }

    /** Hashtags made from the listing alone, for articles without AI: brand, garment, era, fit and style. */
    fun hashtags(listing: Listing): List<String> {
        val type = Garments.type(listing.type)
        val words = listing.keywords.mapNotNull(Garments::keyword).filter { it.appliesTo(type?.group) }
        val brand = if (listing.noBrand) "" else tag(listing.brand)
        val out = mutableListOf<String>()
        if (brand.isNotEmpty()) {
            out += brand
            if (listing.model.isNotBlank()) out += brand + tag(listing.model)
        }
        if (type != null) {
            out += tag(type.en[0])
            out += tag(type.label.substringBefore(" /").substringBefore(" ("))
        }
        val old = words.any { it.kind == KeywordKind.EPOCH || it.id == "vintage" }
        if (old) {
            out += "vintage"
            if (brand.isNotEmpty()) out += brand + "vintage"
        }
        for (w in words) {
            out += when (w.id) {
                "y2k" -> listOf("y2k", "2000s")
                "90s", "80s", "70s" -> listOf(w.id, w.id + "fashion")
                "vintage" -> emptyList()
                else -> listOf(tag(w.en[0]))
            }
        }
        return clean(out)
    }

    /** Lowercase, no accents, spaces or symbols; no duplicates; at most [HASHTAGS_MAX]. */
    fun clean(tags: List<String>): List<String> {
        val t = tags.map(::tag).filter { it.length in 2..30 }.distinct()
        // A variant of another tag adds no search of its own: «90sfashion» next to «90s».
        return t.filterNot { x -> t.any { y -> y != x && x.startsWith(y) && x.removePrefix(y) in FILLER_SUFFIXES } }
            .filterNot { it in GENERIC }
            .take(HASHTAGS_MAX)
    }

    private val FILLER_SUFFIXES = setOf("fashion", "style", "styles", "look", "looks", "vibes", "vibe", "aesthetic",
        "outfit", "outfits", "era", "clothing", "clothes", "moda", "estilo", "ropa")
    private val GENERIC = setOf("ootd", "aesthetic", "outfit", "fashion", "style", "moda", "instafashion", "vinted",
        "vintedfrance", "vintedespana", "vintedes", "ropa", "clothes")

    private fun tag(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]"), "")

    /** Second account: same tags in another order, so both descriptions are not identical. */
    private fun reorder(tags: List<String>): List<String> {
        if (tags.size < 4) return tags
        val half = tags.size / 2
        return tags.take(2) + tags.drop(half) + tags.subList(2, half)
    }

    /** One line, no final full stop, first letter in capitals. */
    private fun cleanNote(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').replaceFirstChar(Char::uppercase)
}
