package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.catalogo.core.Ai
import dev.catalogo.core.AiException
import dev.catalogo.core.AiSuggestion
import dev.catalogo.core.Listing
import dev.catalogo.core.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Google Gemini (free tier of AI Studio). Asks Google which Flash models the
 * key can use, tries the best Flash first and falls back to Flash-Lite when
 * one runs out of free quota, so new model names need no app update.
 */
class Gemini(private val resolver: ContentResolver) {

    data class Answer(val suggestion: AiSuggestion, val model: String, val seconds: Int)

    private var models: List<String>? = null

    /**
     * Two calls, like a careful seller: first only reading ([Reader]: every chosen
     * photo, larger, to catch the labels), then the listing with that reading as
     * facts. If the reading fails (quota, timeout) the listing is still written
     * without it rather than leaving the article stuck.
     */
    suspend fun suggest(key: String, listing: Listing, accounts: List<Ai.Account>, photos: List<Uri>): Answer =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw AiException("Pon tu clave de Gemini en Ajustes")
            val started = System.currentTimeMillis()
            val reading = runCatching {
                val big = photos.take(Reader.PHOTOS).mapNotNull { runCatching { jpeg(it, READ_SIDE) }.getOrNull() }
                if (big.isEmpty()) null else Reader.parse(generate(key) { m, t -> Reader.request(big, m, t) }.first)
            }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException || e is IOException) throw e else null }
            val images = photos.take(Ai.PHOTOS).mapNotNull { runCatching { jpeg(it, MAX_SIDE) }.getOrNull() }
            if (images.isEmpty()) throw AiException("Haz fotos del artículo primero")
            val (text, model) = generate(key) { m, t -> Ai.request(listing, accounts, images, m, t, reading) }
            val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
            Answer(Ai.parse(text, listing, accounts, reading), model, seconds)
        }

    /** One generateContent with the best model that answers; returns (answer text, model). */
    private fun generate(key: String, body: (model: String, thinking: Boolean) -> String): Pair<String, String> {
        var last: AiException? = null
        for (model in models(key)) {
            var thinking = true
            while (true) {
                val (code, text) = try {
                    post("$BASE/models/$model:generateContent", key, body(model, thinking))
                } catch (e: SocketTimeoutException) {
                    last = AiException("Gemini ha tardado demasiado. Vuelve a intentarlo.")
                    break // try the next, lighter model
                } catch (e: UnknownHostException) {
                    throw IOException("Sin internet", e)
                } catch (e: IOException) {
                    throw IOException("Se ha cortado la conexión con Gemini", e)
                }
                when {
                    code == 200 -> return Ai.answerText(text) to model
                    code == 400 && "API_KEY" in text || code == 401 || code == 403 ->
                        throw AiException("La clave de Gemini no es válida")
                    // A model that does not accept the reasoning setting: same model without it.
                    code == 400 && thinking && "thinking" in text.lowercase() -> {
                        thinking = false
                        continue
                    }
                    // Out of free quota, overloaded or gone: next model.
                    code == 429 -> last = AiException("Se ha acabado el uso gratis de hoy. Prueba mañana.")
                    code == 404 || code >= 500 -> last = AiException("Gemini no responde ahora, prueba en un rato")
                    else -> throw AiException("Gemini ha dado un error ($code)")
                }
                break
            }
        }
        throw last ?: AiException("No hay modelos de Gemini disponibles")
    }

    /** Best Flash, then best Flash-Lite, then the rest; newest version first. */
    private fun models(key: String): List<String> {
        models?.let { return it }
        val found = runCatching {
            val (code, text) = get("$BASE/models?pageSize=1000", key)
            if (code != 200) return@runCatching emptyList<String>()
            val list = Json.parseToJsonElement(text).jsonObject["models"] as? JsonArray ?: return@runCatching emptyList()
            list.mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val methods = (o["supportedGenerationMethods"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
                val name = (o["name"] as? JsonPrimitive)?.content?.removePrefix("models/") ?: return@mapNotNull null
                name.takeIf { "generateContent" in methods && STABLE.matches(it) }
            }
        }.getOrDefault(emptyList())
        fun version(name: String) = STABLE.matchEntire(name)!!.groupValues[1].split('.').map { it.toInt() }.let { it + listOf(0, 0) }
        val byVersion = compareByDescending<String> { version(it)[0] }.thenByDescending { version(it)[1] }
        val flash = found.filter { !it.endsWith("-lite") }.sortedWith(byVersion)
        val lite = found.filter { it.endsWith("-lite") }.sortedWith(byVersion)
        val ordered = (flash.take(1) + lite.take(1) + flash.drop(1) + lite.drop(1)).ifEmpty { FALLBACK }
        return ordered.also { models = it }
    }

    /** Photo scaled to [side] px as base64 JPEG. */
    private fun jpeg(uri: Uri, side: Int): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Foto ilegible")
        val scale = side.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, if (side > MAX_SIDE) 85 else 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun get(url: String, key: String): Pair<Int, String> = call(url, key, null)

    private fun post(url: String, key: String, body: String): Pair<Int, String> = call(url, key, body)

    private fun call(url: String, key: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = if (body != null) ANSWER_TIMEOUT else 20_000
            c.setRequestProperty("x-goog-api-key", key.trim())
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Listing call: enough to see colour and cut; smaller photos make the answer faster. */
        private const val MAX_SIDE = 1024
        /** Reading call: labels and small print need more pixels. */
        private const val READ_SIDE = 1600
        /** Past this the model is stuck: the next, lighter one is tried. */
        private const val ANSWER_TIMEOUT = 75_000
        /** "gemini-2.5-flash", "gemini-3-flash-lite"…; no previews, image, audio or dated variants. */
        private val STABLE = Regex("gemini-(\\d+(?:\\.\\d+)?)-flash(?:-lite)?")
        private val FALLBACK = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
    }
}
