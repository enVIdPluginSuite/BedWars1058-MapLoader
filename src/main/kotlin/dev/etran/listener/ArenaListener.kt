package dev.etran.listener

import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent
import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent
import com.andrei1058.bedwars.api.events.server.ArenaDisableEvent
import com.andrei1058.bedwars.api.events.server.ArenaEnableEvent
import com.andrei1058.bedwars.api.events.server.ArenaRestartEvent
import dev.etran.BwContext
import dev.etran.Takeover
import dev.etran.config.LoaderSettings
import dev.etran.pool.ArenaPool
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.ChatColor
import java.util.Locale

/** Feeds BedWars1058 events into the pool and implements the just-in-time `/bw join` hook. */
class ArenaListener(
    private val bw: BwContext,
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

    /**
     * Someone is joining a parked arena that is only listed (it has no world yet): stop that join, load the
     * world and join the player when it is ready. BedWars has not touched the arena's world at this point.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoinListedArena(e: PlayerJoinArenaEvent) {
        if (!pool.isListed(e.arena)) return
        e.isCancelled = true
        pool.joinListed(e.player, e.arena)
    }

    /** A player filled an arena up; that arena stops being joinable. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoinArena(e: PlayerJoinArenaEvent) {
        if (!e.isCancelled) pool.requestRefill()
    }

    /**
     * Left click on a parked arena in the arena selector: handle it here and close the menu, so BedWars does not
     * additionally answer with its "you cannot join this arena" message (its own click handler checks the open menu).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onSelectorClick(e: InventoryClickEvent) {
        if (!pool.listingActive() || pool.listedCount() == 0) return
        val player = e.whoClicked as? Player ?: return
        if (e.click != ClickType.LEFT) return
        val holder = player.openInventory.topInventory.holder ?: return
        if (holder.javaClass.simpleName != "ArenaSelectorHolder") return
        val item = e.currentItem ?: return
        if (item.type == Material.AIR) return
        val data = try {
            val vs = bw.api.versionSupport
            if (vs.isCustomBedWarsItem(item)) vs.getCustomData(item) else null
        } catch (t: Throwable) {
            null
        } ?: return
        if (!data.contains("arena=")) return
        val arena = pool.listedByName(data.split("=")[1]) ?: return
        e.isCancelled = true
        player.closeInventory()
        pool.joinListed(player, arena)
    }

    /** BedWars disables (and would crash on) arenas without a world: take the parked ones out of its lists first. */
    @EventHandler
    fun onPluginDisable(e: PluginDisableEvent) {
        if (e.plugin === bw.bwPlugin) pool.hideAllListed()
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
        if (parts.size == 3 && parts[1].equals("disableArena", ignoreCase = true) &&
            parts[0].substringAfter(':').lowercase(Locale.ROOT) in jit.commands &&
            e.player.hasPermission("bw.disable") && pool.disableListed(parts[2])
        ) {
            e.isCancelled = true
            e.player.sendMessage(ChatColor.GREEN.toString() + "Arena " + parts[2] + " disabled (it was not loaded).")
            return
        }
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
