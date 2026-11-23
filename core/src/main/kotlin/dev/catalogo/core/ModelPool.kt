package dev.catalogo.core

import java.time.Instant
import java.time.ZoneId

/**
 * What the app has learned about the Gemini models during this session, so a model that just said «no» is not asked
 * again until it can say «yes»: one request per model per cooldown instead of walking the whole list every time.
 */
class ModelPool(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Why { BUSY, MINUTE, DAILY }

    private class Block(val until: Long, val why: Why)

    private val blocks = HashMap<String, Block>()
    private val gone = HashSet<String>()
    private val plain = HashSet<String>()

    /** Models that still exist (a 404 removes one for the session). */
    fun usable(all: List<String>): List<String> = all.filter { it !in gone }

    /** Usable models that are not cooling down, in the given order. */
    fun ready(all: List<String>): List<String> = usable(all).filter { (blocks[it]?.until ?: 0L) <= clock() }

    fun block(model: String, ms: Long, why: Why) {
        blocks[model] = Block(clock() + ms, why)
    }

    /** Out of the day's free quota: until it renews (midnight in California, where Google counts the day). */
    fun daily(model: String) = block(model, untilReset(clock()), Why.DAILY)

    fun missing(model: String) {
        gone += model
    }

    /** Milliseconds until the first usable model is ready (0 if one is now, -1 if none exists). */
    fun wait(all: List<String>): Long {
        val u = usable(all)
        if (u.isEmpty()) return -1
        return u.minOf { ((blocks[it]?.until ?: 0L) - clock()).coerceAtLeast(0L) }
    }

    /** Why the usable models are all cooling down: the day's quota only if every one is, the minute's if any is, else load. */
    fun why(all: List<String>): Why {
        val w = usable(all).mapNotNull { blocks[it]?.why }
        return when {
            w.isNotEmpty() && w.all { it == Why.DAILY } -> Why.DAILY
            Why.MINUTE in w -> Why.MINUTE
            else -> Why.BUSY
        }
    }

    /** A model that refuses the reasoning setting is asked without it from then on (no second request each time). */
    fun withoutThinking(model: String) {
        plain += model
    }

    fun thinks(model: String): Boolean = model !in plain

    companion object {
        private val CALIFORNIA = ZoneId.of("America/Los_Angeles")

        fun untilReset(now: Long): Long {
            val day = Instant.ofEpochMilli(now).atZone(CALIFORNIA).toLocalDate().plusDays(1)
            return day.atStartOfDay(CALIFORNIA).toInstant().toEpochMilli() - now
        }
    }
}
