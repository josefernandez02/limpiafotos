package dev.catalogo.core

import java.text.Normalizer

/**
 * What the AI writes for a listing has to be TRUE of that garment: a title (or hashtag, or search word) that says
 * «Cashmere» on a wool and nylon jumper, «Blue» on a grey one or «Puffer» on a sweater is a false listing, and the buyer
 * can claim. The prompt asks for it; this checks it, because a model can still pick a nicer-sounding synonym.
 *
 * Rules, all against what is already settled for the article (type, materials, colours; never the AI's own guess):
 *  - a fabric word needs that material in the list (or the user chose it: garment type, keywords, brand);
 *  - a colour word needs a colour of the same family in the list;
 *  - a garment word must be the garment (or a real synonym of it); otherwise the title is rejected whole.
 * Wrong words are removed, so the rest of a good title is kept; what cannot be repaired returns null.
 */
object TitleGuard {
    /** The settled facts a title is checked against. */
    class Facts(
        val typeId: String?,
        val materials: List<String>,
        val colors: List<String>,
        /** Words of the brand and model: names, not descriptions («Red Wing», «Woolrich»). */
        val names: Set<String> = emptySet(),
        /** Words of the garment type and of the keywords the user picked: they said so, so they are true. */
        val chosen: Set<String> = emptySet(),
    )

    /** [typeId] is the final garment (the user's, else the AI's); [materials] and [colors] the final lists. */
    fun facts(listing: Listing, typeId: String?, materials: List<String>, colors: List<String>): Facts {
        val chosen = buildSet {
            Garments.type(typeId.orEmpty())?.en?.forEach { addAll(tokens(it)) }
            listing.keywords.mapNotNull(Garments::keyword).forEach { k -> k.en.forEach { addAll(tokens(it)) } }
        }
        // What the seller wrote («es lana») also backs a fabric word.
        val backed = (materials + Reader.materials(listOf(listing.aiNotes))).distinct()
        return Facts(typeId, backed, colors, tokens(listing.brand) + tokens(listing.model), chosen)
    }

    /** The facts of an article being re-listed: its type (the user's, else the AI's), and the AI's materials and colours. */
    fun facts(listing: Listing, s: AiSuggestion): Facts =
        facts(listing, listing.type.ifEmpty { s.type.orEmpty() }.ifEmpty { null }, s.materials, s.colors)

    /** The title without its false words, or null when it is about another garment (or nothing is left). */
    fun fix(title: String, f: Facts): String? {
        val words = title.split(' ').filter { it.isNotBlank() }
        val keys = words.map(::key)
        val out = ArrayList<String>()
        val outKeys = ArrayList<String>()
        for ((i, w) in words.withIndex()) {
            val k = keys[i]
            val next = keys.getOrNull(i + 1).orEmpty()
            if (k !in f.chosen && k !in f.names) {
                val fabric = FABRICS[k]
                if (fabric != null && fabric.none { it in f.materials }) continue
                val shades = COLORS[k]
                if (shades != null && next !in COLOR_DETAILS && f.colors.isNotEmpty() && "Varios" !in f.colors &&
                    shades.none { it in f.colors }
                ) {
                    // «Light Blue» on a grey garment: the shade goes with its colour.
                    while (outKeys.lastOrNull()?.let { it in SHADE_WORDS } == true) {
                        out.removeAt(out.lastIndex); outKeys.removeAt(outKeys.lastIndex)
                    }
                    continue
                }
            }
            out += w
            outKeys += k
        }
        val type = Garments.type(f.typeId.orEmpty())
        if (type != null && type.group in CLOTHES) {
            for ((i, k) in outKeys.withIndex()) {
                val allowed = NOUNS[k] ?: continue
                if (k in f.names) continue
                val next = outKeys.getOrNull(i + 1).orEmpty()
                if (k == "cargo" && next in setOf("pockets", "pocket")) continue        // a detail of other trousers
                if (k == "dress" && next in DRESS_COMPOUNDS) continue                  // «Dress Shirt», «Dress Pants»
                if (type.id !in allowed) return null
            }
        }
        return out.joinToString(" ").trim().ifBlank { null }
    }

    /** Material search words (Wool, Leather…) the AI picks from the photos need that material to be real. */
    fun keywordTrue(id: String, f: Facts): Boolean {
        val k = Garments.keyword(id) ?: return false
        if (k.kind != KeywordKind.MATERIAL) return true
        if (id == "denim" && f.typeId in DENIM_TYPES) return true
        val needs = KEYWORD_FABRICS[id] ?: return true
        return needs.any { it in f.materials }
    }

