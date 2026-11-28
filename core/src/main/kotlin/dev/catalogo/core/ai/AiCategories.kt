package dev.catalogo.core.ai

import dev.catalogo.core.vinted.Gender
import dev.catalogo.core.vinted.VintedTaxonomy

/** Vinted categories offered to the model and matching of the one it answers. */
object AiCategories {
    /**
     * Categories for the user's gender and, when the garment type is known, only those of
     * its family (jeans → Vaqueros…): the AI picks among a handful instead of 360 lines,
     * which is where most wrong categories came from. Falls back to all when unsure.
     */
    fun categoriesFor(gender: Gender?, type: String = ""): List<String> {
        val byGender = if (gender == null) VintedTaxonomy.categories else VintedTaxonomy.categories.filter { it.startsWith(gender.label + " ") }
        val family = FAMILIES[type] ?: return byGender
        val picked = byGender.filter { c -> family.any { c.contains(it) } }
        return if (picked.size >= 2) picked else byGender
    }

    /** Type id → words that a category of that family has in its path. */
    private val FAMILIES: Map<String, List<String>> = mapOf(
        "jeans" to listOf("Ropa › Vaqueros"),
        "trousers" to listOf("› Pantalones"),
        "cargo" to listOf("› Pantalones"),
        "joggers" to listOf("› Pantalones", "deportiv"),
        "denimshorts" to listOf("› Shorts"),
        "shorts" to listOf("› Shorts", "deportiv"),
        "tshirt" to listOf("› Camisetas"),
        "longsleeve" to listOf("› Camisetas"),
        "football" to listOf("› Camisetas", "deportiv"),
        "shirt" to listOf("› Camisetas y camisas", "› Camisetas y tops"),
        "polo" to listOf("Polos", "› Camisetas"),
        "tanktop" to listOf("› Camisetas"),
        "top" to listOf("› Camisetas y tops", "› Camisetas y camisas"),
        "hoodie" to listOf("› Jerséis y sudaderas"),
        "sweatshirt" to listOf("› Jerséis y sudaderas"),
        "zipsweat" to listOf("› Jerséis y sudaderas"),
        "sweater" to listOf("› Jerséis y sudaderas"),
        "cardigan" to listOf("› Jerséis y sudaderas"),
        "overshirt" to listOf("› Abrigos", "› Camisetas y camisas", "› Camisetas y tops"),
        "jacket" to listOf("› Abrigos"),
        "denimjacket" to listOf("› Abrigos"),
        "bomber" to listOf("› Abrigos"),
        "leatherjacket" to listOf("› Abrigos"),
        "trackjacket" to listOf("› Abrigos", "deportiv", "› Jerséis y sudaderas"),
        "windbreaker" to listOf("› Abrigos", "deportiv"),
        "fleecejacket" to listOf("› Abrigos", "› Jerséis y sudaderas"),
        "coat" to listOf("› Abrigos"),
        "puffer" to listOf("› Abrigos"),
        "vest" to listOf("Chalecos"),
        "blazer" to listOf("› Trajes y blazers"),
        "dress" to listOf("› Vestidos"),
        "skirt" to listOf("› Faldas"),
        "jumpsuit" to listOf("› Monos", "Petos"),
        "tracksuit" to listOf("deportiv", "Chándal", "chándal"),
        "sneakers" to listOf("› Calzado"),
        "boots" to listOf("› Calzado"),
        "shoes" to listOf("› Calzado"),
        "bag" to listOf("› Bolsos", "› Accesorios"),
        "backpack" to listOf("› Bolsos", "› Accesorios"),
    )

    /** A category as the AI wrote it, matched to the list even with small differences. */
    fun matchCategory(raw: String): String? {
        val text = raw.trim()
        VintedTaxonomy.categories.firstOrNull { it == text }?.let { return it }
        fun norm(x: String) = x.lowercase().replace(Regex("\\s*[›>/|]\\s*"), "›").replace(Regex("\\s+"), " ").trim()
        val n = norm(text)
        VintedTaxonomy.categories.firstOrNull { norm(it) == n }?.let { return it }
        // The last two levels are usually enough to tell which one it is.
        val tail = n.split('›').takeLast(2).joinToString("›")
        return VintedTaxonomy.categories.filter { norm(it).endsWith(tail) }.singleOrNull()
    }
}
