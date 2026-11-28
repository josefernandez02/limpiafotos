package dev.catalogo.core.ai

import dev.catalogo.core.catalog.RelistRules
import dev.catalogo.core.listing.Garments
import dev.catalogo.core.listing.KeywordKind
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.listing.ListingWriter
import dev.catalogo.core.listing.TagReader
import dev.catalogo.core.listing.TagReading
import dev.catalogo.core.listing.TitleGuard
import dev.catalogo.core.listing.TrouserFits
import dev.catalogo.core.model.Relist
import dev.catalogo.core.vinted.Gender
import dev.catalogo.core.vinted.VintedTaxonomy
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

/** What the AI decided for an article, kept with it. Titles are per account id. */
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
    /** Up to 3 of [VintedTaxonomy.materials], as the label (or the fabric) shows. */
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
    /** Account id to title. */
    val titles: Map<String, String>,
    val findings: List<String>,
    val hashtags: List<String> = emptyList(),
    /** Brand and model read in the photos, only when the user left them blank. */
    val brand: String? = null,
    val model: String? = null,
    val materials: List<String> = emptyList(),
    val doubts: List<String> = emptyList(),
    /** What the first call read in the labels (null when it was not made or failed). */
    val reading: TagReading? = null,
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
            TagReader.compositionText(reading))
}

class AiException(message: String) : Exception(message)

