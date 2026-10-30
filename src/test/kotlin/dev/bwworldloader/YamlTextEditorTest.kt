package dev.bwworldloader

import dev.bwworldloader.config.YamlTextEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlTextEditorTest {

    private val doc = """
        # Header comment
        storage:
          path: "bedwars_worlds"   # where maps live
          create-if-missing: true

        loading:
          mode: LAZY
          group-overrides: {}      # per-group counts
          jit-join:
            enabled: true
            commands:
              - bw
              - bedwars
            timeout-seconds: 30
        debug: false
    """.trimIndent() + "\n"

    @Test
    fun `replaces a quoted scalar and keeps the trailing comment`() {
        val out = YamlTextEditor.set(doc, "storage.path", "\"/srv/maps\"")!!
        assertTrue(out.contains("path: \"/srv/maps\" # where maps live"), out)
        assertTrue(out.contains("# Header comment"))
        assertTrue(out.contains("create-if-missing: true"))
    }

    @Test
    fun `replaces a plain scalar`() {
        val out = YamlTextEditor.set(doc, "loading.mode", "EAGER")!!
        assertTrue(out.contains("  mode: EAGER"), out)
        assertTrue(!out.contains("LAZY"))
    }

    @Test
    fun `inserts a group override under an empty flow map and keeps its comment`() {
        val out = YamlTextEditor.set(doc, "loading.group-overrides.Solo", "4")!!
        assertTrue(out.contains("  group-overrides: # per-group counts\n    Solo: 4\n"), out)
        assertTrue(!out.contains("{}"))
    }

    @Test
    fun `inserts a second override next to the first`() {
        val first = YamlTextEditor.set(doc, "loading.group-overrides.Solo", "4")!!
        val second = YamlTextEditor.set(first, "loading.group-overrides.Doubles", "3")!!
        val lines = second.lines()
        val solo = lines.indexOfFirst { it.trim() == "Solo: 4" }
        val doubles = lines.indexOfFirst { it.trim() == "Doubles: 3" }
        assertEquals(solo + 1, doubles, second)
        assertEquals(lines[solo].takeWhile { it == ' ' }, lines[doubles].takeWhile { it == ' ' })
    }

    @Test
    fun `replaces an existing override value`() {
        val first = YamlTextEditor.set(doc, "loading.group-overrides.Solo", "4")!!
        val second = YamlTextEditor.set(first, "loading.group-overrides.Solo", "7")!!
        assertTrue(second.contains("Solo: 7"))
        assertTrue(!second.contains("Solo: 4"))
    }

    @Test
    fun `replaces a block list with a flow list`() {
        val out = YamlTextEditor.set(doc, "loading.jit-join.commands", "[\"bw\", \"x\"]")!!
        assertTrue(out.contains("commands: [\"bw\", \"x\"]"), out)
        assertTrue(!out.contains("- bedwars"), out)
        assertTrue(out.contains("timeout-seconds: 30"), out)
    }

    @Test
    fun `adds a missing nested key under an existing parent`() {
        val out = YamlTextEditor.set(doc, "storage.format-on-save", "ZIP")!!
        val lines = out.lines()
        val i = lines.indexOfFirst { it.trim() == "format-on-save: ZIP" }
        assertTrue(i > 0, out)
        assertEquals("  ", lines[i].takeWhile { it == ' ' })
        // stays inside the storage block, before the blank line + loading
        assertTrue(i < lines.indexOfFirst { it.startsWith("loading:") })
    }

    @Test
    fun `adds a missing top level branch at the end`() {
        val out = YamlTextEditor.set(doc, "world.keep-spawn-in-memory", "false")!!
        assertTrue(out.trimEnd().endsWith("world:\n  keep-spawn-in-memory: false"), out)
    }

    @Test
    fun `adds a missing intermediate section`() {
        val out = YamlTextEditor.set(doc, "messages.jit-failed", "\"nope\"")!!
        assertTrue(out.trimEnd().endsWith("messages:\n  jit-failed: \"nope\""), out)
    }

    @Test
    fun `preserves CRLF line endings`() {
        val crlf = doc.replace("\n", "\r\n")
        val out = YamlTextEditor.set(crlf, "debug", "true")!!
        assertTrue(out.contains("debug: true\r\n"), out)
        assertTrue(!out.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `refuses to overwrite a nested mapping with a scalar`() {
        assertNull(YamlTextEditor.set(doc, "loading.jit-join", "5"))
    }
}