    /** A hashtag naming a fabric the garment is not made of («cashmere», «woolsweater») is dropped. */
    fun hashtagTrue(tag: String, f: Facts): Boolean {
        val t = key(tag)
        if (t in f.names || f.names.any { it.length >= 4 && t.contains(it) }) return true       // «woolrich»
        for ((term, labels) in TAG_FABRICS) {
            val hit = t == term || (term.length >= 5 && t.contains(term)) ||
                (term.length in 3..4 && t.startsWith(term) && t.drop(term.length) in TAG_TAILS)
            if (hit && term !in f.chosen && labels.none { it in f.materials }) return false
        }
        return true
    }

    private fun key(word: String): String = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("'", "").replace("’", "").trim { !it.isLetterOrDigit() }

    private fun tokens(text: String): Set<String> =
        text.split(Regex("[\\s/]+")).map(::key).filter { it.isNotEmpty() }.toSet()

    private val CLOTHES = setOf(Group.PANTS, Group.TOP, Group.OUTER, Group.DRESS, Group.SET)
    private val DENIM_TYPES = setOf("jeans", "denimshorts", "denimjacket")
    private val COLOR_DETAILS = setOf("tab", "label", "logo", "stripe", "stripes", "patch", "detail", "details", "stitch",
        "stitching", "trim", "lining", "sole", "soles", "laces", "buttons", "piping", "embroidery", "embroidered")
    private val DRESS_COMPOUNDS = setOf("shirt", "pants", "trousers", "shoes", "jacket", "coat", "code", "style")
    private val SHADE_WORDS = setOf("light", "dark", "pale", "deep", "bright", "pastel", "dusty", "off")

    /** English fabric word → Vinted materials that make it true. */
    private val FABRICS: Map<String, Set<String>> = buildMap {
        fun m(labels: Set<String>, vararg words: String) = words.forEach { this[it] = labels }
        m(setOf("Lana", "Merino"), "wool", "woolen", "woollen")
        m(setOf("Merino"), "merino")
        m(setOf("Cachemira"), "cashmere", "cachemir", "cachemira")
        m(setOf("Alpaca"), "alpaca")
        m(setOf("Angora"), "angora")
        m(setOf("Algodón"), "cotton")
        m(setOf("Lino"), "linen")
        m(setOf("Seda"), "silk")
        m(setOf("Raso"), "satin")
        m(setOf("Terciopelo", "Velvetón"), "velvet", "velour")
        m(setOf("Pana"), "corduroy")
        m(setOf("Piel", "Cuero sintético", "Piel sintética"), "leather")
        m(setOf("Ante"), "suede")
        m(setOf("Polar"), "fleece")
        m(setOf("Poliéster"), "polyester")
        m(setOf("Nailon"), "nylon")
        m(setOf("Acrílico"), "acrylic")
        m(setOf("Viscosa"), "viscose", "rayon")
        m(setOf("Elastano"), "spandex", "elastane", "lycra")
        m(setOf("Vaquero"), "denim")
        m(setOf("Tweed"), "tweed")
        m(setOf("Lona"), "canvas")
        m(setOf("Franela"), "flannel")
        m(setOf("Lentejuelas"), "sequin", "sequins", "sequined")
        m(setOf("Encaje"), "lace")
        m(setOf("Malla"), "mesh")
        m(setOf("Tul"), "tulle")
        m(setOf("Chifón"), "chiffon")
    }

    /** Material search words (Garments.keywords) → Vinted materials that make them true. */
    private val KEYWORD_FABRICS: Map<String, Set<String>> = mapOf(
        "denim" to setOf("Vaquero"),
        "wool" to setOf("Lana", "Merino"),
        "cashmere" to setOf("Cachemira"),
        "leather" to setOf("Piel", "Cuero sintético", "Piel sintética"),
        "suede" to setOf("Ante"),
        "linen" to setOf("Lino"),
        "corduroy" to setOf("Pana"),
        "fleece" to setOf("Polar"),
        "nylon" to setOf("Nailon"),
        "canvas" to setOf("Lona"),
    )

    /** Fabric words in hashtags (English and Spanish) → Vinted materials. «vaquero», «denim» are not here: they are the garment. */
    private val TAG_FABRICS: Map<String, Set<String>> = buildMap {
        FABRICS.forEach { (w, l) -> if (w != "denim") this[w] = l }
        this["lana"] = setOf("Lana", "Merino")
        this["cachemir"] = setOf("Cachemira")
        this["algodon"] = setOf("Algodón")
        this["lino"] = setOf("Lino")
        this["seda"] = setOf("Seda")
        this["cuero"] = setOf("Piel", "Cuero sintético", "Piel sintética")
        this["terciopelo"] = setOf("Terciopelo", "Velvetón")
        this["pana"] = setOf("Pana")
    }

    private val TAG_TAILS = setOf("sweater", "jumper", "jacket", "coat", "pullover", "cardigan", "knit", "hat", "scarf",
        "shirt", "pants", "trousers", "jersey", "vest", "dress", "skirt", "blend", "vintage")

