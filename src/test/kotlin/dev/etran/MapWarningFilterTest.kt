package dev.etran

import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapWarningFilterTest {

    private fun rec(msg: String) = LogRecord(Level.WARNING, msg)

    @Test
    fun `extracts the map name`() {
        assertEquals("Aquarium", MapWarningFilter.extractMapName("There isn't any map called Aquarium"))
        assertEquals("sky_fall", MapWarningFilter.extractMapName("[BedWars1058] There isn't any map called sky_fall."))
        assertEquals("Frost", MapWarningFilter.extractMapName("§cThere isn't any map called §eFrost"))
        assertNull(MapWarningFilter.extractMapName("Loaded 12 arenas"))
        assertNull(MapWarningFilter.extractMapName("There isn't any map called "))
    }

    @Test
    fun `hides warnings for maps in the library only`() {
        val f = MapWarningFilter({ it == "Aquarium" }, null)
        assertFalse(f.isLoggable(rec("There isn't any map called Aquarium")))
        assertTrue(f.isLoggable(rec("There isn't any map called Ghost")))
        assertTrue(f.isLoggable(rec("Something unrelated about Aquarium")))
    }

    @Test
    fun `keeps the previous filter in the chain`() {
        val deny = Filter { false }
        val f = MapWarningFilter({ false }, deny)
        assertFalse(f.isLoggable(rec("anything")))
        assertTrue(MapWarningFilter({ false }, null).isLoggable(rec("anything")))
    }
}
