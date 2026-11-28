package dev.catalogo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.catalogo.core.Ai
import dev.catalogo.core.GeminiModelPool
import dev.catalogo.core.AiException
import dev.catalogo.core.AiSuggestion
import dev.catalogo.core.Listing
import dev.catalogo.core.TagReader
import dev.catalogo.core.TagReading
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
class GeminiClient(private val resolver: ContentResolver) {

    data class Answer(val suggestion: AiSuggestion, val model: String, val seconds: Int)

    private var models: List<String>? = null
    private val pool = GeminiModelPool()
    /** The reading of the last articles, by photo: asking again for the same photos does not read the labels again. */
    private val readings = object : LinkedHashMap<List<String>, TagReading>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, TagReading>?) = size > 8
    }

    /**
     * Two calls, like a careful seller: first only reading ([TagReader]: every chosen
     * photo, larger, to catch the labels), then the listing with that reading as
     * facts. If the reading fails (quota, timeout) the listing is still written
     * without it rather than leaving the article stuck.
     */
    suspend fun suggest(key: String, listing: Listing, accounts: List<Ai.Account>, photos: List<Uri>, avoid: dev.catalogo.core.Relist? = null): Answer =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw AiException("Pon tu clave de Gemini en Ajustes")
            val started = System.currentTimeMillis()
            val photoKey = photos.take(TagReader.PHOTOS).map { it.toString() }
            val reading = runCatching {
                readings[photoKey] ?: run {
                    val big = photos.take(TagReader.PHOTOS).mapNotNull { runCatching { jpeg(it, READ_SIDE) }.getOrNull() }
                    if (big.isEmpty()) null else TagReader.parse(generate(key, lite = true) { m, t -> TagReader.request(big, m, t) }.first)
                        ?.also { readings[photoKey] = it }
                }
            }.onSuccess { r ->
                AppLog.i("ia", "lectura: composición=${r?.composition} hecho_en=${r?.origin} talla=${r?.sizeLabel} modelo=${r?.model}")
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException || e is IOException) throw e
                AppLog.w("ia", "lectura sin resultado: ${e.message}")
                null
            }
            val images = photos.take(Ai.PHOTOS).mapNotNull { runCatching { jpeg(it, MAX_SIDE) }.getOrNull() }
            if (images.isEmpty()) throw AiException("Haz fotos del artículo primero")
            val (text, model) = generate(key) { m, t -> Ai.request(listing, accounts, images, m, t, reading, avoid) }
            val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
            val s = Ai.parse(text, listing, accounts, reading)
            AppLog.i("ia", "anuncio con $model en $seconds s: categoría=${s.category} materiales=${s.materials} talla=${s.sizeLabel} dudas=${s.doubts}")
            Answer(s, model, seconds)
        }

    /**
     * A re-listed article whose answer still repeats the old title or hashtags: a short text-only call that edits just
     * those (see [dev.catalogo.core.RelistRepair]) and merges the result into [current].
     */
    suspend fun fixRepeated(key: String, listing: Listing, avoid: dev.catalogo.core.Relist, current: dev.catalogo.core.AiSuggestion, accounts: List<Ai.Account>): dev.catalogo.core.AiSuggestion =
        withContext(Dispatchers.IO) {
            // What is true of the garment: the rewrite must not change fabric, colour or garment to get new words.
            val facts = dev.catalogo.core.TitleGuard.facts(listing, current)
            val fresh = current.hashtags.count { t -> t.lowercase() !in avoid.hashtags.map { it.lowercase() }.toSet() }
            val needTags = if (avoid.hashtags.isNotEmpty()) (dev.catalogo.core.RelistRules.MIN_TAGS - fresh).coerceAtLeast(0) else 0
            val (text, model) = generate(key, lite = true) { _, _ -> dev.catalogo.core.RelistRepair.request(avoid, current, accounts, needTags, facts) }
            val edit = dev.catalogo.core.RelistRepair.parse(text, accounts)
            AppLog.i("ia", "arreglo de repetidos con $model: títulos=${edit.titles.values} hashtags=${edit.hashtags}")
            dev.catalogo.core.RelistRepair.merge(avoid, current, edit, accounts, facts)
        }

    /**
     * One generateContent with the best model that answers; returns (answer text, model).
     * [lite]: Flash-Lite first. The reading goes there and the listing to Flash, so each
     * call spends the free quota of a different model.
     *
     * Each model is asked once per cooldown ([pool]): a 429 blocks it for what Google says (or until the day's quota
     * renews), a 503 for a few seconds, a 404 for good. When none is ready it waits for the first to be, up to
     * [MAX_WAIT_MS]; past that it says why. So a busy minute costs one request per model, not a sweep of the list.
     */
    private suspend fun generate(key: String, lite: Boolean = false, body: (model: String, thinking: Boolean) -> String): Pair<String, String> {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (true) {
            val listed = models(key)
            val all = if (lite) listed.filter { it.contains("lite") } + listed.filterNot { it.contains("lite") } else listed
            if (pool.usable(all).isEmpty()) throw AiException("No hay modelos de Gemini disponibles")
            val ready = pool.ready(all)
            if (ready.isEmpty()) {
                val wait = pool.wait(all)
                if (System.currentTimeMillis() + wait > deadline) {
                    throw AiException(when (pool.why(all)) {
                        GeminiModelPool.Why.DAILY -> "Se ha acabado el uso gratis de hoy. Prueba mañana."
                        GeminiModelPool.Why.MINUTE -> "Gemini está al límite de uso por minuto, prueba en un rato"
                        GeminiModelPool.Why.BUSY -> "Gemini no responde ahora, prueba en un rato"
                    })
                }
                AppLog.i("ia", "ningún modelo libre: espero ${wait / 1000 + 1} s")
                kotlinx.coroutines.delay(wait + 500)
                continue
            }
            for (model in ready) {
                var thinking = pool.thinks(model)
                while (true) {
                    val (code, text) = try {
                        post("$BASE/models/$model:generateContent", key, body(model, thinking))
                    } catch (e: SocketTimeoutException) {
                        AppLog.i("ia", "$model: sin respuesta a tiempo")
                        pool.block(model, 10_000, GeminiModelPool.Why.BUSY)
                        break // next model
                    } catch (e: UnknownHostException) {
                        throw IOException("Sin internet", e)
                    } catch (e: IOException) {
                        throw IOException("Se ha cortado la conexión con Gemini", e)
                    }
                    AppLog.i("ia", "$model -> $code")
                    when {
                        code == 200 -> return Ai.answerText(text) to model
                        code == 400 && "API_KEY" in text || code == 401 || code == 403 ->
                            throw AiException("La clave de Gemini no es válida")
                        // A model that does not accept the reasoning setting: same model without it, and remembered.
                        code == 400 && thinking && "thinking" in text.lowercase() -> {
                            pool.withoutThinking(model)
                            thinking = false
                            continue
                        }
                        code == 429 -> {
                            val daily = "PerDay" in text
                            val secs = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
                            AppLog.i("ia", "$model 429: ${if (daily) "cuota del día" else "límite por minuto"}${secs?.let { ", Google pide esperar ${it.toInt()} s" } ?: ""}")
                            if (daily) pool.daily(model) else pool.block(model, (Math.ceil(secs ?: 20.0).toLong().coerceIn(5L, 70L)) * 1000, GeminiModelPool.Why.MINUTE)
                        }
                        code == 404 -> pool.missing(model)
                        code >= 500 -> pool.block(model, 15_000, GeminiModelPool.Why.BUSY)
                        else -> throw AiException("Gemini ha dado un error ($code)")
                    }
                    break
                }
            }
        }
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
        /** Longest it waits for a model to be free before saying it cannot. */
        private const val MAX_WAIT_MS = 120_000L
        private val STABLE = Regex("gemini-(\\d+(?:\\.\\d+)?)-flash(?:-lite)?")
        private val FALLBACK = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
    }
}