    /** Garment word → the garment types it is true for (only clothes; shoes and accessories are not checked). */
    private val NOUNS: Map<String, Set<String>> = buildMap {
        fun m(types: Set<String>, vararg words: String) = words.forEach { this[it] = types }
        m(setOf("jeans", "denimjacket", "denimshorts"), "jeans")
        m(setOf("jeans", "trousers", "cargo", "joggers", "tracksuit"), "pants", "trousers")
        m(setOf("trousers"), "chino", "chinos")
        m(setOf("cargo"), "cargo", "cargos")
        m(setOf("joggers", "tracksuit"), "jogger", "joggers", "sweatpants")
        m(setOf("denimshorts", "shorts"), "shorts")
        m(setOf("denimshorts"), "jorts")
        m(setOf("skirt"), "skirt")
        m(setOf("dress"), "dress")
        m(setOf("jumpsuit"), "jumpsuit", "dungarees", "overalls", "romper")
        m(setOf("tracksuit"), "tracksuit")
        m(setOf("tshirt", "longsleeve", "football", "tanktop"), "t-shirt", "tshirt", "tee", "tees")
        m(setOf("shirt", "overshirt", "polo", "football", "tshirt", "longsleeve", "top"), "shirt")
        m(setOf("hoodie", "zipsweat"), "hoodie", "hoody", "hooded")
        m(setOf("sweatshirt", "hoodie", "zipsweat"), "sweatshirt")
        m(setOf("sweatshirt", "sweater"), "crewneck")
        m(setOf("sweater", "sweatshirt", "cardigan"), "sweater", "jumper", "pullover", "knitwear")
        m(setOf("cardigan"), "cardigan")
        m(setOf("top", "shirt"), "blouse")
        m(setOf("tanktop", "top"), "tank", "singlet")
        m(setOf("vest", "tanktop", "top"), "vest", "gilet")
        m(setOf("jacket", "denimjacket", "bomber", "leatherjacket", "trackjacket", "windbreaker", "fleecejacket",
            "puffer", "overshirt", "blazer", "coat"), "jacket", "jackets")
        m(setOf("coat", "puffer", "windbreaker"), "coat", "overcoat", "trench", "parka")
        m(setOf("puffer", "vest"), "puffer")
        m(setOf("bomber", "jacket"), "bomber")
        m(setOf("blazer"), "blazer")
        m(setOf("windbreaker", "jacket"), "windbreaker", "anorak")
        m(setOf("overshirt", "shirt", "jacket"), "overshirt")
    }

    /** English colour word → Vinted colours of the same family. «Heather» and the like are not colours. */
    private val COLORS: Map<String, Set<String>> = buildMap {
        fun m(labels: Set<String>, vararg words: String) = words.forEach { this[it] = labels }
        m(setOf("Negro"), "black")
        m(setOf("Blanco"), "white")
        m(setOf("Crema", "Blanco", "Beige"), "cream", "ivory", "offwhite")
        m(setOf("Beige", "Crema", "Marrón", "Caqui"), "beige", "sand", "tan", "camel", "oatmeal", "taupe", "stone")
        m(setOf("Marrón", "Beige"), "brown", "chocolate", "mocha", "coffee")
        m(setOf("Gris", "Plateado"), "grey", "gray", "charcoal", "slate", "graphite", "anthracite")
        m(setOf("Plateado", "Gris"), "silver")
        m(setOf("Dorado", "Amarillo", "Mostaza"), "gold")
        m(setOf("Rojo", "Burdeos", "Coral"), "red")
        m(setOf("Burdeos", "Rojo", "Morado"), "burgundy", "maroon", "wine", "bordeaux")
        m(setOf("Rosa", "Fucsia", "Coral", "Lila"), "pink")
        m(setOf("Fucsia", "Rosa"), "fuchsia", "magenta")
        m(setOf("Morado", "Lila", "Burdeos"), "purple", "violet", "plum")
        m(setOf("Lila", "Morado"), "lilac", "lavender")
        m(setOf("Azul", "Azul claro", "Azul marino", "Turquesa"), "blue")
        m(setOf("Azul marino", "Azul"), "navy")
        m(setOf("Azul", "Azul marino"), "indigo")
        m(setOf("Turquesa", "Azul", "Verde", "Menta"), "turquoise", "teal", "aqua", "cyan")
        m(setOf("Verde", "Verde oscuro", "Menta", "Caqui"), "green")
        m(setOf("Caqui", "Verde", "Verde oscuro"), "olive", "khaki", "army")
        m(setOf("Menta", "Verde"), "mint")
        m(setOf("Amarillo", "Mostaza"), "yellow")
        m(setOf("Mostaza", "Amarillo"), "mustard")
        m(setOf("Naranja", "Naranja pastel", "Coral", "Rojo", "Marrón"), "orange", "rust", "terracotta")
        m(setOf("Coral", "Naranja", "Rosa", "Rojo"), "coral")
        m(setOf("Naranja pastel", "Coral", "Rosa", "Beige"), "peach")
    }
}
