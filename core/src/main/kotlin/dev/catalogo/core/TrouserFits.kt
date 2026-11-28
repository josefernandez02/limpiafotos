package dev.catalogo.core

/**
 * Cut (fit) of trousers and jeans, and the Vinted category it belongs to. The AI
 * guesses the cut; a model number read on a label or patch (Levi's 511, 505…) is
 * a fact and wins. The category then follows the cut and the gender in code, so
 * a straight pair never ends up in «Vaqueros ajustados».
 */
object TrouserFits {
    const val SKINNY = "skinny"
    const val SLIM = "slim"
    const val TAPERED = "tapered"
    const val STRAIGHT = "straight"
    const val RELAXED = "relaxed"
    const val BAGGY = "baggy"
    const val BOOTCUT = "bootcut"
    const val FLARE = "flare"
    const val WIDE = "wide"
    const val MOM = "mom"
    const val BOYFRIEND = "boyfriend"

    val ALL = listOf(SKINNY, SLIM, TAPERED, STRAIGHT, RELAXED, BAGGY, BOOTCUT, FLARE, WIDE, MOM, BOYFRIEND)

    /** Free text («Slim Fit», «loose straight», «pitillo», «campana») to one cut, or null. */
    fun normalize(raw: String?): String? {
        val t = raw?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        return when {
            Regex("super ?skinny|skinny|pitillo|jegging").containsMatchIn(t) -> SKINNY
            Regex("boyfriend").containsMatchIn(t) -> BOYFRIEND
            Regex("\\bmom\\b").containsMatchIn(t) -> MOM
            Regex("boot ?cut|bootleg").containsMatchIn(t) -> BOOTCUT
            Regex("flare|campana|acampanad").containsMatchIn(t) -> FLARE
            Regex("wide|ancho|palazzo").containsMatchIn(t) -> WIDE
            Regex("baggy|loose|holgad|oversize").containsMatchIn(t) -> BAGGY
            Regex("relaxed|comfort").containsMatchIn(t) -> RELAXED
            Regex("slim ?taper|taper|tapered").containsMatchIn(t) && !Regex("regular ?taper").containsMatchIn(t) -> TAPERED
            Regex("\\bslim\\b|ajustad|estrech").containsMatchIn(t) -> SLIM
            Regex("straight|recto|regular|classic|cl[aá]sico").containsMatchIn(t) -> STRAIGHT
            else -> null
        }
    }

    /** Levi's model numbers (patch or label) and their cut: a fact, not a guess. */
    private val LEVIS = mapOf(
        "501" to STRAIGHT, "502" to STRAIGHT, "504" to STRAIGHT, "505" to STRAIGHT, "506" to STRAIGHT,
        "510" to SKINNY, "511" to SLIM, "512" to TAPERED, "513" to SLIM, "514" to STRAIGHT, "516" to STRAIGHT,
        "517" to BOOTCUT, "515" to BOOTCUT, "527" to BOOTCUT, "541" to TAPERED, "550" to RELAXED,
        "559" to RELAXED, "560" to RELAXED, "565" to BAGGY, "568" to BAGGY, "569" to BAGGY, "578" to BAGGY,
        "580" to BAGGY, "751" to STRAIGHT,
        "310" to SKINNY, "311" to SKINNY, "312" to SLIM, "314" to STRAIGHT, "315" to BOOTCUT,
        "710" to SKINNY, "711" to SKINNY, "712" to SLIM, "714" to STRAIGHT, "715" to BOOTCUT,
        "720" to SKINNY, "721" to SKINNY, "724" to STRAIGHT, "725" to BOOTCUT, "726" to FLARE,
    )

    /** The cut that a brand + model makes certain (only Levi's numbers for now), or null. */
    fun ofModel(brand: String, model: String): String? {
        if (!brand.lowercase().replace("'", "").startsWith("levi")) return null
        val n = Regex("\\b(\\d{3})\\b").find(model)?.groupValues?.get(1) ?: return null
        return LEVIS[n]
    }

    /** Is [category] a jeans category («… › Vaqueros › …»)? */
    fun isJeans(category: String): Boolean = Regex("› Ropa › Vaqueros › ").containsMatchIn(category)

    /** The jeans category for a gender and a cut (null = no clear match: leave the AI's). */
    fun jeansCategory(gender: Gender, cut: String): String? {
        val leaf = when (gender) {
            Gender.MEN -> when (cut) {
                SKINNY -> "Vaqueros pitillo"
                SLIM, TAPERED -> "Vaqueros ajustados"
                STRAIGHT, RELAXED, BAGGY, BOOTCUT, WIDE, BOYFRIEND, MOM, FLARE -> "Vaqueros rectos"
                else -> null
            }
            Gender.WOMEN -> when (cut) {
                SKINNY, SLIM, TAPERED -> "Vaqueros pitillo"
                STRAIGHT -> "Vaqueros rectos"
                BOOTCUT, FLARE -> "Vaqueros de campana"
                BOYFRIEND, BAGGY, RELAXED -> "Vaqueros boyfriend"
                MOM -> "Vaqueros de cintura alta"
                WIDE -> "Otros vaqueros"
                else -> null
            }
        } ?: return null
        return "${gender.label} › Ropa › Vaqueros › $leaf".takeIf { it in VintedTaxonomy.categories }
    }

    /** Same category under the other gender, when Vinted has it («Hombre › … › X» → «Mujer › … › X»). */
    fun forGender(category: String, gender: Gender): String? {
        if (category.startsWith(gender.label + " ")) return category
        val rest = category.substringAfter(" › ", "")
        return "${gender.label} › $rest".takeIf { rest.isNotEmpty() && it in VintedTaxonomy.categories }
    }
}
