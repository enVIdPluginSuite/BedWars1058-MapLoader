package dev.etran

import com.andrei1058.bedwars.api.BedWars
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.server.ServerType
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.LinkedList

/**
 * Everything this plugin needs from BedWars1058, in one place.
 *
 * Besides wrapping the API it also owns the one slightly delicate trick of this plugin: BedWars1058 keeps its
 * "arena enable queue" in a public static list. Removing a *parked* arena from that list directly (instead of
 * calling `removeFromEnableQueue`) avoids BedWars immediately calling `onEnable` for the next arena, which would
 * otherwise recurse once per parked arena.
 */
class BwContext(val plugin: Plugin, val api: BedWars, val bwPlugin: Plugin) {

    val arenaUtil: BedWars.ArenaUtil get() = api.arenaUtil

    /** BedWars1058's live enable queue. The head is the arena whose world is currently being loaded. */
    val queue: LinkedList<IArena> get() = api.arenaUtil.enableQueue

    /** True when [queue] hands out the real list, so entries can be detached without side effects. */
    val liveQueue: Boolean = api.arenaUtil.enableQueue === api.arenaUtil.enableQueue

    /** BedWars' list of active arenas (what its GUIs and join commands read). */
    val arenas: LinkedList<IArena> get() = api.arenaUtil.arenas

    /** True when [arenas] hands out the real list, so parked arenas can be listed in it. */
    val liveArenas: Boolean = api.arenaUtil.arenas === api.arenaUtil.arenas

    val serverType: ServerType get() = api.serverType

    val autoscale: Boolean get() = api.isAutoScale

    val arenasDir: File get() = File(bwPlugin.dataFolder, "Arenas")

    /** Stock BedWars1058 keeps pristine map zips here. */
    val stockCacheDir: File get() = File(bwPlugin.dataFolder, "Cache")

    /**
     * Removes [a] from the enable queue *without* triggering `onEnable` for the next arena.
     * Returns false when that is not possible on this BedWars1058 build (the caller then falls back to [dequeueAndAdvance]).
     */
    fun detach(a: IArena): Boolean {
        if (!liveQueue) return false
        return queue.remove(a)
    }

    /** BedWars1058's own removal: also starts loading the next queued arena. */
    fun dequeueAndAdvance(a: IArena) {
        api.arenaUtil.removeFromEnableQueue(a)
    }

    fun isShuttingDown(): Boolean = !plugin.isEnabled || !bwPlugin.isEnabled

    /** Runs [block] on the main thread (inline during shutdown, when the scheduler no longer accepts tasks). */
    fun sync(block: () -> Unit) {
        if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable { block() }) else block()
    }

    fun syncLater(ticks: Long, block: () -> Unit) {
        if (plugin.isEnabled) Bukkit.getScheduler().runTaskLater(plugin, Runnable { block() }, ticks) else block()
    }
}
