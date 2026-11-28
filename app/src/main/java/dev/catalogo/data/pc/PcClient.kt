package dev.catalogo.data.pc

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.model.PcLink
import dev.catalogo.core.sales.ShippingLabels
import dev.catalogo.data.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Client of the PC that creates the drafts (whatsapp-likes, servidor.py).
 * Every call carries the shared token; errors come back as [CatalogException]
 * with a message the user can act on.
 */
class PcClient(private val resolver: ContentResolver) {

    /** One article as the PC sees it. */
    data class Job(val ref: String, val status: String, val message: String, val itemId: String, val url: String, val game: String)

    /** The PC program: what it is doing and what comes next. */
    data class Status(
        val text: String, val next: String, val paused: Boolean, val queue: Map<String, Int>, val incidents: Int = 0,
        val lastIncident: String = "", val health: Health? = null, val today: List<GameDay> = emptyList(),
        /** The user is using the PC: the program waits (and goes on by itself) until they stop. */
        val usingPc: Boolean = false,
        /** Which PC it is (its profile name), for when several are connected. */
        val name: String = "",
    )

    /** One game today: hours played out of hours it had, and the reasons the rest were lost. */
    data class GameDay(val game: String, val hours: Int, val played: Int, val reasons: Map<String, Int>)

    /** What the PC's watchdog saw in the last hours: games played, how many fine, with warning, serious. */
    data class Health(val hours: Int, val games: Int, val ok: Int, val warnings: Int, val serious: Int, val last: List<HealthEntry>,
        val today: List<TodayGame> = emptyList())

    /** One game today as the PC judged it: label is "perfecto", "revisar" or "perdido". */
    data class TodayGame(val at: Long, val game: String, val time: String, val label: String, val summary: String)

    data class HealthEntry(val at: Long, val game: String, val time: String, val level: String, val summary: String)

    /** A game link that got no like after several tries: to look at by hand. */
    data class Incident(
        val id: String, val at: Long, val game: String, val url: String, val reason: String,
        val level: String = "", val title: String = "", val summary: String = "",
        val details: List<String> = emptyList(), val actions: List<String> = emptyList(),
        val links: List<String> = emptyList(), val repeated: Boolean = false,
    ) {
        /** A watchdog warning (a game left unfinished, a repeat…) as opposed to a single link without like. */
        val isWarning get() = level == "aviso" || level == "grave"
    }

    /** Automatic offers to people who favourite an article: switch, today's numbers and the latest ones. */
    data class Offers(
        val working: Boolean, val waiting: String, val today: Int, val errorsToday: Int, val total: Int,
        val lastRunAt: Long, val lastRunSent: Int, val lastRunWhy: String, val last: List<OfferEntry>,
    )

    /** A game the PC can play, and whether its switch is on. [id] is the PC's own name for it. */

