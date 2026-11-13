package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.catalogo.core.CatalogException
import dev.catalogo.core.PcLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client of the PC that creates the drafts (whatsapp-likes, servidor.py).
 * Every call carries the shared token; errors come back as [CatalogException]
 * with a message the user can act on.
 */
class Desk(private val resolver: ContentResolver) {

    /** One article as the PC sees it. */
    data class Job(val ref: String, val status: String, val message: String, val itemId: String, val url: String, val game: String)

    /** The PC program: what it is doing and what comes next. */
    data class Status(val text: String, val next: String, val paused: Boolean, val queue: Map<String, Int>)

    private val json = Json { ignoreUnknownKeys = true }

    fun status(link: PcLink): Status {
        val o = call(link, "GET", "/estado", null)
        val queue = (o["cola"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }.orEmpty()
        return Status(o.str("estado"), o.str("siguiente"), o.str("pausado") == "true", queue)
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

    fun retry(link: PcLink, ref: String): Job = call(link, "POST", "/articulos/${enc(ref)}/reintentar", "{}").job()

    /** The app's log, kept on the PC next to its own (datos/<perfil>/movil.log). */
    fun log(link: PcLink, text: String) {
        val body = kotlinx.serialization.json.buildJsonObject { put("texto", kotlinx.serialization.json.JsonPrimitive(text)) }
        call(link, "PUT", "/registro", body.toString(), 30_000)
    }

    fun delete(link: PcLink, ref: String) {
        call(link, "DELETE", "/articulos/${enc(ref)}", null)
    }

    /** "iniciar", "pausar", "reanudar" or "detener". */
    fun control(link: PcLink, action: String): Status {
        call(link, "POST", "/control", buildJsonObject { put("accion", action) }.toString())
        return status(link)
    }

    // ---------- internals ----------

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

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
                404 -> throw CatalogException("El PC no conoce este artículo")
                else -> throw CatalogException("PC: ${o.str("error").ifBlank { "error $code" }}")
            }
        } catch (e: CatalogException) {
            throw e
        } catch (e: IOException) {
            AppLog.w("pc", "$method $path: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            throw CatalogException("No llego al PC (¿encendido, programa abierto, misma wifi o Tailscale?)")
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
        /** Long side of the photos sent: Vinted shows them smaller. */
        private const val SIDE = 2048
        private const val SEND_TIMEOUT = 120_000
    }
}
