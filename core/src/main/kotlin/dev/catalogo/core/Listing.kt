package dev.catalogo.core

import kotlinx.serialization.Serializable

/** Measurement set and wording family of a garment. */
enum class Group { BOTTOM, TOP, OUTER, DRESS, SHOES, ACCESSORY }

/** [en] holds one English name per listing style, so accounts never share wording. */
data class GarmentType(val id: String, val label: String, val group: Group, val en: List<String>)

enum class KeywordKind(val label: String) { STYLE("Estilo"), FIT("Corte"), DETAIL("Detalles") }

data class Keyword(val id: String, val label: String, val kind: KeywordKind, val en: List<String>)

data class Measure(val id: String, val label: String)

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
    val measures: Map<String, String> = emptyMap(),
)

@Serializable
data class ListingText(val title: String, val description: String)

object Garments {
    const val STYLES = 2

    val types: List<GarmentType> = listOf(
        GarmentType("jeans", "Vaqueros", Group.BOTTOM, listOf("Jeans", "Denim Pants")),
        GarmentType("trousers", "Pantalón", Group.BOTTOM, listOf("Trousers", "Pants")),
        GarmentType("cargo", "Cargo", Group.BOTTOM, listOf("Cargo Pants", "Cargos")),
        GarmentType("joggers", "Chándal", Group.BOTTOM, listOf("Track Pants", "Joggers")),
        GarmentType("shorts", "Shorts", Group.BOTTOM, listOf("Shorts", "Short Pants")),
        GarmentType("skirt", "Falda", Group.BOTTOM, listOf("Skirt", "Skirt")),
        GarmentType("tshirt", "Camiseta", Group.TOP, listOf("T-Shirt", "Tee")),
        GarmentType("shirt", "Camisa", Group.TOP, listOf("Shirt", "Button Up Shirt")),
        GarmentType("polo", "Polo", Group.TOP, listOf("Polo Shirt", "Polo Tee")),
        GarmentType("sweatshirt", "Sudadera", Group.TOP, listOf("Sweatshirt", "Crewneck")),
        GarmentType("hoodie", "Hoodie", Group.TOP, listOf("Hoodie", "Hooded Sweatshirt")),
        GarmentType("sweater", "Jersey", Group.TOP, listOf("Sweater", "Knit Jumper")),
        GarmentType("cardigan", "Cárdigan", Group.TOP, listOf("Cardigan", "Knit Cardigan")),
        GarmentType("top", "Top", Group.TOP, listOf("Top", "Top")),
        GarmentType("jacket", "Chaqueta", Group.OUTER, listOf("Jacket", "Outerwear Jacket")),
        GarmentType("denimjacket", "Cazadora vaquera", Group.OUTER, listOf("Denim Jacket", "Jean Jacket")),
        GarmentType("bomber", "Bomber", Group.OUTER, listOf("Bomber Jacket", "Bomber")),
        GarmentType("coat", "Abrigo", Group.OUTER, listOf("Coat", "Overcoat")),
        GarmentType("puffer", "Plumífero", Group.OUTER, listOf("Puffer Jacket", "Down Jacket")),
        GarmentType("windbreaker", "Cortavientos", Group.OUTER, listOf("Windbreaker", "Track Jacket")),
        GarmentType("vest", "Chaleco", Group.OUTER, listOf("Vest", "Gilet")),
        GarmentType("blazer", "Blazer", Group.OUTER, listOf("Blazer", "Suit Jacket")),
        GarmentType("dress", "Vestido", Group.DRESS, listOf("Dress", "Dress")),
        GarmentType("jumpsuit", "Mono", Group.DRESS, listOf("Jumpsuit", "Overall")),
        GarmentType("sneakers", "Zapatillas", Group.SHOES, listOf("Sneakers", "Trainers")),
        GarmentType("boots", "Botas", Group.SHOES, listOf("Boots", "Boots")),
        GarmentType("shoes", "Zapatos", Group.SHOES, listOf("Shoes", "Shoes")),
        GarmentType("bag", "Bolso", Group.ACCESSORY, listOf("Bag", "Handbag")),
        GarmentType("backpack", "Mochila", Group.ACCESSORY, listOf("Backpack", "Rucksack")),
        GarmentType("cap", "Gorra", Group.ACCESSORY, listOf("Cap", "Baseball Cap")),
        GarmentType("beanie", "Gorro", Group.ACCESSORY, listOf("Beanie", "Knit Hat")),
        GarmentType("belt", "Cinturón", Group.ACCESSORY, listOf("Belt", "Waist Belt")),
        GarmentType("scarf", "Bufanda", Group.ACCESSORY, listOf("Scarf", "Scarf")),
        GarmentType("sunglasses", "Gafas de sol", Group.ACCESSORY, listOf("Sunglasses", "Shades")),
    )