    data class OfferEntry(val at: Long, val user: String, val title: String, val result: String, val price: Double, val offer: Double, val reason: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun status(link: PcLink): Status {
        val o = call(link, "GET", "/estado", null)
        val queue = (o["cola"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }.orEmpty()
        return Status(o.str("estado"), o.str("siguiente"), o.str("pausado") == "true", queue,
            o.str("incidencias").toIntOrNull() ?: 0, o.str("incidencia_ultima"), (o["salud"] as? JsonObject)?.health(), today(o["agenda"] as? JsonObject),
            o.str("usando_pc") == "true", o.str("equipo"))
    }

    /** The game groups the PC has switched off, or null if its program is older and does not know them. */
    fun gamesOff(link: PcLink): Set<String>? = call(link, "GET", "/juegos", null).off()

    fun setGamesOff(link: PcLink, off: Set<String>): Set<String>? =
        call(link, "POST", "/juegos", buildJsonObject { put("apagados", JsonArray(off.sorted().map(::JsonPrimitive))) }.toString()).off()

    private fun JsonObject.off(): Set<String>? =
        (this["apagados"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()

    fun offers(link: PcLink): Offers = call(link, "GET", "/ofertas", null).offers()

    /** "mandar" (go through the likes until reaching what was already offered) or "parar". */
    fun offersControl(link: PcLink, action: String): Offers =
        call(link, "POST", "/ofertas", buildJsonObject { put("accion", action) }.toString()).offers()

    private fun JsonObject.offers(): Offers {
        fun n(k: String) = str(k).toIntOrNull() ?: 0
        val lastRun = this["ultima"] as? JsonObject
        return Offers(str("modo") == "trabajando", str("esperando"), n("hoy_ofertadas"),
            n("hoy_errores"), n("total_ofertadas"),
            ((lastRun?.str("ts")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(), lastRun?.str("ofertadas")?.toIntOrNull() ?: 0, lastRun?.str("motivo").orEmpty(),
            (this["ultimas"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let {
                    OfferEntry(((it.str("ts").toDoubleOrNull() ?: 0.0) * 1000).toLong(), it.str("usuario"), it.str("titulo"),
                        it.str("resultado"), it.str("precio").toDoubleOrNull() ?: 0.0, it.str("oferta").toDoubleOrNull() ?: 0.0, it.str("motivo"))
                }
            })
    }

    /** The sales the PC has read in Vinted, as it sends them (see VintedSales.parsePc). */
    fun sales(link: PcLink): String = call(link, "GET", "/ventas", null).toString()

    /**
     * "mandar" (read now), "refrescar" (read if the last time is old) or "parar".
     * [prefixes]: the article codes to recognise.
     */
    fun salesControl(link: PcLink, action: String, prefixes: List<String>): String = call(link, "POST", "/ventas",
        buildJsonObject {
            put("accion", action)
            put("prefijos", JsonArray(prefixes.map(::JsonPrimitive)))
        }.toString()).toString()

    /** The shipping labels the PC was last asked for and how they go (see Labels.parse). */
    fun labels(link: PcLink): String = call(link, "GET", "/etiquetas", null).toString()

    /** "mandar" (take out the labels of [requests]) or "parar". */
    fun labelsControl(link: PcLink, action: String, requests: List<ShippingLabels.Request> = emptyList()): String = call(link, "POST", "/etiquetas",
        buildJsonObject {
            put("accion", action)
            putJsonArray("pedidos") {
                for (r in requests) add(buildJsonObject {
                    put("id", r.id)
                    put("codigo", r.code)
                    put("titulo", r.title)
                })
            }
        }.toString()).toString()

    /** The PDF of the last lot (see Labels.parsePdf). It carries the whole file, so it may take longer. */
    fun labelsPdf(link: PcLink): String = call(link, "GET", "/etiquetas/pdf", null, SEND_TIMEOUT).toString()

    fun incidents(link: PcLink): List<Incident> =
        (call(link, "GET", "/incidencias", null)["incidencias"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let {
                Incident(it.str("id"), ((it.str("cuando").toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                    it.str("juego"), it.str("url"), it.str("motivo"),
                    level = it.str("nivel"), title = it.str("titulo"), summary = it.str("resumen"),
                    details = it.strs("detalles"), actions = it.strs("acciones"), links = it.strs("enlaces"),
                    repeated = it.str("reincidente") == "true")
            }
        }

    fun reviewed(link: PcLink, id: String) {
        call(link, "POST", "/incidencias/${enc(id)}/revisada", "{}")
    }

    fun jobs(link: PcLink): List<Job> =
        (call(link, "GET", "/articulos", null)["articulos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.job() }

    /** Sends the article with its photos (in order); the PC answers with its job. */
    fun send(link: PcLink, ref: String, article: JsonObject, photos: List<Uri>): Job {
        val body = buildJsonObject {
            put("articulo", article)
            putJsonArray("fotos") { for (p in photos) add(JsonPrimitive(Base64.encodeToString(jpeg(p), Base64.NO_WRAP))) }
        }
        return call(link, "PUT", "/articulos/${enc(ref)}", body.toString(), SEND_TIMEOUT).job()
    }

    /** A state set by hand («borrador», «publicado», «jugado», «fuera»), with what to do ([mode]) and the link. */
    fun setState(link: PcLink, ref: String, state: String, mode: String, url: String, account: String): Job {
        val body = buildJsonObject {
            put("estado", state)
            put("modo", mode)
            put("url", url)
            put("cuenta", account)
        }
        return call(link, "POST", "/articulos/${enc(ref)}/estado", body.toString()).job()
    }

    fun retry(link: PcLink, ref: String): Job = call(link, "POST", "/articulos/${enc(ref)}/reintentar", "{}").job()

    /** The app's log, kept on the PC next to its own (datos/<perfil>/movil.log). */
    fun log(link: PcLink, text: String) {
        val body = kotlinx.serialization.json.buildJsonObject { put("texto", kotlinx.serialization.json.JsonPrimitive(text)) }
        call(link, "PUT", "/registro", body.toString(), 30_000)
    }

    fun delete(link: PcLink, ref: String) {
        call(link, "DELETE", "/articulos/${enc(ref)}", null)
    }

    /** "iniciar", "pausar", "reanudar", "detener" or "seguir" (stop waiting for the user to leave the PC). */
    fun control(link: PcLink, action: String): Status {
        call(link, "POST", "/control", buildJsonObject { put("accion", action) }.toString())
        return status(link)
    }

    // ---------- internals ----------

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.strs(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

    private fun today(o: JsonObject?): List<GameDay> = o?.mapNotNull { (game, v) ->
        (v as? JsonObject)?.let {
            fun n(k: String) = it.str(k).toIntOrNull() ?: 0
            val reasons = (it["motivos"] as? JsonObject)?.mapValues { (_, c) -> (c as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }.orEmpty()
            GameDay(game, n("horas"), n("jugados"), reasons)
        }
    }.orEmpty()

    private fun JsonObject.health(): Health? {
        if (str("juegos").isBlank()) return null
        fun n(k: String) = str(k).toIntOrNull() ?: 0
        val last = (this["ultimos"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let {
                HealthEntry(((it.str("cuando").toDoubleOrNull() ?: 0.0) * 1000).toLong(), it.str("juego"), it.str("hora"),
                    it.str("nivel"), it.str("resumen"))
            }
        }
        val todayGames = (this["hoy"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let {
                TodayGame(((it.str("cuando").toDoubleOrNull() ?: 0.0) * 1000).toLong(), it.str("juego"), it.str("hora"),
                    it.str("etiqueta"), it.str("resumen"))
            }
        }
        return Health(n("horas"), n("juegos"), n("ok"), n("avisos"), n("graves"), last, todayGames)
    }

    private fun JsonObject.job() = Job(str("ref"), str("estado"), str("motivo"), str("item_id"), str("url"), str("juego"))

    private fun enc(ref: String) = URLEncoder.encode(ref, "UTF-8")

    private fun call(link: PcLink, method: String, path: String, body: String?, readTimeout: Int = 20_000): JsonObject {
        val url = link.url.trim().trimEnd('/') + path
        val c = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw CatalogException("Dirección del PC no válida")
        }
        try {
            c.connectTimeout = 8_000
            c.readTimeout = readTimeout
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer ${link.token.trim()}")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val started = System.currentTimeMillis()
            val code = c.responseCode
            if (path != "/registro") AppLog.i("pc", "$method $path -> $code (${System.currentTimeMillis() - started} ms)")
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            return when (code) {
                in 200..299 -> o
                401 -> throw CatalogException("El PC no acepta la clave: revísala en Ajustes")
                404 -> throw CatalogException(
                    when (path) {
                        "/ventas", "/etiquetas" -> "El programa del PC es antiguo: ciérralo y vuelve a abrirlo"
                        "/etiquetas/pdf" -> "El PC ya no tiene el PDF de las etiquetas: vuelve a pedirlas"
                        else -> "El PC no conoce este artículo"
                    },
                )
                else -> throw CatalogException("PC: ${o.str("error").ifBlank { "error $code" }}")
            }
        } catch (e: CatalogException) {
            throw e
        } catch (e: IOException) {
            AppLog.w("pc", "$method $path: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            throw CatalogException(UNREACHABLE)
        } finally {
            c.disconnect()
        }
    }

    /** Photo re-encoded as JPEG up to [SIDE] px: no metadata, light enough to send over mobile data. */
    private fun jpeg(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw CatalogException("Hay una foto que no se puede leer")
        val scale = SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    companion object {
        /** The PC is off, its program closed, or there is no way to it. */
        const val UNREACHABLE = "No llego al PC"
        /** Long side of the photos sent: Vinted shows them smaller. */
        private const val SIDE = 2048
        private const val SEND_TIMEOUT = 120_000
    }
}
