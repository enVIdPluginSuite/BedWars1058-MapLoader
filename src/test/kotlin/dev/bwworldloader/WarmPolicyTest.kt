package dev.bwworldloader

import dev.bwworldloader.config.Selection
import dev.bwworldloader.pool.ColdEntry
import dev.bwworldloader.pool.WarmPolicy
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarmPolicyTest {

    private fun entry(key: String, last: Long = 0, order: Long) = ColdEntry(key, "Solo", last, order)

    @Test
    fun `deficit never goes negative and counts loading arenas`() {
        assertEquals(3, WarmPolicy.deficit(target = 3, open = 0, inFlight = 0))
        assertEquals(1, WarmPolicy.deficit(target = 3, open = 1, inFlight = 1))
        assertEquals(0, WarmPolicy.deficit(target = 2, open = 2, inFlight = 0))
        assertEquals(0, WarmPolicy.deficit(target = 2, open = 5, inFlight = 1))
        assertEquals(0, WarmPolicy.deficit(target = 0, open = 0, inFlight = 0))
    }

    @Test
    fun `nothing to pick from`() {
        assertNull(WarmPolicy.pick(emptyList(), Selection.RANDOM, Random(1), -1))
    }

    @Test
    fun `least recent prefers never-used and then the oldest`() {
        val c = listOf(entry("a", last = 500, order = 1), entry("b", last = 100, order = 2), entry("c", last = 900, order = 3))
        assertEquals("b", WarmPolicy.pick(c, Selection.LEAST_RECENT, Random(1), -1)!!.key)
        val withNever = c + entry("d", last = 0, order = 4)
        assertEquals("d", WarmPolicy.pick(withNever, Selection.LEAST_RECENT, Random(1), -1)!!.key)
    }

    @Test
    fun `least recent breaks ties randomly but only among the tied`() {
        val c = listOf(entry("a", 0, 1), entry("b", 0, 2), entry("c", 5, 3))
        val rnd = Random(7)
        val picks = (1..200).map { WarmPolicy.pick(c, Selection.LEAST_RECENT, rnd, -1)!!.key }.toSet()
        assertEquals(setOf("a", "b"), picks)
    }

    @Test
    fun `round robin walks the order and wraps`() {
        val c = listOf(entry("c", order = 7), entry("a", order = 2), entry("b", order = 5))
        var cursor = -1L
        val seen = ArrayList<String>()
        repeat(5) {
            val p = WarmPolicy.pick(c, Selection.ROUND_ROBIN, Random(1), cursor)!!
            seen += p.key
            cursor = p.order
        }
        assertEquals(listOf("a", "b", "c", "a", "b"), seen)
    }

    @Test
    fun `round robin copes with a cursor that points at a removed arena`() {
        val c = listOf(entry("x", order = 10), entry("y", order = 20))
        assertEquals("y", WarmPolicy.pick(c, Selection.ROUND_ROBIN, Random(1), 12)!!.key)
        assertEquals("x", WarmPolicy.pick(c, Selection.ROUND_ROBIN, Random(1), 99)!!.key)
    }

    @Test
    fun `random eventually returns every candidate`() {
        val c = (1..5).map { entry("m$it", order = it.toLong()) }
        val rnd = Random(42)
        val seen = (1..300).map { WarmPolicy.pick(c, Selection.RANDOM, rnd, -1)!!.key }.toSet()
        assertTrue(seen.size == 5, seen.toString())
    }
}
