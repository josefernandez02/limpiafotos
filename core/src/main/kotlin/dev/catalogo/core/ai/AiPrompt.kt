package dev.catalogo.core.ai

import dev.catalogo.core.ai.Ai.Account
import dev.catalogo.core.ai.Ai.TITLE_MAX
import dev.catalogo.core.ai.AiCategories.categoriesFor
import dev.catalogo.core.catalog.RelistRules
import dev.catalogo.core.listing.Garments
import dev.catalogo.core.listing.KeywordKind
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.listing.TagReader
import dev.catalogo.core.listing.TagReading
import dev.catalogo.core.model.Relist
import dev.catalogo.core.vinted.VintedTaxonomy
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The text sent to the model with the photos. */
object AiPrompt {
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
}
