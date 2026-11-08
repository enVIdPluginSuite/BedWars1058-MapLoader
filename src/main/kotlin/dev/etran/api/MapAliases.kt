package dev.etran.api

import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import java.util.logging.Level

/**
 * Public hook for add-ons that create arenas which reuse another arena's map (for example BedWars1058-MultiGroup,
 * which plays one map in several arena groups).
 *
 * A resolver maps an arena name to the name of the map it is played on, or returns null for "not mine". When an
 * arena resolves to another map, this plugin loads that map from the library for it (just-in-time, parked and listed
 * like any other arena), so the map is stored only once.
 *
 * Only *loading* follows aliases. Saving (`/bw setupArena`), deleting, cloning and restoring always use the exact
 * arena name, so an alias can never overwrite or remove the map it points to.
 *
 * Called by name/reflection with JDK types only (both plugins shade and relocate Kotlin):
 * `MapAliases.register(Plugin, java.util.function.Function<String, String>)` and `MapAliases.unregister(Plugin)`.
 * Resolvers are called from the main thread and from IO threads, so they must be thread-safe and fast.
 */
object MapAliases {

    private val resolvers = ConcurrentHashMap<String, Function<String, String?>>()

    @JvmStatic
    fun register(owner: Plugin, resolver: Function<String, String?>) {
        registerResolver(owner.name, resolver)
        owner.logger.info("Registered map aliases with BedWars1058-MapLoader.")
    }

    @JvmStatic
    fun unregister(owner: Plugin) {
        unregisterResolver(owner.name)
    }

    internal fun registerResolver(id: String, resolver: Function<String, String?>) {
        resolvers[id] = resolver
    }

    internal fun unregisterResolver(id: String) {
        resolvers.remove(id)
    }

    /** The map [arenaName] is played on when a resolver claims it, otherwise null. */
    @JvmStatic
    fun resolve(arenaName: String): String? {
        if (resolvers.isEmpty()) return null
        for ((owner, r) in resolvers) {
            val target = try {
                r.apply(arenaName)
            } catch (t: Throwable) {
                java.util.logging.Logger.getLogger("BedWars1058-MapLoader")
                    .log(Level.WARNING, "Map alias resolver of $owner failed for '$arenaName'", t)
                null
            }
            if (!target.isNullOrBlank() && !target.equals(arenaName, ignoreCase = true)) return target
        }
        return null
    }

    @JvmStatic
    fun isActive(): Boolean = resolvers.isNotEmpty()
}
