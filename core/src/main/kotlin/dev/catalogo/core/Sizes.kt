package dev.catalogo.core

/**
 * Sizes as the vinted.es upload form offers them for each category, and how
 * the size written on the label turns into one of them.
 *
 * What the form shows (checked on 2026-11-12 reading the form itself):
 * - Women's clothing: letters XXXS…9XL and «Talla única» (EU/UK/FR/IT/US are
 *   other tabs of the same list). No W sizes: a W30 is a letter there.
 * - Men's clothing: letters XS…8XL and «Talla única»; trousers add W23…W54
 *   («W30 | ES 40»), shirts add the collar 35…52, blazers their numbers.
 * - Shoes (men and women): 34…46 in halves, «42.5», and «Talla única».
 *
 * [resolve] only answers when it is sure; otherwise the size goes to «Revisar»
 * with [options] to choose from.
 */
object Sizes {
    const val ONE_SIZE = "Talla única"

    private val WOMEN_LETTERS = listOf(
        "XXXS", "XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "4XL", "5XL", "6XL", "7XL", "8XL", "9XL", ONE_SIZE,
    )
    private val MEN_LETTERS = listOf("XS", "S", "M", "L", "XL", "XXL", "XXXL", "4XL", "5XL", "6XL", "7XL", "8XL", ONE_SIZE)
    private val WAIST = listOf(23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54)
        .map { "W$it" }
    private val COLLAR = (35..52).map { "$it" }
    private val SHOES = (34..46).flatMap { if (it < 46) listOf("$it", "$it.5") else listOf("$it") } + ONE_SIZE

    /** Women's EU (Spanish) number → letter, as in Vinted's own table («M / 38 / 10»). */
    private val WOMEN_EU = mapOf(
        30 to "XXXS", 32 to "XXS", 34 to "XS", 36 to "S", 38 to "M", 40 to "L", 42 to "XL", 44 to "XXL",
        46 to "XXXL", 48 to "4XL", 50 to "5XL", 52 to "6XL", 54 to "7XL", 56 to "8XL", 58 to "9XL",
    )
    private val LETTER_ALIASES = mapOf("2XL" to "XXL", "3XL" to "XXXL", "2XS" to "XXS", "3XS" to "XXXS", "XXXXL" to "4XL")
    private val ONE_SIZE_WORDS = setOf("TALLAUNICA", "UNICA", "TU", "ONESIZE", "OS", "UNITALLA", "U")

    /** Sizes the form offers for [category]; empty when it asks for none (bags, beauty…). */
    fun options(category: String): List<String> {
        val out = LinkedHashSet<String>()
        for (g in SizeTable.groups[category].orEmpty()) {
            when (g) {
                4, 80 -> out += WOMEN_LETTERS
                81, 82, 83, 84, 85 -> Unit                  // other tabs of the same women's list
                14 -> out += MEN_LETTERS
                77 -> out += WAIST
                74 -> out += COLLAR
                7, 38 -> out += SHOES
                else -> SizeTable.raw[g].orEmpty().forEach { v ->
                    when (v) {
                        "Other" -> Unit
                        "One size" -> out += ONE_SIZE
                        else -> out += v
                    }
                }
            }
        }
        return out.toList()
    }

    fun asksForSize(category: String): Boolean = options(category).isNotEmpty()

    private fun gender(category: String): Gender? = when {
        category.startsWith("Mujer ") -> Gender.WOMEN
        category.startsWith("Hombre ") -> Gender.MEN
        else -> null
    }

    private fun key(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase().replace(",", ".").replace(Regex("[\\s_]"), "")

    /**
     * The form's size for what the label says ([Listing.size]), or null when it
     * cannot be told for sure (then the user picks it in «Revisar»).
     */
    fun resolve(category: String, listing: Listing): String? = resolve(category, listing.size)

    fun resolve(category: String, raw: String): String? {
        val opts = options(category)
        val size = raw.trim()
        if (opts.isEmpty() || size.isEmpty()) return null
        val byKey = opts.associateBy(::key)
        val k = key(size)

        byKey[k]?.let { return it }
        if (k in ONE_SIZE_WORDS) return ONE_SIZE.takeIf { it in opts }

        // Waist sizes («W30 L32», «W 30», «30/32» stays out: it could be anything).
        Regex("(?i)\\bW\\s?(\\d{2})\\b").find(size)?.groupValues?.get(1)?.let { w ->
            if ("W$w" in opts) return "W$w"
            val letter = Vinted.letterSize("W$w", gender(category)) ?: return null
            return letter.takeIf { it in opts }
        }

        // Shoes: «42», «42,5», «42.5», «EU 42», «42 1/2». Thirds (adidas) are not on the list.
        if (opts.contains("42.5")) {
            val m = Regex("^(?:EU|ES|EUR)?(\\d{2})(?:\\.(5)|1/2)?(?:EU|ES|EUR)?$").find(k) ?: return null
            val v = m.groupValues[1] + (if (m.groupValues[2].isNotEmpty() || k.contains("1/2")) ".5" else "")
            return v.takeIf { it in opts }
        }

        // Letters inside a longer text: «M / 38 / 10», «Talla M», «M (38)», «XXL».
        val letters = opts.filter { it.first().isLetter() && it != ONE_SIZE }.toSet()
        val tokens = Regex("[A-Za-z0-9]+").findAll(size).map { it.value.uppercase() }
            .map { LETTER_ALIASES[it] ?: it }.toList()
        val found = tokens.filter { it in letters }.distinct()
        if (found.size == 1) return found.single()
        if (found.size > 1) return null                     // «S/M»: the user decides

        // Women's clothes in Spanish/EU numbers: 38 → M.
        if (gender(category) == Gender.WOMEN && opts.contains("M")) {
            Regex("^(?:EU|ES)?(\\d{2})$").find(k)?.groupValues?.get(1)?.toInt()?.let { n ->
                return WOMEN_EU[n]?.takeIf { it in opts }
            }
        }
        return null
    }
}
