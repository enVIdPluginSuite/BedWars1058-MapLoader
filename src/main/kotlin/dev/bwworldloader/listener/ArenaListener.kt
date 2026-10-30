package dev.bwworldloader.listener

import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent
import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent
import com.andrei1058.bedwars.api.events.server.ArenaDisableEvent
import com.andrei1058.bedwars.api.events.server.ArenaEnableEvent
import com.andrei1058.bedwars.api.events.server.ArenaRestartEvent
import dev.bwworldloader.Takeover
import dev.bwworldloader.config.LoaderSettings
import dev.bwworldloader.pool.ArenaPool
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Locale

/** Feeds BedWars1058 events into the pool and implements the just-in-time `/bw join` hook. */
class ArenaListener(
    private val pool: ArenaPool,
    private val takeover: Takeover,
    private val settings: () -> LoaderSettings,
) : Listener {

    @EventHandler
    fun onArenaEnabled(e: ArenaEnableEvent) {
        pool.onArenaEnabled(e.arena)
        takeover.onArenaEnabled(e.arena)
    }

    /** A game started or ended: the number of joinable arenas changed, so the pool may need to top up. */
    @EventHandler
    fun onStateChange(e: GameStateChangeEvent) {
        pool.requestRefill()
    }

    /** A player filled an arena up; that arena stops being joinable. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerJoinArena(e: PlayerJoinArenaEvent) {
        pool.requestRefill()
    }

    @EventHandler
    fun onArenaDisabled(e: ArenaDisableEvent) {
        pool.requestRefill()
    }

    @EventHandler
    fun onArenaRestart(e: ArenaRestartEvent) {
        pool.requestRefill()
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        pool.dropPending(e.player.uniqueId)
    }

    /**
     * `/bw join <arena>` for an arena that is only parked: wake it and join the player when it is ready.
     * Runs at HIGHEST so the join command never sees the (not yet existing) arena and answers "not found".
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(e: PlayerCommandPreprocessEvent) {
        val jit = settings().loading.jitJoin
        if (!jit.enabled || !pool.lazyActive()) return
        val msg = e.message
        if (msg.length < 7 || msg[0] != '/') return

        val parts = msg.substring(1).trim().split(Regex("\\s+"))
        if (parts.size < 3) return
        // "/bedwars1058:bw join x" -> "bw"
        val label = parts[0].substringAfter(':').lowercase(Locale.ROOT)
        if (label !in jit.commands) return
        if (!parts[1].equals("join", ignoreCase = true)) return

        val player = e.player
        if (pool.requestJoin(player, parts[2], parts.joinToString(" "))) {
            e.isCancelled = true
        }
    }
}
