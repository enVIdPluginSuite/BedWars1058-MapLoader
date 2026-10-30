package dev.etran.pool

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import dev.etran.BwContext
import dev.etran.config.LoadMode
import dev.etran.config.LoaderSettings
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.logging.Logger

/**
 * The just-in-time layer.
 *
 * In [LoadMode.LAZY] an arena that BedWars1058 wants to enable is not loaded straight away. It is *parked*
 * (removed from BedWars' enable queue and remembered here). The pool then keeps only as many joinable arenas
 * ready per group as configured ("warm"), waking parked arenas up when joinable ones are used up, and it also
 * wakes an arena immediately when a player asks for it with `/bw join <arena>`.
 *
 * All methods must be called on the main server thread.
 */
class ArenaPool(
    private val bw: BwContext,
    private val settings: () -> LoaderSettings,
    private val log: Logger,
) {
    data class PendingJoin(
        val player: UUID,
        val arenaKey: String?,
        val groupKey: String?,
        val deadline: Long,
        /** the command the player typed (without the leading slash); it is run again once the arena is ready */
        val command: String,
    )

    data class GroupStatus(val group: String, val target: Int, val open: Int, val loading: Int, val active: Int, val cold: Int)

    private class Cold(val arena: IArena, val seq: Long)

    /** parked arenas, keyed by lower-case arena name */
    private val cold = LinkedHashMap<String, Cold>()

    /** arenas whose world load we started (or adopted) and that have not been initialised by BedWars yet */
    private val loading: MutableSet<IArena> = Collections.newSetFromMap(IdentityHashMap())

    /** arena names that must be loaded (not parked) the next time BedWars enables them */
    private val forced = HashSet<String>()

    /** arena names we created ourselves and expect to park the next time BedWars enables them */
    private val expectParking = HashSet<String>()

    private val lastActivated = HashMap<String, Long>()
    private val seqByName = HashMap<String, Long>()
    private var seqCounter = 0L
    private var rrCursor = -1L
    private val rnd = Random()
    private var refillQueued = false

    private val pending = ArrayList<PendingJoin>()

    /**
     * Set by `/bwloader sleep`: the warm pool is not topped up again until a player asks for an arena (or the
     * settings change), otherwise the arenas that were just put to sleep would be woken straight away.
     */
    @Volatile
    var refillPaused = false
        private set

    /** While paused, only groups an arena was explicitly woken for (by a player or an admin) are topped up. */
    private val demandGroups = HashSet<String>()

    fun pauseRefill() {
        refillPaused = true
        demandGroups.clear()
    }

    fun resumeRefill() {
        if (refillPaused) {
            refillPaused = false
            demandGroups.clear()
            requestRefill()
        }
    }

    /** Parked arenas currently shown in BedWars' arena list as "waiting" ("ghosts": they have no world yet). */
    private val listed: MutableSet<IArena> = Collections.newSetFromMap(IdentityHashMap())

    /** last time a loaded arena was in use, for the idle unload */
    private val lastBusy = IdentityHashMap<IArena, Long>()

    /** Updated whenever the enable queue makes progress; used by the stall watchdog. */
    @Volatile
    var lastProgress: Long = System.currentTimeMillis()
        private set

    /** Called when the head of the queue has not progressed for too long. Set by the plugin. */
    var onStall: (IArena) -> Unit = {}

    // ---------------------------------------------------------------------------------------------
    // Mode
    // ---------------------------------------------------------------------------------------------

    /**
     * LAZY only makes sense where we decide what is loaded. On BungeeCord servers BedWars1058 already creates
     * arenas on demand (auto-scale), so there we behave like EAGER.
     */
    fun lazyActive(): Boolean =
        settings().loading.mode == LoadMode.LAZY && bw.serverType != com.andrei1058.bedwars.api.server.ServerType.BUNGEE

    // ---------------------------------------------------------------------------------------------
    // Enable-queue hooks (called by the restore adapter)
    // ---------------------------------------------------------------------------------------------

    /** Registers that [name] will come back through BedWars' enable queue and should be parked. */
    fun expectParking(name: String) {
        expectParking += name.lowercase(Locale.ROOT)
    }

    /**
     * Call right after creating an arena that was announced with [expectParking]: if its constructor bailed out
     * (invalid config, missing map) the arena never reached the queue and the expectation must not linger.
     */
    fun settleExpectation(name: String) {
        val key = name.lowercase(Locale.ROOT)
        if (key in expectParking && bw.queue.none { it.arenaName.equals(name, ignoreCase = true) }) expectParking.remove(key)
    }

    /** True if the arena BedWars is enabling right now should be parked instead of loaded. */
    fun shouldPark(a: IArena): Boolean {
        val key = a.arenaName.lowercase(Locale.ROOT)
        if (!lazyActive()) {
            forced.remove(key)
            expectParking.remove(key)
            return false
        }
        // The admin (or another plugin) re-enabled an arena that is parked: they want it now.
        val wasCold = cold.remove(key)
        if (wasCold != null) {
            hideListed(wasCold.arena)
            forced.remove(key)
            expectParking.remove(key)
            return false
        }
        if (forced.remove(key)) {
            expectParking.remove(key)
            return false
        }
        return expectParking.remove(key)
    }

    fun park(a: IArena) {
        val key = a.arenaName.lowercase(Locale.ROOT)
        loading.remove(a)
        cold[key] = Cold(a, seqByName.getOrPut(key) { ++seqCounter })
        showListed(a)
        touch()
        debug("Parked arena '${a.arenaName}' (group ${a.group}); ${cold.size} parked")
        requestRefill()
    }

    fun markLoading(a: IArena) {
        loading += a
        touch()
    }

    /** The load of [a] failed (success is reported through [onArenaEnabled]). Waiting players are told. */
    fun loadingFinished(a: IArena) {
        loading.remove(a)
        touch()
        val key = a.arenaName.lowercase(Locale.ROOT)
        val msg = settings().messages.jitFailed
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val pj = iter.next()
            if (pj.arenaKey == key) {
                iter.remove()
                Bukkit.getPlayer(pj.player)?.sendMessage(message(msg, a.arenaName))
            }
        }
        requestRefill()
    }

    fun isLoading(a: IArena): Boolean = a in loading

    /** BedWars finished initialising [a] (it is now an active arena). */
    fun onArenaEnabled(a: IArena) {
        loading.remove(a)
        touch()
        if (pending.isNotEmpty()) bw.syncLater(5L) { completePending(a) }
        requestRefill()
    }

    fun touch() {
        lastProgress = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------------------------------------
    // Warm pool
    // ---------------------------------------------------------------------------------------------

    /** Schedules a refill for the next tick (several triggers in one tick collapse into one pass). */
    fun requestRefill() {
        if (refillQueued) return
        refillQueued = true
        bw.sync {
            refillQueued = false
            refill()
        }
    }

    /** Wakes up parked arenas until every group has its configured number of joinable arenas. */
    fun refill() {
        if (bw.isShuttingDown() || !lazyActive() || cold.isEmpty() || refillPaused) return
        val loadingSettings = settings().loading
        val groups = cold.values.map { it.arena.group }.distinctBy { it.lowercase(Locale.ROOT) }
        for (g in groups) {
            if (refillPaused && g.lowercase(Locale.ROOT) !in demandGroups) continue
            var need = WarmPolicy.deficit(effectiveWarm(g), openCount(g), inFlightCount(g))
            while (need > 0) {
                val pick = pickCold(g) ?: break
                activate(pick, "warm pool: group $g has ${openCount(g)} open of ${effectiveWarm(g)} wanted")
                need--
            }
        }
    }

    private fun pickCold(group: String): IArena? {
        val candidates = cold.entries
            .filter { sameGroup(it.value.arena.group, group) }
            .map { (k, c) -> ColdEntry(k, c.arena.group, lastActivated[k] ?: 0L, c.seq) }
        val pick = WarmPolicy.pick(candidates, settings().loading.selection, rnd, rrCursor) ?: return null
        rrCursor = pick.order
        return cold[pick.key]?.arena
    }

    /** Moves a parked arena back into BedWars' enable queue; our adapter will then really load it. */
    fun activate(a: IArena, reason: String = "requested") {
        log.info("Loading arena '${a.arenaName}' ($reason)")
        val key = a.arenaName.lowercase(Locale.ROOT)
        cold.remove(key)
        if (refillPaused) demandGroups += (a.group ?: "").lowercase(Locale.ROOT)
        hideListed(a) // BedWars' init() refuses an arena that is still registered, and its status must be reset
        forced += key
        lastActivated[key] = System.currentTimeMillis()
        debug("Waking up arena '${a.arenaName}'")
        bw.arenaUtil.addToEnableQueue(a)
    }

    /** Activates a parked arena by name (admin command). Returns false if it is not parked. */
    fun activateByName(name: String): Boolean {
        val c = cold[name.lowercase(Locale.ROOT)] ?: return false
        activate(c.arena, "admin command")
        return true
    }

    /** Used when switching from LAZY to EAGER: bring every parked arena online. */
    fun activateAll(): Int {
        val all = cold.values.map { it.arena }
        all.forEach { activate(it, "load all") }
        return all.size
    }

    fun isJoinable(a: IArena): Boolean {
        val st = a.status
        return (st == GameState.waiting || st == GameState.starting) && a.players.size < a.maxPlayers
    }

    fun openCount(group: String): Int =
        bw.arenaUtil.arenas.count { it !in listed && sameGroup(it.group, group) && isJoinable(it) }

    private fun inFlightArenas(): Set<IArena> {
        val set: MutableSet<IArena> = Collections.newSetFromMap(IdentityHashMap())
        set.addAll(loading)
        for (a in bw.queue) {
            if (a.arenaName.lowercase(Locale.ROOT) !in expectParking) set += a
        }
        return set
    }

    fun inFlightCount(group: String): Int = inFlightArenas().count { sameGroup(it.group, group) }

    fun coldNames(): List<String> = cold.values.map { it.arena.arenaName }.sorted()

    fun coldCount(): Int = cold.size

    fun loadingCount(): Int = loading.size

    fun status(): List<GroupStatus> {
        val groups = LinkedHashMap<String, String>() // lower -> display
        fun note(g: String) {
            groups.putIfAbsent(g.lowercase(Locale.ROOT), g)
        }
        bw.arenaUtil.arenas.forEach { if (it !in listed) note(it.group) }
        cold.values.forEach { note(it.arena.group) }
        loading.forEach { note(it.group) }
        val s = settings().loading
        return groups.values.map { g ->
            GroupStatus(
                group = g,
                target = effectiveWarm(g),
                open = openCount(g),
                loading = inFlightCount(g),
                active = bw.arenaUtil.arenas.count { it !in listed && sameGroup(it.group, g) },
                cold = cold.values.count { sameGroup(it.arena.group, g) },
            )
        }.sortedBy { it.group.lowercase(Locale.ROOT) }
    }

    // ---------------------------------------------------------------------------------------------
    // Listing parked arenas
    //
    // A parked arena is a real BedWars arena object that was never initialised (it has no world). Adding it to
    // BedWars' arena list with status "waiting" makes the selector GUI, `/bw join random|<group>` and the arena
    // list show it. Joining it fires PlayerJoinArenaEvent before BedWars touches the (missing) world; we cancel
    // that, load the world and let the player join when it is ready ([joinListed]).
    // ---------------------------------------------------------------------------------------------

    /**
     * Warm arenas are only used while parked arenas are NOT listed: when they are listed every arena is joinable
     * anyway (it loads on demand), so keeping some loaded in advance would only waste memory and block unloading.
     */
    fun effectiveWarm(group: String): Int = if (listingActive()) 0 else settings().loading.warmFor(group)

    fun listingActive(): Boolean = lazyActive() && settings().loading.listParkedArenas && bw.liveArenas

    fun isListed(a: IArena): Boolean = a in listed

    fun listedByName(name: String): IArena? = cold[name.lowercase(Locale.ROOT)]?.arena?.takeIf { it in listed }

    fun listedCount(): Int = listed.size

    private fun showListed(a: IArena) {
        if (!listingActive() || a in listed) return
        try {
            a.status = GameState.waiting
            bw.arenas.add(a)
            bw.arenaUtil.setArenaByName(a)
            listed += a
        } catch (t: Throwable) {
            log.warning("Could not list parked arena '${a.arenaName}': ${t.message}")
        }
    }

    private fun hideListed(a: IArena) {
        if (!listed.remove(a)) return
        bw.arenas.remove(a)
        bw.arenaUtil.removeArenaByName(a.arenaName)
        try {
            a.status = GameState.restarting // the state of a fresh arena; BedWars' init() switches it to waiting itself
        } catch (t: Throwable) {
            // nothing sensible to do
        }
    }

    /** Re-applies `list-parked-arenas` (after a reload / config change). */
    fun syncListing() {
        if (listingActive()) cold.values.forEach { showListed(it.arena) } else hideAllListed()
    }

    /** Removes every parked arena from BedWars' lists; they stay parked. Must run before BedWars disables its arenas. */
    fun hideAllListed() {
        listed.toList().forEach { hideListed(it) }
    }

    /** `/bw disableArena <name>` on a parked arena: forget it, like BedWars does for a loaded one. */
    fun disableListed(name: String): Boolean {
        val key = name.lowercase(Locale.ROOT)
        val c = cold[key] ?: return false
        hideListed(c.arena)
        cold.remove(key)
        requestRefill()
        return true
    }

    /**
     * A player tried to join the listed arena [a] (selector GUI, sign, `/bw join random` ...). The caller has
     * cancelled the join; wake the arena and join the player when it is ready.
     */
    fun joinListed(player: Player, a: IArena) {
        // `/bw join random` offers several arenas in a row: only the first one is taken
        if (pending.any { it.player == player.uniqueId }) return
        // A party member is pulled along by the party leader's join, so only the leader waits.
        try {
            val party = bw.api.partyUtil
            if (party.hasParty(player) && !party.isOwner(player)) return
        } catch (t: Throwable) {
            // no party support: treat as a solo player
        }
        val key = a.arenaName.lowercase(Locale.ROOT)
        val label = settings().loading.jitJoin.commands.firstOrNull() ?: "bw"
        pending += PendingJoin(player.uniqueId, key, null, System.currentTimeMillis() + settings().loading.jitJoin.timeoutSeconds * 1000L, "$label join ${a.arenaName}")
        player.sendMessage(message(settings().messages.jitPreparing, a.arenaName))
        // next tick: we are inside BedWars' addPlayer right now
        bw.sync { if (cold[key]?.arena === a) activate(a, "${player.name} joined it") }
    }

    // --- idle unload ---

    /** Why each loaded arena is (not) going to sleep; shown by `/bwloader arenas`. */
    fun sleepReport(): List<String> {
        val now = System.currentTimeMillis()
        val s = settings().loading
        val out = ArrayList<String>()
        val real = bw.arenaUtil.arenas.filter { it !in listed }.sortedBy { it.arenaName.lowercase(Locale.ROOT) }
        for (a in real) {
            val key = a.arenaName.lowercase(Locale.ROOT)
            val busy = a.status != GameState.waiting || a.players.isNotEmpty() || a.spectators.isNotEmpty() ||
                a in loading || pending.any { it.arenaKey == key }
            val idleSec = if (busy) 0L else (now - (lastBusy[a] ?: now)) / 1000L
            val verdict = when {
                !lazyActive() -> "never: loading.mode is not LAZY (or this is a BungeeCord server)"
                s.idleUnloadSeconds <= 0 -> "never: loading.idle-unload-seconds is 0"
                busy -> "in use (${a.status}, ${a.players.size} player(s))"
                openCount(a.group) <= effectiveWarm(a.group) -> "kept loaded: inside warm-arenas-per-group (${effectiveWarm(a.group)}) of group ${a.group}"
                else -> "idle ${idleSec} s, sleeps after ${s.idleUnloadSeconds} s"
            }
            out += "${a.arenaName} [${a.group}]: $verdict"
        }
        return out
    }

    private fun unloadIdle(now: Long) {
        val seconds = settings().loading.idleUnloadSeconds
        if (seconds <= 0 || !lazyActive() || bw.isShuttingDown()) return
        val limit = seconds * 1000L
        val alive: MutableSet<IArena> = Collections.newSetFromMap(IdentityHashMap())
        val idle = HashMap<String, MutableList<Pair<IArena, Long>>>()
        for (a in bw.arenaUtil.arenas.toList()) {
            if (a in listed) continue
            alive += a
            val key = a.arenaName.lowercase(Locale.ROOT)
            val busy = a.status != GameState.waiting || a.players.isNotEmpty() || a.spectators.isNotEmpty() ||
                a in loading || pending.any { it.arenaKey == key }
            if (busy) {
                lastBusy[a] = now
                continue
            }
            val since = lastBusy.getOrPut(a) { now }
            if (now - since >= limit) idle.getOrPut(a.group.lowercase(Locale.ROOT)) { ArrayList() } += a to since
        }
        lastBusy.keys.retainAll(alive)
        for ((g, list) in idle) {
            var open = openCount(g)
            for ((a, _) in list.sortedBy { it.second }) {
                if (open <= effectiveWarm(g)) break
                log.info("Putting idle arena '${a.arenaName}' to sleep (unused for ${seconds}+ s).")
                lastBusy.remove(a)
                open--
                try {
                    a.restart() // BedWars -> our adapter: world is unloaded and deleted, the arena is parked (and listed) again
                } catch (t: Throwable) {
                    log.warning("Could not unload idle arena '${a.arenaName}': ${t.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Just-in-time join
    // ---------------------------------------------------------------------------------------------

    /**
     * A player ran `/bw join [target]` ([command] without the slash). If [target] is an arena - or a group
     * (also `A+B` and `random`) with nothing joinable - that is only parked, wake it up and run the command
     * again for the player once it is ready, so BedWars1058 applies its normal permission/party checks.
     * Returns true when the request was taken over (the caller should cancel the original command).
     */
    fun requestJoin(player: Player, target: String, command: String): Boolean {
        val jit = settings().loading.jitJoin
        val key = target.lowercase(Locale.ROOT)
        val deadline = System.currentTimeMillis() + jit.timeoutSeconds * 1000L

        val parked = cold[key]
        if (parked != null) {
            pending.removeAll { it.player == player.uniqueId }
            pending += PendingJoin(player.uniqueId, key, null, deadline, command)
            player.sendMessage(message(settings().messages.jitPreparing, parked.arena.arenaName))
            activate(parked.arena, "${player.name} ran /bw join ${parked.arena.arenaName}")
            return true
        }

        // a group (or group list / "random") that has parked arenas but nothing joinable right now
        if (coldFor(target).isNotEmpty() && openCountFor(target) == 0) {
            pending.removeAll { it.player == player.uniqueId }
            pending += PendingJoin(player.uniqueId, null, key, deadline, command)
            player.sendMessage(message(settings().messages.jitPreparing, target))
            if (inFlightCountFor(target) == 0) pickColdFor(target)?.let { activate(it, "${player.name} ran /bw join $target") }
            return true
        }
        return false
    }

    fun dropPending(player: UUID) {
        pending.removeAll { it.player == player }
    }

    fun pendingCount(): Int = pending.size

    private fun completePending(a: IArena) {
        val key = a.arenaName.lowercase(Locale.ROOT)
        val msgs = settings().messages
        var capacity = a.maxPlayers - a.players.size
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val pj = iter.next()
            val matches = if (pj.arenaKey != null) pj.arenaKey == key else pj.groupKey != null && groupMatches(a.group, pj.groupKey)
            if (!matches) continue
            val p = Bukkit.getPlayer(pj.player)
            if (p == null || !p.isOnline || bw.arenaUtil.getArenaByPlayer(p) != null) {
                iter.remove()
                continue
            }
            if (!isJoinable(a) || capacity <= 0) continue
            iter.remove()
            capacity--
            p.sendMessage(message(msgs.jitJoining, a.arenaName))
            p.performCommand(pj.command)
        }
        // people still waiting for a group: wake one more arena for them
        val waitingGroups = pending.mapNotNull { it.groupKey }.distinct()
        for (g in waitingGroups) {
            if (inFlightCountFor(g) == 0 && openCountFor(g) == 0) pickColdFor(g)?.let { activate(it, "players are waiting for group $g") }
        }
    }

    // --- group matching: "Solo", "Solo+Doubles" and "random" ---

    private fun groupMatches(arenaGroup: String?, target: String): Boolean {
        if (target.equals("random", ignoreCase = true)) return true
        return target.split('+').any { sameGroup(arenaGroup, it) }
    }

    private fun coldFor(target: String): List<Cold> = cold.values.filter { groupMatches(it.arena.group, target) }

    private fun openCountFor(target: String): Int =
        bw.arenaUtil.arenas.count { it !in listed && groupMatches(it.group, target) && isJoinable(it) }

    private fun inFlightCountFor(target: String): Int = inFlightArenas().count { groupMatches(it.group, target) }

    private fun pickColdFor(target: String): IArena? {
        val candidates = coldFor(target).map { c ->
            val k = c.arena.arenaName.lowercase(Locale.ROOT)
            ColdEntry(k, c.arena.group, lastActivated[k] ?: 0L, c.seq)
        }
        val pick = WarmPolicy.pick(candidates, settings().loading.selection, rnd, rrCursor) ?: return null
        rrCursor = pick.order
        return cold[pick.key]?.arena
    }

    /** Periodic maintenance: expire waiting players, run the stall watchdog and top the pool up. */
    fun tick() {
        val now = System.currentTimeMillis()
        val msgs = settings().messages
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val pj = iter.next()
            if (pj.deadline < now) {
                iter.remove()
                Bukkit.getPlayer(pj.player)?.sendMessage(message(msgs.jitTimeout, ""))
            }
        }

        val stall = settings().loading.stallTimeoutSeconds
        val head: IArena? = bw.queue.peekFirst()
        if (head == null) {
            touch()
        } else if (stall > 0 && now - lastProgress > stall * 1000L) {
            log.severe("Arena '${head.arenaName}' has not finished loading after ${stall}s; skipping it so the queue can continue.")
            loading.remove(head)
            touch()
            onStall(head)
        }
        unloadIdle(now)
        refill()
    }

    // ---------------------------------------------------------------------------------------------

    private fun sameGroup(a: String?, b: String?): Boolean =
        (a ?: "").equals(b ?: "", ignoreCase = true)

    private fun message(template: String, arena: String): String =
        ChatColor.translateAlternateColorCodes('&', template.replace("{arena}", arena))

    private fun debug(msg: String) {
        if (settings().debug) log.info("[debug] $msg")
    }
}