    val keywords: List<Keyword> = listOf(
        Keyword("vintage", "vintage", KeywordKind.STYLE, listOf("Vintage", "Retro")),
        Keyword("retro", "retro", KeywordKind.STYLE, listOf("Retro", "Old School")),
        Keyword("y2k", "y2k", KeywordKind.STYLE, listOf("Y2K", "2000s")),
        Keyword("90s", "90s", KeywordKind.STYLE, listOf("90s", "1990s")),
        Keyword("2000s", "2000s", KeywordKind.STYLE, listOf("2000s", "00s")),
        Keyword("80s", "80s", KeywordKind.STYLE, listOf("80s", "1980s")),
        Keyword("oldmoney", "old money", KeywordKind.STYLE, listOf("Old Money", "Quiet Luxury")),
        Keyword("preppy", "preppy", KeywordKind.STYLE, listOf("Preppy", "Ivy Style")),
        Keyword("streetwear", "streetwear", KeywordKind.STYLE, listOf("Streetwear", "Street Style")),
        Keyword("skater", "skater", KeywordKind.STYLE, listOf("Skater", "Skate")),
        Keyword("grunge", "grunge", KeywordKind.STYLE, listOf("Grunge", "Grunge Style")),
        Keyword("workwear", "workwear", KeywordKind.STYLE, listOf("Workwear", "Utility")),
        Keyword("gorpcore", "gorpcore", KeywordKind.STYLE, listOf("Gorpcore", "Outdoor")),
        Keyword("sporty", "sporty", KeywordKind.STYLE, listOf("Sporty", "Athletic")),
        Keyword("minimal", "minimal", KeywordKind.STYLE, listOf("Minimal", "Clean")),
        Keyword("classic", "classic", KeywordKind.STYLE, listOf("Classic", "Timeless")),
        Keyword("rare", "rare", KeywordKind.STYLE, listOf("Rare", "Rare Find")),
        Keyword("baggy", "baggy", KeywordKind.FIT, listOf("Baggy", "Loose Fit")),
        Keyword("oversized", "oversized", KeywordKind.FIT, listOf("Oversized", "Boxy")),
        Keyword("straight", "straight", KeywordKind.FIT, listOf("Straight Leg", "Straight Fit")),
        Keyword("slim", "slim", KeywordKind.FIT, listOf("Slim", "Slim Fit")),
        Keyword("wideleg", "wide leg", KeywordKind.FIT, listOf("Wide Leg", "Wide Fit")),
        Keyword("bootcut", "bootcut", KeywordKind.FIT, listOf("Bootcut", "Boot Cut")),
        Keyword("flare", "flare", KeywordKind.FIT, listOf("Flare", "Flared")),
        Keyword("highwaist", "high waist", KeywordKind.FIT, listOf("High Waisted", "High Rise")),
        Keyword("lowrise", "low rise", KeywordKind.FIT, listOf("Low Rise", "Low Waist")),
        Keyword("cropped", "cropped", KeywordKind.FIT, listOf("Cropped", "Crop")),
        Keyword("faded", "faded", KeywordKind.DETAIL, listOf("Faded", "Sun Faded")),
        Keyword("washed", "washed", KeywordKind.DETAIL, listOf("Washed", "Stone Wash")),
        Keyword("distressed", "distressed", KeywordKind.DETAIL, listOf("Distressed", "Ripped")),
        Keyword("carpenter", "carpenter", KeywordKind.DETAIL, listOf("Carpenter", "Painter")),
        Keyword("logo", "logo", KeywordKind.DETAIL, listOf("Logo", "Branded")),
        Keyword("embroidered", "bordado", KeywordKind.DETAIL, listOf("Embroidered", "Embroidery")),
        Keyword("graphic", "estampado", KeywordKind.DETAIL, listOf("Graphic", "Print")),
        Keyword("knit", "punto", KeywordKind.DETAIL, listOf("Knit", "Knitted")),
        Keyword("cableknit", "ochos", KeywordKind.DETAIL, listOf("Cable Knit", "Cable Knitted")),
        Keyword("corduroy", "pana", KeywordKind.DETAIL, listOf("Corduroy", "Cord")),
        Keyword("fleece", "forro polar", KeywordKind.DETAIL, listOf("Fleece", "Polar Fleece")),
        Keyword("leather", "piel", KeywordKind.DETAIL, listOf("Leather", "Genuine Leather")),
        Keyword("quarterzip", "media cremallera", KeywordKind.DETAIL, listOf("Quarter Zip", "1/4 Zip")),
        Keyword("zipup", "cremallera", KeywordKind.DETAIL, listOf("Zip Up", "Full Zip")),
    )

    private val typeById = types.associateBy { it.id }
    private val keywordById = keywords.associateBy { it.id }

    fun type(id: String): GarmentType? = typeById[id]

    fun keyword(id: String): Keyword? = keywordById[id]

    fun measures(group: Group?): List<Measure> = when (group) {
        Group.BOTTOM -> listOf(
            Measure("length", "largo total"), Measure("waist", "cintura"), Measure("rise", "tiro"),
            Measure("thigh", "muslo"), Measure("ankle", "tobillo"),
        )
        Group.TOP, Group.OUTER -> listOf(
            Measure("length", "largo"), Measure("chest", "pecho"), Measure("shoulders", "hombros"), Measure("sleeve", "manga"),
        )
        Group.DRESS -> listOf(Measure("length", "largo"), Measure("chest", "pecho"), Measure("waist", "cintura"))
        Group.SHOES -> listOf(Measure("insole", "largo de plantilla"))
        Group.ACCESSORY -> listOf(Measure("height", "alto"), Measure("width", "ancho"), Measure("depth", "fondo"))
        null -> emptyList()
    }
}

