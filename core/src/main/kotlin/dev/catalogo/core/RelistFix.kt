package dev.catalogo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Last step for a re-listed article whose new answer still repeats the old title or hashtags. Asking the AI again
 * to "write something different" gives the same answer (the account rules and photos decide it), but editing a
 * given title or adding to a given list is an easy task: this builds that small text-only request and merges its
 * answer into the one that fell short.
 */
object RelistFix {
    /** What came back: new titles by account prefix and extra hashtags. */
    data class Edit(val titles: Map<String, String>, val hashtags: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Accounts whose title still repeats the old one. */
    fun stale(avoid: Relist, titles: Map<String, String>): List<String> =
        avoid.titles.filter { (id, old) -> !Relisting.titleChanged(old, titles[id].orEmpty()) }.keys.toList()

    fun prompt(avoid: Relist, current: AiSuggestion, accounts: List<Ai.Account>, needTags: Int): String = buildString {
        val stale = stale(avoid, current.titles)
        append("Eres redactor de anuncios de Vinted. Una prenda se vuelve a subir y el anuncio nuevo no puede parecerse al anterior.\n")
        if (stale.isNotEmpty()) {
            append("\nTÍTULOS: reescribe cada título cambiando al menos ${Relisting.MIN_WORDS + 1} palabras por sinónimos o por otra palabra cierta que también se busque ")
            append("(Jeans / Denim, Pants / Trousers, Slim Fit / Slim Leg, Classic / Original, Vintage / Retro, Black / Washed Black). ")
            append("Mismo orden y estructura, sin repetir palabras, sin inventar datos nuevos, Title Case, máximo ${ListingWriter.TITLE_MAX} caracteres, sin talla, estado, precio ni emojis. ")
            append("Cada cuenta lleva un título distinto.\n")
            for (a in accounts) if (a.id in stale) {
                append("  ${a.prefix} (título a cambiar): ").append(current.titles[a.id] ?: avoid.titles[a.id]).append('\n')
                append("  ${a.prefix} (no uses estas palabras solas, ya salieron en el anterior): ").append(avoid.titles[a.id]).append('\n')
            }
        }
        if (needTags > 0) {
            append("\nHASHTAGS: dame ").append(needTags + 2).append(" hashtags NUEVOS (sin #, minúsculas, sin tildes ni espacios) que no estén en ninguna de estas listas. ")
            append("Tienen que ser ciertos para la prenda y búsquedas distintas que traigan visitas: traducciones (inglés / español), sinónimos, otro corte o estilo, época, detalle visible. ")
            append("Nada genérico (ootd, aesthetic, fashion, style, vinted).\n")
            append("  usados antes: ").append(avoid.hashtags.joinToString(", ")).append('\n')
            append("  los de ahora: ").append(current.hashtags.joinToString(", ")).append('\n')
        }
    }

    fun request(avoid: Relist, current: AiSuggestion, accounts: List<Ai.Account>, needTags: Int): String = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", prompt(avoid, current, accounts, needTags)) }) }
            })
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("temperature", 0.9)
            putJsonObject("responseSchema") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("titulos") {
                        put("type", "OBJECT")
                        putJsonObject("properties") { for (a in accounts) putJsonObject(a.prefix) { put("type", "STRING"); put("nullable", true) } }
                    }
                    putJsonObject("hashtags") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
                }
                putJsonArray("required") { add("titulos"); add("hashtags") }
            }
        }
    }.toString()

    fun parse(answer: String, accounts: List<Ai.Account>): Edit {
        val o = runCatching { json.parseToJsonElement(answer.trim().removePrefix("```json").removeSuffix("```")).jsonObject }.getOrNull()
            ?: return Edit(emptyMap(), emptyList())
        val t = o["titulos"] as? JsonObject
        val titles = accounts.mapNotNull { a ->
            (t?.get(a.prefix) as? JsonPrimitive)?.contentOrNull?.let(::title)?.takeIf { it.isNotEmpty() }?.let { a.prefix to it }
        }.toMap()
        val tags = (o["hashtags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return Edit(titles, ListingWriter.clean(tags))
    }

    private fun title(raw: String): String {
        var t = Ai.distinctWords(raw.replace(Regex("\\s+"), " ").trim().trim('"', '.', '-', ' '))
        if (t.length > ListingWriter.TITLE_MAX) t = t.take(ListingWriter.TITLE_MAX).substringBeforeLast(' ')
        return t
    }

    /**
     * The answer that fell short, with the edit applied only where it helps: a title is replaced if the edited one
     * is different enough (and not equal to another account's); fresh hashtags are added and, to stay within 12,
     * the oldest repeated ones at the end go first.
     */
    fun merge(avoid: Relist, current: AiSuggestion, edit: Edit, accounts: List<Ai.Account>): AiSuggestion {
        val titles = current.titles.toMutableMap()
        val used = titles.values.map { it.lowercase() }.toMutableSet()
        for (a in accounts) {
            val old = avoid.titles[a.id] ?: continue
            if (Relisting.titleChanged(old, titles[a.id].orEmpty())) continue
            val new = edit.titles[a.prefix] ?: continue
            if (Relisting.titleChanged(old, new) && used.add(new.lowercase())) titles[a.id] = new
        }
        var tags = current.hashtags
        if (avoid.hashtags.isNotEmpty() && !Relisting.hashtagsChanged(avoid.hashtags, tags)) {
            val seen = (avoid.hashtags + tags).map { it.lowercase() }.toSet()
            val fresh = edit.hashtags.filter { it.lowercase() !in seen }
            val old = avoid.hashtags.map { it.lowercase() }.toSet()
            val freshSet = fresh.map { it.lowercase() }.toSet()
            val result = (tags + fresh).toMutableList()
            while (result.size > MAX_TAGS) {
                // Drop repeated tags first, then the last of the others; never a fresh one.
                val i = result.indexOfLast { it.lowercase() in old }.takeIf { it >= 0 } ?: result.indexOfLast { it.lowercase() !in freshSet }
                if (i < 0) break else result.removeAt(i)
            }
            tags = result.take(MAX_TAGS)
        }
        return current.copy(titles = titles, hashtags = tags)
    }

    const val MAX_TAGS = 12
}
