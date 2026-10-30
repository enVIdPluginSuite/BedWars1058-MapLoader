package dev.bwworldloader

import java.util.Locale
import java.util.logging.Filter
import java.util.logging.LogRecord

/**
 * Hides BedWars1058's "There isn't any map called X" warning for maps that are in the map library.
 *
 * BedWars1058 checks the server root for every arena's world while it enables. Maps that live in the library
 * (not in the root) are reported as missing even though this plugin loads them just fine afterwards. Warnings for
 * maps that really exist nowhere are NOT hidden.
 */
class MapWarningFilter(
    private val isKnownMap: (String) -> Boolean,
    private val previous: Filter?,
) : Filter {

    override fun isLoggable(record: LogRecord): Boolean {
        val message = record.message
        if (message != null) {
            val name = extractMapName(message)
            if (name != null && isKnownMap(name)) return false
        }
        return previous?.isLoggable(record) ?: true
    }

    companion object {
        private val pattern = Regex("there isn't any map called\\s+(.+)", RegexOption.IGNORE_CASE)
        private val colorCodes = Regex("(?i)[§&][0-9a-fk-or]")

        /** The map name from a "There isn't any map called X" message, or null for any other message. */
        fun extractMapName(message: String): String? {
            val clean = message.replace(colorCodes, "")
            val raw = pattern.find(clean)?.groupValues?.get(1) ?: return null
            val name = raw.trim().trim('"', '\'', '.', '!', ':', ',').trim()
            return name.takeIf { it.isNotEmpty() && !name.lowercase(Locale.ROOT).contains(' ') }
        }
    }
}
