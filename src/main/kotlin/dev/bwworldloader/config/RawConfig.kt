package dev.bwworldloader.config

import org.bukkit.configuration.ConfigurationSection

/**
 * Tiny read-only view over a parsed YAML tree.
 *
 * [LoaderSettings] only talks to this interface, which keeps the settings parser free of Bukkit
 * classes and therefore unit-testable without a server.
 */
interface RawConfig {
    /** Returns the raw value at a dotted path, or `null` when absent. */
    fun get(path: String): Any?

    /** Returns the names of the direct children of the section at [path] (empty if not a section). */
    fun childKeys(path: String): List<String>
}

/** [RawConfig] backed by nested maps (used by tests). */
class MapRawConfig(private val root: Map<String, Any?>) : RawConfig {
    override fun get(path: String): Any? {
        var cur: Any? = root
        for (part in path.split('.')) {
            cur = (cur as? Map<*, *>)?.get(part) ?: return null
        }
        return cur
    }

    override fun childKeys(path: String): List<String> {
        val node = if (path.isEmpty()) root else get(path)
        return (node as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList()
    }
}

/** [RawConfig] backed by a Bukkit [ConfigurationSection]. */
class BukkitRawConfig(private val section: ConfigurationSection) : RawConfig {
    override fun get(path: String): Any? = section.get(path)

    override fun childKeys(path: String): List<String> {
        val child = if (path.isEmpty()) section else section.getConfigurationSection(path)
        return child?.getKeys(false)?.toList() ?: emptyList()
    }
}
