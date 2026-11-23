package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelPoolTest {
    private var now = 1_000_000L
    private val pool = ModelPool { now }
    private val all = listOf("flash", "lite", "old")

    @Test
    fun `a model that said no is not asked again until its cooldown ends`() {
        pool.block("flash", 20_000, ModelPool.Why.MINUTE)
        assertEquals(listOf("lite", "old"), pool.ready(all))
        now += 19_999
        assertEquals(listOf("lite", "old"), pool.ready(all))
        now += 2
        assertEquals(all, pool.ready(all))
    }

    @Test
    fun `a missing model is dropped for good and the wait is the shortest cooldown`() {
        pool.missing("old")
        pool.block("flash", 30_000, ModelPool.Why.BUSY)
        pool.block("lite", 12_000, ModelPool.Why.MINUTE)
        assertEquals(listOf("flash", "lite"), pool.usable(all))
        assertEquals(emptyList(), pool.ready(all))
        assertEquals(12_000, pool.wait(all))
        assertEquals(ModelPool.Why.MINUTE, pool.why(all))
    }

    @Test
    fun `the day's quota is only the reason when every model is out of it`() {
        pool.daily("flash"); pool.daily("lite"); pool.daily("old")
        assertEquals(ModelPool.Why.DAILY, pool.why(all))
        assertTrue(pool.wait(all) > 60_000)
        pool.block("old", 5_000, ModelPool.Why.BUSY)
        assertEquals(ModelPool.Why.BUSY, pool.why(all))
        assertEquals(5_000, pool.wait(all))
    }

    @Test
    fun `reasoning is dropped once per model and the reset is the next California midnight`() {
        assertTrue(pool.thinks("flash"))
        pool.withoutThinking("flash")
        assertFalse(pool.thinks("flash"))
        assertTrue(pool.thinks("lite"))
        val ms = ModelPool.untilReset(now)
        assertTrue(ms in 1..(24 * 3600_000L + 3600_000L), ms.toString())
        assertEquals(-1, ModelPool().also { it.missing("x") }.wait(listOf("x")))
    }
}
