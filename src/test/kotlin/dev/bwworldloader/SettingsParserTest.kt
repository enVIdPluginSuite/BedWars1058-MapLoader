package dev.bwworldloader

import dev.bwworldloader.config.ConfigKeys
import dev.bwworldloader.config.Coerced
import dev.bwworldloader.config.LoadMode
import dev.bwworldloader.config.MapRawConfig
import dev.bwworldloader.config.SaveFormat
import dev.bwworldloader.config.Selection
import dev.bwworldloader.config.SettingsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsParserTest {

    @Test
    fun `empty config yields the documented defaults without problems`() {
        val out = SettingsParser.parse(MapRawConfig(emptyMap()))
        assertTrue(out.ok, out.problems.toString())
        assertEquals(LoadMode.LAZY, out.settings.loading.mode)
        assertEquals("bedwars_worlds", out.settings.storage.path)
        assertEquals(0, out.settings.loading.warmPerGroup)
        assertTrue(out.settings.loading.listParkedArenas)
        assertEquals(300, out.settings.loading.idleUnloadSeconds)
    }

    @Test
    fun `parses a full tree`() {
        val raw = MapRawConfig(
            mapOf(
                "storage" to mapOf("path" to "/srv/maps", "format-on-save" to "zip", "index-refresh-seconds" to 5),
                "loading" to mapOf(
                    "mode" to "eager",
                    "selection" to "round-robin",
                    "warm-arenas-per-group" to 3,
                    "group-overrides" to mapOf("Solo" to 5, "Doubles" to "4"),
                    "jit-join" to mapOf("commands" to listOf("/BW", "bedwars"), "timeout-seconds" to 12),
                ),
                "debug" to true,
            ),
        )
        val s = SettingsParser.parse(raw).also { assertTrue(it.ok, it.problems.toString()) }.settings
        assertEquals("/srv/maps", s.storage.path)
        assertEquals(SaveFormat.ZIP, s.storage.formatOnSave)
        assertEquals(LoadMode.EAGER, s.loading.mode)
        assertEquals(Selection.ROUND_ROBIN, s.loading.selection)
        assertEquals(5, s.loading.warmFor("solo"))
        assertEquals(4, s.loading.warmFor("DOUBLES"))
        assertEquals(3, s.loading.warmFor("unknown"))
        assertEquals(listOf("bw", "bedwars"), s.loading.jitJoin.commands)
        assertEquals(12, s.loading.jitJoin.timeoutSeconds)
        assertTrue(s.debug)
    }

    @Test
    fun `bad values fall back to defaults and are reported`() {
        val raw = MapRawConfig(
            mapOf(
                "storage" to mapOf("path" to "  ", "index-refresh-seconds" to -3),
                "loading" to mapOf("mode" to "sometimes", "stagger-ticks" to "x"),
            ),
        )
        val out = SettingsParser.parse(raw)
        assertFalse(out.ok)
        assertEquals("bedwars_worlds", out.settings.storage.path)
        assertEquals(LoadMode.LAZY, out.settings.loading.mode)
        assertEquals(10, out.settings.loading.staggerTicks)
        assertTrue(out.problems.any { it.startsWith("storage.path") })
        assertTrue(out.problems.any { it.startsWith("loading.mode") })
        assertTrue(out.problems.any { it.startsWith("loading.stagger-ticks") })
        assertTrue(out.problems.any { it.startsWith("storage.index-refresh-seconds") })
    }

    @Test
    fun `server root is rejected as storage path`() {
        val out = SettingsParser.parse(MapRawConfig(mapOf("storage" to mapOf("path" to "."))))
        assertFalse(out.ok)
        assertEquals("bedwars_worlds", out.settings.storage.path)
    }

    @Test
    fun `every concrete key resolves and dynamic prefixes resolve`() {
        for (p in ConfigKeys.concretePaths()) assertNotNull(ConfigKeys.find(p), p)
        assertNotNull(ConfigKeys.find("loading.group-overrides.Solo"))
        assertNotNull(ConfigKeys.find("messages.jit-preparing"))
        assertEquals(null, ConfigKeys.find("loading.nope"))
        assertEquals(null, ConfigKeys.find("loading.group-overrides."))
    }

    @Test
    fun `coercion validates types and ranges`() {
        fun c(path: String, v: String) = ConfigKeys.coerce(ConfigKeys.find(path)!!, v)
        assertEquals("EAGER", (c("loading.mode", "eager") as Coerced.Ok).yaml)
        assertTrue(c("loading.mode", "never") is Coerced.Err)
        assertEquals("12", (c("loading.stagger-ticks", "12") as Coerced.Ok).yaml)
        assertTrue(c("loading.stagger-ticks", "-1") is Coerced.Err)
        assertTrue(c("loading.stagger-ticks", "abc") is Coerced.Err)
        assertEquals("true", (c("debug", "on") as Coerced.Ok).yaml)
        assertEquals("\"/srv/my maps\"", (c("storage.path", "/srv/my maps") as Coerced.Ok).yaml)
        assertEquals("[\"bw\", \"bedwars\"]", (c("loading.jit-join.commands", "bw, bedwars") as Coerced.Ok).yaml)
        assertEquals("\"a\\\"b\"", ConfigKeys.quote("a\"b"))
    }

    @Test
    fun `an old idle-unload-minutes value is converted to seconds`() {
        val old = SettingsParser.parse(MapRawConfig(mapOf("loading" to mapOf("idle-unload-minutes" to 2)))).settings
        assertEquals(120, old.loading.idleUnloadSeconds)
        val both = SettingsParser.parse(MapRawConfig(mapOf("loading" to mapOf("idle-unload-minutes" to 2, "idle-unload-seconds" to 45)))).settings
        assertEquals(45, both.loading.idleUnloadSeconds)
    }
}