/**
 * Deterministic titles and descriptions. Each listing style ([style] 0 or 1)
 * uses its own vocabulary, order and phrasing so two accounts never publish
 * the same text for the same article.
 */
object ListingWriter {
    const val TITLE_MAX = 100
    const val DESCRIPTION_MAX = 2000

    private val TEMPLATES = listOf(
        """
        👕 Talla: {talla}
        📏 {medidas}
        ✨ Estado: {estado}
        📦 Envío en 24h

        📝 Medidas tomadas en plano, aproximadas. Tómalas como referencia junto a la talla.
        🛍️ Descubre más prendas similares en mi perfil

        #vintage #branded #y2k #vintagefashion #ootd #vintedfrance #aesthetic #2000s #90s #stylefinds #retrofashion #{sku}
        """,
        """
        🏷️ Talla {talla}
        📐 {medidas}
        ✅ {estado}
        🚚 Envío: 24h

        ℹ️ Nota: medidas aproximadas en plano, úsalas como guía junto con la talla.
        👀 Echa un vistazo a mi perfil, tengo más prendas de este estilo

        #vintagestyle #thrifted #streetwear #y2kfashion #retro #vinted #90sfashion #outfit #secondhand #fashionfinds #vintageclothing #{sku}
        """,
    ).map { it.trimIndent() }

    fun defaultTemplate(style: Int): String = TEMPLATES[style.mod(TEMPLATES.size)]

    fun title(listing: Listing, style: Int): String {
        val s = style.mod(Garments.STYLES)
        val type = Garments.type(listing.type)?.en?.get(s).orEmpty()
        val brand = if (s == 1) listing.brand.replace("'", "").replace("’", "") else listing.brand
        val words = listing.keywords.mapNotNull(Garments::keyword)
        fun of(kind: KeywordKind) = words.filter { it.kind == kind }.map { it.en[s] }
        val style0 = of(KeywordKind.STYLE)
        val fit = of(KeywordKind.FIT)
        val detail = of(KeywordKind.DETAIL)

        // (text, priority): lower priority is dropped first when the title is too long.
        val parts: List<Pair<String, Int>> = if (s == 0) {
            listOf(brand to 9, listing.model to 8) + style0.map { it to 5 } + fit.map { it to 4 } +
                (type to 9) + detail.map { it to 3 }
        } else {
            style0.reversed().map { it to 5 } + listOf(brand to 9, listing.model to 8) + fit.map { it to 4 } +
                (type to 9) + detail.reversed().map { it to 3 }
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

    fun description(listing: Listing, style: Int, template: String, sku: String): String {
        val s = style.mod(Garments.STYLES)
        val group = Garments.type(listing.type)?.group
        val measures = Garments.measures(group).mapNotNull { m ->
            listing.measures[m.id]?.let(::cleanMeasure)?.takeIf { it.isNotEmpty() }?.let { m to it }
        }
        val medidas = when {
            measures.isEmpty() -> if (s == 0) "Medidas en las fotos" else "Medidas: ver fotos"
            s == 0 -> "Medidas: " + measures.joinToString(" · ") { (m, v) -> "${m.label} $v cm" }
            else -> measures.joinToString(" · ") { (m, v) -> "${m.label.replaceFirstChar(Char::uppercase)} $v" } + " (cm)"
        }
        val values = mapOf(
            "{talla}" to listing.size.trim(),
            "{medidas}" to medidas,
            "{estado}" to condition(listing.condition, s),
            "{sku}" to sku,
        )
        val body = (template.ifBlank { defaultTemplate(s) }).lines().mapNotNull { line ->
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

    fun text(listing: Listing, style: Int, template: String, sku: String) =
        ListingText(title(listing, style), description(listing, style, template, sku))

    fun condition(c: Condition, style: Int): String = if (style.mod(Garments.STYLES) == 0) {
        when (c) {
            Condition.NEW_TAGS -> "Nuevo con etiquetas."
            Condition.NEW -> "Nuevo, sin estrenar."
            Condition.VERY_GOOD -> "Muy buen estado."
            Condition.GOOD -> "Buen estado."
            Condition.FAIR -> "Con algún signo de uso, ver fotos."
        }
    } else {
        when (c) {
            Condition.NEW_TAGS -> "A estrenar, con su etiqueta"
            Condition.NEW -> "Sin usar, sin etiqueta"
            Condition.VERY_GOOD -> "En muy buen estado, ver fotos"
            Condition.GOOD -> "En buen estado, ver fotos"
            Condition.FAIR -> "Tiene señales de uso, ver fotos"
        }
    }

    private fun cleanMeasure(raw: String): String =
        raw.trim().removeSuffix("cm").removeSuffix("CM").trim().replace('.', ',')
}
