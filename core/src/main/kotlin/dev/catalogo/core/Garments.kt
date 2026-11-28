package dev.catalogo.core


object Garments {
    const val STYLES = 2

    private val CLOTHES = setOf(Group.PANTS, Group.TOP, Group.OUTER, Group.DRESS, Group.SET)

    val types: List<GarmentType> = listOf(
        GarmentType("jeans", "Vaqueros", Group.PANTS, listOf("Jeans", "Denim Pants"), "pantalon vaquero tejano denim"),
        GarmentType("trousers", "Chinos / de vestir", Group.PANTS, listOf("Chino Pants", "Trousers"), "pantalon chino vestir traje"),
        GarmentType("cargo", "Cargo", Group.PANTS, listOf("Cargo Pants", "Cargos"), "pantalon cargo bolsillos"),
        GarmentType("joggers", "Track pants", Group.PANTS, listOf("Track Pants", "Joggers"), "pantalon chandal deporte jogger"),
        GarmentType("denimshorts", "Shorts vaqueros", Group.PANTS, listOf("Denim Shorts", "Jorts"), "pantalon corto vaquero bermuda"),
        GarmentType("shorts", "Shorts deportivos", Group.PANTS, listOf("Athletic Shorts", "Gym Shorts"), "pantalon corto deporte bermuda"),
        GarmentType("tshirt", "Camiseta", Group.TOP, listOf("T-Shirt", "Tee"), "camiseta manga corta"),
        GarmentType("longsleeve", "Camiseta manga larga", Group.TOP, listOf("Long Sleeve Tee", "Longsleeve T-Shirt"), "camiseta larga"),
        GarmentType("football", "Camiseta de fútbol", Group.TOP, listOf("Football Shirt", "Soccer Jersey"), "futbol equipo jersey camiseta deporte"),
        GarmentType("shirt", "Camisa", Group.TOP, listOf("Shirt", "Button Up Shirt"), "camisa botones"),
        GarmentType("polo", "Polo", Group.TOP, listOf("Polo Shirt", "Polo Tee"), "polo cuello"),
        GarmentType("hoodie", "Sudadera con capucha", Group.TOP, listOf("Hoodie", "Hooded Sweatshirt"), "sudadera capucha hoodie"),
        GarmentType("sweatshirt", "Sudadera cuello redondo", Group.TOP, listOf("Crewneck Sweatshirt", "Crewneck"), "sudadera crewneck cuello redondo"),
        GarmentType("zipsweat", "Sudadera con cremallera", Group.TOP, listOf("Zip Up Hoodie", "Full Zip Sweatshirt"), "sudadera cremallera zip"),
        GarmentType("sweater", "Jersey de punto", Group.TOP, listOf("Sweater", "Jumper"), "jersey punto lana knit"),
        GarmentType("cardigan", "Cárdigan", Group.TOP, listOf("Cardigan", "Knit Cardigan"), "cardigan chaqueta punto botones"),
        GarmentType("tanktop", "Camiseta de tirantes", Group.TOP, listOf("Tank Top", "Vest Top"), "tirantes sin mangas"),
        GarmentType("top", "Top / blusa", Group.TOP, listOf("Top", "Blouse"), "top blusa"),
        GarmentType("overshirt", "Sobrecamisa", Group.OUTER, listOf("Overshirt", "Shirt Jacket"), "sobrecamisa camisa gruesa leñador"),
        GarmentType("jacket", "Chaqueta", Group.OUTER, listOf("Jacket", "Light Jacket"), "chaqueta ligera corta"),
        GarmentType("denimjacket", "Cazadora vaquera", Group.OUTER, listOf("Denim Jacket", "Jean Jacket"), "chaqueta vaquera tejana"),
        GarmentType("bomber", "Bomber", Group.OUTER, listOf("Bomber Jacket", "Flight Jacket"), "chaqueta bomber aviador"),
        GarmentType("leatherjacket", "Chaqueta de cuero", Group.OUTER, listOf("Leather Jacket", "Leather Coat"), "chaqueta cuero piel motera"),
        GarmentType("trackjacket", "Chaqueta de chándal", Group.OUTER, listOf("Track Jacket", "Track Top"), "chaqueta chandal deporte cremallera"),
        GarmentType("windbreaker", "Cortavientos", Group.OUTER, listOf("Windbreaker", "Shell Jacket"), "cortavientos chubasquero impermeable"),
        GarmentType("fleecejacket", "Forro polar", Group.OUTER, listOf("Fleece Jacket", "Polar Fleece"), "forro polar fleece"),
        GarmentType("coat", "Abrigo", Group.OUTER, listOf("Coat", "Overcoat"), "abrigo largo pano lana invierno"),
        GarmentType("puffer", "Plumífero", Group.OUTER, listOf("Puffer Jacket", "Down Jacket"), "plumifero acolchado anorak plumas"),
        GarmentType("vest", "Chaleco", Group.OUTER, listOf("Vest", "Gilet"), "chaleco"),
        GarmentType("blazer", "Blazer / americana", Group.OUTER, listOf("Blazer", "Suit Jacket"), "americana chaqueta traje blazer"),
        GarmentType("dress", "Vestido", Group.DRESS, listOf("Dress", "Dress"), "vestido"),
        GarmentType("skirt", "Falda", Group.DRESS, listOf("Skirt", "Skirt"), "falda"),
        GarmentType("jumpsuit", "Mono / peto", Group.DRESS, listOf("Jumpsuit", "Dungarees"), "mono peto overall"),
        GarmentType("tracksuit", "Chándal completo", Group.SET, listOf("Tracksuit", "Track Suit Set"), "chandal conjunto completo"),
        GarmentType("sneakers", "Zapatillas", Group.SHOES, listOf("Sneakers", "Trainers"), "zapatillas deportivas tenis"),
        GarmentType("boots", "Botas", Group.SHOES, listOf("Boots", "Boots"), "botas botines"),
        GarmentType("shoes", "Zapatos", Group.SHOES, listOf("Shoes", "Shoes"), "zapatos mocasines"),
        GarmentType("bag", "Bolso", Group.ACCESSORY, listOf("Bag", "Handbag"), "bolso bandolera"),
        GarmentType("backpack", "Mochila", Group.ACCESSORY, listOf("Backpack", "Rucksack"), "mochila"),
        GarmentType("cap", "Gorra", Group.ACCESSORY, listOf("Cap", "Baseball Cap"), "gorra visera"),
        GarmentType("beanie", "Gorro", Group.ACCESSORY, listOf("Beanie", "Knit Hat"), "gorro lana"),
        GarmentType("belt", "Cinturón", Group.ACCESSORY, listOf("Belt", "Waist Belt"), "cinturon correa"),
        GarmentType("scarf", "Bufanda", Group.ACCESSORY, listOf("Scarf", "Scarf"), "bufanda"),
        GarmentType("sunglasses", "Gafas de sol", Group.ACCESSORY, listOf("Sunglasses", "Shades"), "gafas sol"),
        GarmentType("wallet", "Cartera", Group.ACCESSORY, listOf("Wallet", "Card Wallet"), "cartera billetera monedero"),
    )