/**
 * Prompt, request and answer checking for the listing call. A first call
 * ([TagReader]) reads the labels; this one gets that reading as facts, observes the
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

    /**
     * The part of the prompt for a re-listed article: the titles and hashtags it had, which the new ones must not repeat
     * (see [RelistRules] for how much they must differ). Empty for a first listing.
     */
    fun relistingPrompt(avoid: Relist?, accounts: List<Account>, rejected: Relist? = null): String {
        if (avoid == null) return ""
        val titles = accounts.mapNotNull { a -> avoid.titles[a.id]?.let { "${a.prefix}: $it" } }
        return buildString {
            append("\nREPUBLICACION (la prenda se vuelve a subir con fotos nuevas; el anuncio no puede ser el mismo)\n")
            if (titles.isNotEmpty()) append("- Títulos usados antes:\n").append(titles.joinToString("\n") { "  $it" }).append('\n')
            append("- Cada título nuevo lleva al menos ${RelistRules.MIN_WORDS} palabras que no estaban en el anterior de su cuenta: sinónimos REALES de la misma prenda (Jeans / Denim Pants, Sweater / Jumper, Pants / Trousers), otra palabra de corte, diseño, época o estilo que sea cierta, otro matiz del MISMO color. ")
            append("Mismas reglas de arriba: orden de la cuenta, palabras que de verdad se buscan, nada inventado, y la VERDAD manda: nunca cambies de material (Wool no se cambia por Cashmere, Merino ni nada «más bonito»), de color ni de prenda para conseguir palabras nuevas. ")
            append("Si no te salen palabras nuevas verdaderas, usa época, estética, detalles visibles o el corte antes que un sinónimo falso. Cambiar solo el orden no vale.\n")
            if (avoid.hashtags.isNotEmpty()) {
                append("- Hashtags usados antes: ").append(avoid.hashtags.joinToString(", ")).append('\n')
                append("- Los nuevos incluyen al menos ${RelistRules.MIN_TAGS} que no estén en esa lista (otras búsquedas ciertas: sinónimos, el otro idioma, estilo, época o un detalle visible). Conserva los esenciales (marca, prenda) y mantén de 10 a 12.\n")
            }
            if (rejected != null) {
                // A retry: the same prompt gives the same answer, so the model is told what it already wrote and was refused.
                append("- RECHAZADO: tu respuesta anterior seguía casi igual que la publicada. No la repitas; cambia más, sin inventar nada:\n")
                for (a in accounts) rejected.titles[a.id]?.takeIf { it.isNotBlank() }?.let { append("  título rechazado ${a.prefix}: ").append(it).append('\n') }
                if (rejected.hashtags.isNotEmpty()) append("  hashtags rechazados: ").append(rejected.hashtags.joinToString(", ")).append('\n')
            }
        }
    }

    fun prompt(listing: Listing, accounts: List<Account>, reading: TagReading? = null, avoid: Relist? = null, rejected: Relist? = null): String {
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
            - notas_del_vendedor son hechos y pistas del vendedor y pesan MÁS que tu impresión de las fotos: úsalos siempre (estilo, diseño, procedencia, keywords…), aunque no se vean.
              · Si la nota dice dónde va algo («en el título», «en hashtags», «categoría X»), hazlo así.
              · Si no lo dice, decide tú dónde trae más visitas, y ponlo en UN solo sitio (título o hashtags, no los dos): lo que la gente escribe al buscar esa prenda va al título (Coogi, Made in USA, Made in Italy, Selvedge, Big E, Carhartt WIP…); lo que solo suma como etiqueta, a hashtags (madeincanada, madeinportugal…).
            - LECTURA_DE_ETIQUETAS (si viene) es lo que se ha leído con detalle en todas las fotos: créetelo antes que a tu impresión. La composición manda sobre los materiales y la talla de la etiqueta sobre cualquier estimación.
            - Describe solo lo que se ve en las fotos o dice el vendedor. Si algo no se ve con seguridad, déjalo vacío (null o lista vacía).
            - Títulos y hashtags dicen solo cosas VERDADERAS de esta prenda (mira VERDAD en TÍTULOS): un material, color o prenda que no case con tus datos es publicidad engañosa y el comprador puede reclamar. Además se comprueba por código y lo que no cuadre se borra.
            - Devuelve solo el JSON pedido.

            2. OBSERVA ("hallazgos", rellénalo antes que nada)
            Etiquetas, logos, parches, bordados, texto legible, tejido, lavado, corte y detalles; cada uno con dónde se ve. Para cada logo o dibujo di si es BORDADO (hilo en relieve) o ESTAMPADO (impreso), y su tamaño (pequeño en el pecho, grande en todo el frente, por toda la prenda): decide la categoría y las keywords.

            3. COMPLETA LO QUE FALTA
            - marca: solo si marca es null. Escríbela si se lee o es inconfundible (p. ej. la pestaña roja de Levi's en el bolsillo trasero). Si no, null. Si es "SIN MARCA", null.
            - modelo: solo si modelo es null y se lee en una etiqueta o parche (p. ej. "501" en el parche de cuero). Si no, null.
            - talla: solo si talla es null. Primero la que se lea en una etiqueta, tal cual. Si no hay etiqueta, estímala con las medidas en plano, la forma y las fotos:
              · Pantalones de hombre: W = cintura en plano × 2 / 2,54, redondeada (42 cm → W33). Nunca confundas los cm con la W. Tiro y largo solo confirman proporciones.
              · Pantalones de mujer y todo lo demás: talla en letra XS, S, M, L, XL o XXL. En prendas de arriba manda el pecho en plano (hombros apoyan; largo y manga solos son señales débiles); en chaquetas y abrigos, el pecho contando la holgura normal de una prenda de abrigo.
              · Vaqueros de mujer con etiqueta W: equivalencia W23-24=XXS, W25-26=XS, W27-28=S, W29-30=M, W31-32=L, W33-34=XL, W35-36=XXL (W30 de mujer NO es S).
              · Un número suelto en una etiqueta de vaqueros (30, 32) suele ser la W; en otras prendas no supongas W ni EU por parecido.
              · Etiqueta de EDAD infantil (12 years, 14Y, 16 años): no es una talla adulta; estima por las medidas o null, y no la menciones en título ni hashtags.
              · Una etiqueta adulta legible manda sobre las medidas. Si no hay base razonable, null.
            - tipo_prenda: uno de TIPOS. Si viene, devuelve ese.
            - palabras: de PALABRAS, solo las que se vean claramente. No devuelvas épocas. Las de material (lana, cachemir, cuero, lino, pana, forro polar…) solo si ese material está en tus "materiales" o en la composición: un tejido no se adivina por la foto ni por lo suave que parezca.
            - sexo: "hombre" o "mujer". Si viene en DATOS_DEL_USUARIO, ese. Si no, el de LECTURA_DE_ETIQUETAS si lo trae. Si no, decide con etiquetas, modelo (Levi's 5xx hombre, 3xx/7xx mujer), formato de talla (W/L suele ser hombre; 24-31 o 34-44 mujer) y patrón (tiro alto y cadera marcada → mujer).
            - corte: en pantalones y vaqueros, uno de skinny, slim, tapered, straight, relaxed, baggy, bootcut, flare, wide, mom, boyfriend; el de LECTURA_DE_ETIQUETAS si lo trae (manda el número de modelo: Levi's 501/505/514 straight, 511 slim, 512 tapered, 510 skinny, 517 bootcut, 550 relaxed, 568/569/578 baggy). Si no, por la silueta: compara el bajo con la rodilla y el muslo. En otras prendas, null.
            - categoria: copia EXACTA de una línea de CATEGORIAS. Entre las que de verdad describen la prenda (suele haber varias), elige la que más visitas trae: la que los compradores miran para algo así. Ejemplos: jersey de punto con dibujo → De punto (mejor que Cuello redondo); camiseta con un estampado o gráfico grande → Camisetas estampadas, nunca lisas; camiseta de un color con un logo pequeño (bordado o no) → Camisetas lisas, no Otras camisetas; pantalón de chándal → Joggers o Chándales. Si dos dan las mismas visitas, la más exacta. Siempre la FINAL más concreta (la línea más larga que siga siendo cierta), coherente con tipo_prenda, el sexo y el corte (vaqueros straight → Vaqueros rectos; slim/tapered → Vaqueros ajustados o pitillo; bootcut/flare de mujer → Vaqueros de campana; sudadera con capucha → Sudaderas y sudaderas con capucha; con cremallera → Sudaderas con cremallera; jersey de punto → el de su cuello (Cuello redondo, Cuello de pico, Cuello alto) o De punto; camiseta de manga larga → Camisetas de manga larga). Debe empezar por el sexo elegido. Nunca construyas una categoría que no esté en la lista.
            - materiales: de MATERIALES, hasta 3. Si hay composición (en LECTURA_DE_ETIQUETAS o en una foto), SOLO los de la composición (100% algodón → Algodón; 98% algodón 2% elastano → Algodón, Elastano), aunque la prenda sea vaquera. Sin composición, solo si el tejido es inconfundible (vaquero → Vaquero). Si no, lista vacía.
            - dudas: nombres de los campos (de categoria, talla, colores, materiales, marca) que hayas rellenado SIN seguridad, para que el vendedor los revise. Nunca pongas talla si sin_etiqueta_de_talla es true (el vendedor tampoco la sabe: decide tú con las medidas y las fotos) ni materiales si notas_del_vendedor dice el material. Por ejemplo un corte que no se distingue en las fotos (slim o recto) → categoria. Si todo está claro, lista vacía. Sé honesto: es mejor una duda que un error.
            - colores: de COLORES, el color del TEJIDO (no fondo, luz, sombras, etiquetas ni costuras). Normalmente UNO. Un segundo solo si la prenda tiene de verdad dos colores distintos (bloques, rayas, estampado); nunca dos tonos parecidos (Beige y Caqui, Azul y Azul claro).

            4. TÍTULOS (en inglés, uno por cuenta dentro de "titulos")
            Vinted muestra la foto, no el título: el título sirve para salir en las búsquedas. Mete las palabras que la gente escribe, en un orden natural.
            - Sigue el orden y los ejemplos de la cuenta (CUENTAS). Si falta un dato (marca, modelo, época), sáltalo; nunca lo inventes.
            - VERDAD (obligatorio): decide primero tipo_prenda, colores y materiales y escribe los títulos DESPUÉS, solo con ellos.
              · Material o tejido (Wool, Cotton, Leather, Silk, Linen, Cashmere, Merino, Fleece, Velvet, Denim…): solo si está en tus "materiales" (o en la composición o las notas del vendedor). Son materiales distintos, nunca sinónimos: Wool ≠ Cashmere ≠ Merino ≠ Alpaca; Cotton ≠ Linen; Leather ≠ Suede. «Lana y nailon» no es cashmere. Mejor sin material que con uno falso.
              · Color: solo los de "colores". Un gris no lleva Blue ni un negro Brown. Los matices (Heather, Charcoal, Light Blue, Navy…) solo dentro de ese mismo color.
              · Prenda: la de tipo_prenda y sus sinónimos reales (Sweater = Jumper = Pullover; Jeans = Denim Pants; Pants = Trousers; Hoodie = Hooded Sweatshirt). Nunca otra prenda: un jersey no es Puffer, Hoodie ni Jacket; unos vaqueros no son Cargo; una camiseta no es una Shirt de botones; una chaqueta no es un Coat si no es abrigo.
              · Corte, estilo y diseño: solo si se ven o los dice el vendedor, y sin contradecirse (Skinny y Baggy a la vez no).
            - Piensa como un revendedor que quiere visitas sin engañar: el título lleva las keywords que de verdad se buscan para ESA prenda, sin miedo pero sin relleno. Un título flaco pierde visitas: «Nike PSG Track Pants Sporty Black Pink» se queda corto; «Nike PSG Track Pants Vintage Y2K Sporty Football Black Pink» trae muchas más búsquedas.
            - Época: la de DATOS_DEL_USUARIO, una sola vez (Y2K o 2000s, nunca las dos). Si no viene, puedes poner UNA estética de época (Y2K, 90s, Retro) solo si el diseño la tiene claramente. "Vintage" si hay época o la prenda lo es o lo parece claramente.
            - Diseño visible que se busca: estampado grande (Graphic, Big Print, Big Logo, All Over Print), logo pequeño bordado (Embroidered Logo), punto con dibujo (Nordic, Fair Isle, Cable Knit, Coogi Style), fútbol o equipo (Football, Team, Club), deporte (Sporty, Track, Athletic)…
            - Corte o forma visible: Straight, Slim, Skinny, Bootcut, Flare, Baggy, Wide Leg, Oversized, Boxy, Cropped…
            - Hasta 2 palabras de estilo SOLO si encajan de verdad: Workwear, Streetwear, Old Money, Retro, Preppy, Grunge, Washed, Faded, Denim…
            - Termina con el color en inglés y con el matiz real: Blue, Light Blue, Dark Blue, Black, Grey, White, Khaki, Beige, Brown, Navy, Burgundy, Cream, Olive…
            - Nunca talla, estado, precio, SKU, emojis, signos ni relleno (Amazing, Must Have, Rare…).
            - Cada palabra una sola vez: si ya pusiste el corte (Skinny) no lo repitas más adelante.
            - Title Case, máximo $TITLE_MAX caracteres. Las cuentas no pueden tener el mismo título.

            5. HASHTAGS ("hashtags": de 10 a 12, sin #, minúsculas, sin tildes ni espacios)
            Son palabras que los compradores escriben al buscar; tienen que atraer ventas, no quedar bonitos. Vinted España comparte catálogo con otros países: mezcla inglés y español.
            - En este orden: marca, marca+modelo y marca+vintage si es vintage (levis, levis505, levisvintage); prenda en inglés y en español (jeans, vaqueros, vaquerosvintage); corte en inglés y en español (straightjeans, vaquerosrectos); época (90s; con Y2K pon y2k y 2000s); estilo si encaja de verdad (streetwear, workwear, oldmoney, grunge, skater); vintage; lo útil de notas_del_vendedor que no hayas puesto ya en el título (madeincanada, madeinargentina).
            - Cada hashtag es una búsqueda distinta que puede traer visitas. Una traducción (jeans / vaqueros) sí suma; una variante de lo mismo no (90s y 90sfashion, vintage y vintagestyle, denim y denimjeans): pon solo una.
            - Nada que no sea cierto ni genéricos que no venden (ootd, aesthetic, outfit, fashion, style, vinted, vintedfrance, instafashion).
            - Sin SKU ni códigos: la app los añade al final.

            DATOS_DEL_USUARIO
            $user
            ${TagReader.facts(reading)?.let { "\nLECTURA_DE_ETIQUETAS\n$it\n" }.orEmpty()}
            CUENTAS (orden del título y ejemplos)
            ${accounts.joinToString("\n") { "${it.prefix}: ${VOICES[it.style.mod(VOICES.size)].trimIndent()}" }}

            TIPOS (id|nombre)
            ${Garments.types.joinToString("\n") { "${it.id}|${it.en[0]}" }}

            PALABRAS (id|en inglés|qué significa)
            ${Garments.keywords.filter { it.kind != KeywordKind.EPOCH }.joinToString("\n") { "${it.id}|${it.en[0]}|${it.help}" }}

            COLORES: ${VintedTaxonomy.colors.joinToString(", ")}

            MATERIALES: ${VintedTaxonomy.materials.joinToString(", ")}

            CATEGORIAS
            ${categoriesFor(listing.gender, listing.type).joinToString("\n")}
        """.trimIndent().let { text ->
            // (added after trimming: its lines have no indentation of their own)
            if (avoid == null) text else text.replace(Regex("\n[ \t]*DATOS_DEL_USUARIO\n")) { relistingPrompt(avoid, accounts, rejected) + "\nDATOS_DEL_USUARIO\n" }
        }
    }

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
        VintedTaxonomy.categories.firstOrNull { it == text }?.let { return it }
        fun norm(x: String) = x.lowercase().replace(Regex("\\s*[›>/|]\\s*"), "›").replace(Regex("\\s+"), " ").trim()
        val n = norm(text)
        VintedTaxonomy.categories.firstOrNull { norm(it) == n }?.let { return it }
        // The last two levels are usually enough to tell which one it is.
        val tail = n.split('›').takeLast(2).joinToString("›")
        return VintedTaxonomy.categories.filter { norm(it).endsWith(tail) }.singleOrNull()
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
        reading: TagReading? = null,
        avoid: Relist? = null,
        rejected: Relist? = null,
    ): String = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt(listing, accounts, reading, avoid, rejected)) }
                    for (p in photos.take(PHOTOS)) {
                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", p) } }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema(accounts))
            put("temperature", if (rejected != null) 0.9 else 0.5)   // after a refused answer, more variety
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
            "corte" to str(TrouserFits.ALL, nullable = true),
            "categoria" to str(nullable = true),
            "colores" to arr(str(VintedTaxonomy.colors), max = VintedTaxonomy.MAX_COLORS),
            "materiales" to arr(str(VintedTaxonomy.materials), max = VintedTaxonomy.MAX_MATERIALS),
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
