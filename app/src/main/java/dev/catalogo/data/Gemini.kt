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
import java.net.URL

/**
 * Google Gemini (free tier of AI Studio). Asks Google which Flash models the
 * key can use, tries the best Flash first and falls back to Flash-Lite when
 * one runs out of free quota, so new model names need no app update.
 */
class Gemini(private val resolver: ContentResolver) {

    data class Answer(val suggestion: AiSuggestion, val model: String)

    private var models: List<String>? = null

    suspend fun suggest(key: String, listing: Listing, accounts: List<Ai.Account>, photos: List<Uri>): Answer =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw AiException("Pon tu clave de Gemini en Ajustes")
            val images = photos.take(Ai.PHOTOS).mapNotNull { runCatching { jpeg(it) }.getOrNull() }
            if (images.isEmpty()) throw AiException("Haz fotos del artículo primero")
            val body = Ai.request(listing, accounts, images)
            var last: AiException? = null
            for (model in models(key)) {
                val (code, text) = try {
                    post("$BASE/models/$model:generateContent", key, body)
                } catch (e: IOException) {
                    throw AiException("Sin conexión con Gemini")
                }
                when {
                    code == 200 -> {
                        val answer = Ai.answerText(text)
                        return@withContext Answer(Ai.parse(answer, listing, accounts), model)
                    }
                    code == 400 && "API_KEY" in text || code == 401 || code == 403 ->
                        throw AiException("La clave de Gemini no es válida")
                    // Out of free quota, overloaded or gone: next model.
                    code == 429 -> last = AiException("Se ha acabado el uso gratis de hoy. Prueba mañana.")
                    code == 404 || code >= 500 -> last = AiException("Gemini no responde ahora, prueba en un rato")
                    else -> throw AiException("Gemini ha dado un error ($code)")
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

    /** Photo scaled to [MAX_SIDE] px as base64 JPEG: enough to read labels, light on data. */
    private fun jpeg(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Foto ilegible")
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun get(url: String, key: String): Pair<Int, String> = call(url, key, null)

    private fun post(url: String, key: String, body: String): Pair<Int, String> = call(url, key, body)

    private fun call(url: String, key: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
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
        private const val MAX_SIDE = 1280
        /** "gemini-2.5-flash", "gemini-3-flash-lite"…; no previews, image, audio or dated variants. */
        private val STABLE = Regex("gemini-(\\d+(?:\\.\\d+)?)-flash(?:-lite)?")
        private val FALLBACK = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
    }
}
