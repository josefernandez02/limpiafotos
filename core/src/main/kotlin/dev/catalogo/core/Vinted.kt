package dev.catalogo.core

import kotlinx.serialization.Serializable

@Serializable
enum class Gender(val label: String) {
    MEN("Hombre"),
    WOMEN("Mujer"),
}

/** Closed lists of Vinted Spain: where a garment is filed and its colours. */
object Vinted {

    val colors: List<String> = listOf(
        "Negro", "Gris", "Blanco", "Crema", "Beige", "Marrón", "Caqui", "Azul", "Azul marino", "Azul claro",
        "Turquesa", "Verde", "Verde oscuro", "Menta", "Amarillo", "Mostaza", "Naranja", "Albaricoque", "Coral",
        "Rojo", "Burdeos", "Rosa", "Fucsia", "Morado", "Lila", "Plata", "Dorado", "Multicolor",
    )

    private val CLOTHES = listOf(
        "Vaqueros › Vaqueros rectos", "Vaqueros › Vaqueros ajustados", "Vaqueros › Vaqueros pitillo",
        "Vaqueros › Vaqueros anchos", "Vaqueros › Vaqueros rotos",
        "Pantalones › Chinos", "Pantalones › Pantalones de vestir", "Pantalones › Pantalones cargo",
        "Pantalones › Pantalones de chándal", "Pantalones › Otros pantalones",
        "Pantalones cortos › Pantalones cortos vaqueros", "Pantalones cortos › Pantalones cortos deportivos",
        "Pantalones cortos › Pantalones cortos cargo", "Pantalones cortos › Otros pantalones cortos",
        "Camisetas › Camisetas lisas", "Camisetas › Camisetas estampadas", "Camisetas › Camisetas de manga larga",
        "Camisetas › Camisetas sin mangas", "Camisetas › Polos",
        "Camisas › Camisas lisas", "Camisas › Camisas de cuadros", "Camisas › Camisas de rayas",
        "Camisas › Camisas estampadas", "Camisas › Camisas vaqueras",
        "Jerséis y sudaderas › Sudaderas", "Jerséis y sudaderas › Sudaderas con capucha",
        "Jerséis y sudaderas › Sudaderas con cremallera", "Jerséis y sudaderas › Jerséis de cuello redondo",
        "Jerséis y sudaderas › Jerséis de cuello alto", "Jerséis y sudaderas › Jerséis de cuello en V",
        "Jerséis y sudaderas › Jerséis con cremallera", "Jerséis y sudaderas › Cárdigans",
        "Jerséis y sudaderas › Chalecos de punto",
        "Abrigos y chaquetas › Chaquetas vaqueras", "Abrigos y chaquetas › Bombers",
        "Abrigos y chaquetas › Chaquetas de cuero", "Abrigos y chaquetas › Cortavientos",
        "Abrigos y chaquetas › Chaquetas acolchadas y plumíferos", "Abrigos y chaquetas › Forros polares",
        "Abrigos y chaquetas › Sobrecamisas", "Abrigos y chaquetas › Chaquetas de chándal",
        "Abrigos y chaquetas › Chalecos", "Abrigos y chaquetas › Abrigos", "Abrigos y chaquetas › Parkas",
        "Abrigos y chaquetas › Gabardinas", "Abrigos y chaquetas › Otras chaquetas",
        "Trajes y americanas › Americanas", "Ropa deportiva › Chándales", "Ropa deportiva › Camisetas deportivas",
    )

    private val WOMEN_ONLY = listOf(
        "Vaqueros › Vaqueros de campana", "Vaqueros › Vaqueros de tiro alto", "Vaqueros › Vaqueros boyfriend",
        "Tops y camisetas › Blusas", "Tops y camisetas › Tops cortos", "Tops y camisetas › Tops de tirantes",
        "Tops y camisetas › Bodies",
        "Vestidos › Vestidos cortos", "Vestidos › Vestidos midi", "Vestidos › Vestidos largos",
        "Faldas › Minifaldas", "Faldas › Faldas midi", "Faldas › Faldas largas",
        "Monos y petos › Monos", "Monos y petos › Petos",
    )

    private val SHOES = listOf(
        "Calzado › Zapatillas deportivas", "Calzado › Botas", "Calzado › Botines", "Calzado › Zapatos de vestir",
        "Calzado › Mocasines", "Calzado › Sandalias",
    )

    private val ACCESSORIES = listOf(
        "Bolsos › Bandoleras", "Bolsos › Bolsos de mano", "Bolsos › Mochilas", "Bolsos › Riñoneras",
        "Accesorios › Gorras", "Accesorios › Gorros", "Accesorios › Cinturones", "Accesorios › Bufandas",
        "Accesorios › Gafas de sol", "Accesorios › Carteras",
    )

    /** Every category path, e.g. "Hombre › Ropa › Vaqueros › Vaqueros rectos". */
    val categories: List<String> = buildList {
        for (g in Gender.entries) {
            val clothes = if (g == Gender.WOMEN) {
                CLOTHES.map { it.replace("Camisetas ›", "Tops y camisetas ›") } + WOMEN_ONLY
            } else {
                CLOTHES
            }
            clothes.forEach { add("${g.label} › Ropa › $it") }
            SHOES.forEach { add("${g.label} › $it") }
            ACCESSORIES.forEach { add("${g.label} › $it") }
        }
    }.distinct()

    /**
     * Letter size for a waist size such as "W32 L34", using [gender]'s usual
     * equivalence. Null when the size has no W or already is a letter.
     */
    fun letterSize(size: String, gender: Gender?): String? {
        if (gender == null) return null
        val w = Regex("(?i)\\bW\\s?(\\d{2})\\b").find(size)?.groupValues?.get(1)?.toInt() ?: return null
        return when (gender) {
            Gender.MEN -> when {
                w <= 28 -> "XS"
                w <= 30 -> "S"
                w <= 32 -> "M"
                w <= 34 -> "L"
                w <= 36 -> "XL"
                else -> "XXL"
            }
            Gender.WOMEN -> when {
                w <= 24 -> "XXS"
                w <= 26 -> "XS"
                w <= 28 -> "S"
                w <= 30 -> "M"
                w <= 32 -> "L"
                w <= 34 -> "XL"
                else -> "XXL"
            }
        }
    }
}