    private val E = KeywordKind.EPOCH
    private val A = KeywordKind.AESTHETIC
    private val F = KeywordKind.FIT
    private val M = KeywordKind.MATERIAL
    private val D = KeywordKind.DETAIL
    private val P = Group.PANTS
    private val T = Group.TOP
    private val O = Group.OUTER
    private val V = Group.DRESS
    private val S = Group.SET

    val keywords: List<Keyword> = listOf(
        // Época
        Keyword("70s", "70s", E, listOf("70s", "1970s"), "Años 70: colores tierra, campana, estampados."),
        Keyword("80s", "80s", E, listOf("80s", "1980s"), "Años 80: colores vivos, chándales de nylon, hombreras."),
        Keyword("90s", "90s", E, listOf("90s", "1990s"), "Años 90: ropa ancha, logos grandes, vaqueros holgados."),
        Keyword("y2k", "y2k", E, listOf("Y2K", "Y2K"), "Estilo de los 2000: tiro bajo, ajustado, logos y brillos."),
        // Estética
        Keyword("vintage", "vintage", A, listOf("Vintage", "Retro"), "Prenda antigua (unos 20 años o más) o con ese aire."),
        Keyword("retro", "retro", A, listOf("Retro", "Old School"), "Nueva, pero inspirada en otra década."),
        Keyword("streetwear", "streetwear", A, listOf("Streetwear", "Street Style"), "Ropa urbana: sudaderas, logos, corte ancho."),
        Keyword("workwear", "workwear", A, listOf("Workwear", "Utility"), "Ropa de trabajo resistente: Carhartt, Dickies, lona."),
        Keyword("gorpcore", "gorpcore", A, listOf("Gorpcore", "Outdoor"), "Ropa de montaña llevada en ciudad: The North Face, Patagonia."),
        Keyword("oldmoney", "old money", A, listOf("Old Money", "Quiet Luxury"), "Elegante y clásico, sin logos llamativos, tonos neutros."),
        Keyword("preppy", "preppy", A, listOf("Preppy", "Ivy Style"), "Estilo universitario americano: polos, rayas, Ralph Lauren."),
        Keyword("minimal", "minimal", A, listOf("Minimal", "Clean Look"), "Sencilla, lisa, sin estampados."),
        Keyword("skater", "skater", A, listOf("Skater", "Skate"), "Estilo skate: ancho y cómodo, marcas como Vans o Dickies."),
        Keyword("grunge", "grunge", A, listOf("Grunge", "Grunge Style"), "Desaliñado de los 90: cuadros, oscuro, desgastado."),
        Keyword("sporty", "sporty", A, listOf("Sporty", "Athletic"), "Deportiva: chándales, camisetas técnicas, marcas de deporte."),
        Keyword("cottagecore", "cottagecore", A, listOf("Cottagecore", "Countryside"), "Romántico y campestre: flores, encaje, lino, volantes.", setOf(T, V)),
        Keyword("coquette", "coquette", A, listOf("Coquette", "Girly"), "Muy femenino: lazos, rosa, encaje.", setOf(T, V)),
        Keyword("archive", "archive", A, listOf("Archive", "Designer Archive"), "Pieza antigua de diseñador o de una colección especial."),
        Keyword("rare", "rare", A, listOf("Rare", "Rare Find"), "Difícil de encontrar: modelo o color poco común."),
        // Corte
        Keyword("baggy", "baggy", F, listOf("Baggy", "Loose Fit"), "Muy ancho y holgado de arriba abajo.", setOf(P, S)),
        Keyword("oversized", "oversized", F, listOf("Oversized", "Oversize Fit"), "Más grande de lo normal a propósito; queda suelta.", setOf(T, O, S, V)),
        Keyword("boxy", "boxy", F, listOf("Boxy", "Boxy Fit"), "Corte cuadrado: ancha y algo corta.", setOf(T, O)),
        Keyword("relaxed", "relaxed", F, listOf("Relaxed", "Relaxed Fit"), "Holgada pero sin exagerar; cómoda.", setOf(P, T, O)),
        Keyword("straight", "straight", F, listOf("Straight Leg", "Straight Fit"), "Pierna recta: igual de ancha de la rodilla al tobillo.", setOf(P)),
        Keyword("slim", "slim", F, listOf("Slim", "Slim Fit"), "Ajustada al cuerpo, sin llegar a pitillo.", setOf(P, T, O)),
        Keyword("wideleg", "wide leg", F, listOf("Wide Leg", "Wide Fit"), "Pierna muy ancha de arriba abajo.", setOf(P)),
        Keyword("bootcut", "bootcut", F, listOf("Bootcut", "Boot Cut"), "Recto y se abre un poco abajo, para que quepa la bota.", setOf(P)),
        Keyword("flare", "flare", F, listOf("Flare", "Flared"), "Se abre mucho desde la rodilla (de campana).", setOf(P, V)),
        Keyword("highwaist", "high waist", F, listOf("High Waisted", "High Rise"), "Cintura alta, por encima del ombligo.", setOf(P, V)),
        Keyword("lowrise", "low rise", F, listOf("Low Rise", "Low Waist"), "Cintura baja, sobre la cadera.", setOf(P, V)),
        Keyword("cropped", "cropped", F, listOf("Cropped", "Crop"), "Más corta de lo normal (a la cintura o al tobillo).", setOf(T, O, P)),
        // Material
        Keyword("denim", "denim", M, listOf("Denim", "Jean"), "Tela vaquera, la de los vaqueros.", setOf(P, O, V, T)),
        Keyword("wool", "lana", M, listOf("Wool", "Pure Wool"), "Lana: abriga y suele ser de calidad.", setOf(T, O, P, Group.ACCESSORY)),
        Keyword("cashmere", "cachemir", M, listOf("Cashmere", "Cashmere Wool"), "Cachemir: lana muy suave y de calidad.", setOf(T, O, Group.ACCESSORY)),
        Keyword("leather", "cuero", M, listOf("Leather", "Genuine Leather"), "Cuero o piel auténtica.", setOf(O, P, Group.SHOES, Group.ACCESSORY)),
        Keyword("suede", "ante", M, listOf("Suede", "Suede Leather"), "Ante: piel con tacto de terciopelo.", setOf(O, Group.SHOES, Group.ACCESSORY)),
        Keyword("linen", "lino", M, listOf("Linen", "Pure Linen"), "Lino: tela fresca de verano.", setOf(T, P, V)),
        Keyword("corduroy", "pana", M, listOf("Corduroy", "Cord"), "Pana: tela con rayas en relieve.", setOf(P, O, T)),
        Keyword("fleece", "forro polar", M, listOf("Fleece", "Polar Fleece"), "Forro polar: tela suave y calentita.", setOf(O, T)),
        Keyword("nylon", "nylon", M, listOf("Nylon", "Nylon Shell"), "Tela fina y algo brillante de chándales y cortavientos.", setOf(O, S, P, Group.ACCESSORY)),
        Keyword("knit", "punto", M, listOf("Knit", "Knitted"), "De punto, tejida como un jersey.", setOf(T, V, Group.ACCESSORY)),
        Keyword("cableknit", "ochos", M, listOf("Cable Knit", "Cable Knitted"), "Punto con trenzas en relieve (ochos).", setOf(T)),
        Keyword("canvas", "lona", M, listOf("Canvas", "Duck Canvas"), "Lona gruesa, típica de la ropa de trabajo.", setOf(O, P, Group.ACCESSORY)),
        // Detalles
        Keyword("faded", "desteñido", D, listOf("Faded", "Sun Faded"), "Color desgastado por el uso; muy buscado en vintage.", CLOTHES),
        Keyword("washed", "lavado", D, listOf("Washed", "Stone Wash"), "Lavado a la piedra: tono más claro y gastado.", setOf(P, O, T, V)),
        Keyword("distressed", "con rotos", D, listOf("Distressed", "Ripped"), "Con rotos o desgastes.", setOf(P, O, T)),
        Keyword("embroidered", "bordado", D, listOf("Embroidered", "Embroidery"), "Con bordado: logo o dibujo cosido.", CLOTHES + Group.ACCESSORY),
        Keyword("biglogo", "logo grande", D, listOf("Big Logo", "Logo Print"), "Logo grande y bien visible.", setOf(T, O, S, Group.ACCESSORY)),
        Keyword("graphic", "estampado", D, listOf("Graphic", "Graphic Print"), "Con un dibujo o estampado grande.", setOf(T)),
        Keyword("carpenter", "carpenter", D, listOf("Carpenter", "Painter"), "De carpintero: bolsillo lateral y trabilla para el martillo.", setOf(P)),
        Keyword("cargopockets", "bolsillos cargo", D, listOf("Cargo Pockets", "Utility Pockets"), "Bolsillos grandes a los lados.", setOf(P, O)),
        Keyword("quarterzip", "media cremallera", D, listOf("Quarter Zip", "1/4 Zip"), "Cremallera corta en el cuello.", setOf(T)),
        Keyword("fullzip", "cremallera entera", D, listOf("Full Zip", "Zip Up"), "Cremallera de arriba abajo.", setOf(T, O)),
        Keyword("singlestitch", "single stitch", D, listOf("Single Stitch", "Single Stitched"), "Camiseta antigua con una sola costura en mangas y bajo: señal de los 80-90.", setOf(T)),
        Keyword("madeinusa", "made in USA", D, listOf("Made in USA", "USA Made"), "Fabricada en Estados Unidos (lo pone la etiqueta)."),
        Keyword("madeinitaly", "made in Italy", D, listOf("Made in Italy", "Italian Made"), "Fabricada en Italia (lo pone la etiqueta)."),
        Keyword("deadstock", "deadstock", D, listOf("Deadstock", "New Old Stock"), "Antigua pero sin usar nunca, a veces con etiqueta."),
    )

    private val typeById = types.associateBy { it.id }
    private val keywordById = keywords.associateBy { it.id }

    fun type(id: String): GarmentType? = typeById[id]

    fun keyword(id: String): Keyword? = keywordById[id]

    fun keywordsFor(group: Group?): List<Keyword> = keywords.filter { it.appliesTo(group) }

    /** Types whose name, synonyms or English names contain every word of [query]. */
    fun search(query: String): List<GarmentType> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return types.filter { t ->
            val hay = normalize(t.label + " " + t.aliases + " " + t.en.joinToString(" "))
            words.all { it in hay }
        }
    }

    private fun normalize(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

}
