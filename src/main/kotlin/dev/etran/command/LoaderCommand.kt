package dev.etran.command

import dev.etran.WorldLoaderPlugin
import dev.etran.config.ConfigKeys
import dev.etran.config.ConfigService
import dev.etran.config.ValueType
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale

/** `/bwloader` - status, reload, in-game config editing and map migration. */
class LoaderCommand(private val plugin: WorldLoaderPlugin) : CommandExecutor, TabCompleter {

    private val subcommands = listOf("help", "status", "reload", "config", "scan", "activate", "arenas", "sleep", "disable", "enable", "migrate", "restore")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("bw1058maploader.admin")) {
            sender.sendMessage(ChatColor.RED.toString() + "You do not have permission to do that.")
            return true
        }
        val rt = plugin.services
        if (rt == null) {
            sender.sendMessage(err("BedWars1058-MapLoader is not active (see the console for the reason)."))
            return true
        }
        when (args.firstOrNull()?.lowercase(Locale.ROOT) ?: "help") {
            "help" -> help(sender, label)
            "status" -> status(sender)
            "reload" -> reload(sender)
            "config" -> config(sender, label, args.drop(1))
            "scan" -> {
                val n = rt.store.rescan()
                sender.sendMessage(ok("Scanned ${rt.store.storageDir}: $n map(s) found."))
            }
            "activate" -> activate(sender, args.getOrNull(1))
            "migrate" -> migrate(sender, args.getOrNull(1)?.equals("confirm", ignoreCase = true) == true)
            "restore" -> restore(sender, label, args.drop(1))
            "arenas" -> arenasReport(sender)
            "sleep" -> sleep(sender, label, args.drop(1))
            "disable" -> disable(sender, label, args.drop(1))
            "enable" -> enable(sender, label, args.drop(1))
            else -> {
                sender.sendMessage(err("Unknown subcommand '${args[0]}'."))
                help(sender, label)
            }
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------

    private fun help(s: CommandSender, label: String) {
        s.sendMessage(title("BedWars1058-MapLoader"))
        for ((cmd, desc) in listOf(
            "status" to "Show mode, map library and arena pool",
            "reload" to "Reload config.yml and apply it immediately",
            "config list" to "List every setting and its value",
            "config get <key>" to "Show one setting",
            "config set <key> <value>" to "Change a setting (saved + applied now)",
            "scan" to "Re-read the map library folder",
            "activate <arena>" to "Load a parked arena right now",
            "arenas" to "Show every loaded arena and when/why it will (not) go to sleep",
            "sleep [idle|all|<arena>] [force]" to "Put loaded arenas back into the library now (idle ones; force also removes players)",
            "disable <arena|all> [confirm]" to "Unload arena(s) (kicks players) so their maps can be moved",
            "enable <arena|all>" to "Bring disabled arena(s) back",
            "migrate [confirm]" to "Move arena maps from the server root into the library",
            "restore <arena|all> [move] [confirm]" to "Put maps back into the server root (stock BedWars1058 layout)",
        )) {
            s.sendMessage("${ChatColor.GOLD} ▪ ${ChatColor.GRAY}/$label ${ChatColor.YELLOW}$cmd ${ChatColor.GRAY}- $desc")
        }
    }

    private fun status(s: CommandSender) {
        val rt = plugin.services ?: return
        val cfg = rt.settingsService.settings
        val kinds = rt.store.templates().groupingBy { it.kind }.eachCount()
        s.sendMessage(title("BedWars1058-MapLoader ${plugin.description.version}"))
        val modeNote = if (!rt.pool.lazyActive() && cfg.loading.mode == dev.etran.config.LoadMode.LAZY)
            " ${ChatColor.RED}(inactive: BungeeCord mode loads on demand by itself)" else ""
        s.sendMessage(line("Mode", "${cfg.loading.mode}$modeNote${ChatColor.GRAY}, ${cfg.loading.warmPerGroup} warm per group, selection ${cfg.loading.selection}"))
        s.sendMessage(line("Library", "${rt.store.storageDir} ${ChatColor.GRAY}(${rt.store.templates().size} maps: " +
            "${kinds[dev.etran.storage.WorldStore.Kind.FOLDER] ?: 0} folders, ${kinds[dev.etran.storage.WorldStore.Kind.ZIP] ?: 0} zips)"))
        s.sendMessage(line("Arenas", "${rt.bw.arenaUtil.arenas.size} active, ${rt.pool.loadingCount()} loading, " +
            "${rt.pool.coldCount()} parked (${rt.pool.listedCount()} listed in BedWars' arena list), ${rt.bw.queue.size} in BedWars' enable queue"))
        s.sendMessage(line("Runtime copies", "${rt.runtime.ownedCount()} in the server root"))
        if (rt.pool.pendingCount() > 0) s.sendMessage(line("Waiting players", rt.pool.pendingCount().toString()))
        for (g in rt.pool.status()) {
            s.sendMessage("${ChatColor.GRAY}   ${ChatColor.AQUA}${g.group}${ChatColor.GRAY}: joinable ${g.open}/${g.target}, " +
                "active ${g.active}, loading ${g.loading}, parked ${g.cold}")
        }
    }

    private fun reload(s: CommandSender) {
        val result = plugin.reloadSettings()
        s.sendMessage(if (result.ok) ok(result.message) else err(result.message))
        result.warnings.forEach { s.sendMessage(ChatColor.YELLOW.toString() + " ! " + it) }
    }

    // ---------------------------------------------------------------------------------------------

    private fun config(s: CommandSender, label: String, args: List<String>) {
        val rt = plugin.services ?: return
        val svc = rt.settingsService
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "list" -> {
                s.sendMessage(title("Settings"))
                for (spec in ConfigKeys.all) {
                    val shown = if (spec.dynamicPrefix) "${spec.path}<name>" else spec.path
                    val value = if (spec.dynamicPrefix) "" else " ${ChatColor.WHITE}= ${svc.display(spec.path) ?: "?"}"
                    s.sendMessage("${ChatColor.GOLD} ▪ ${ChatColor.YELLOW}$shown$value")
                    s.sendMessage("${ChatColor.DARK_GRAY}     ${spec.description}")
                }
            }
            "get" -> {
                val key = args.getOrNull(1) ?: return s.sendMessage(err("Usage: /$label config get <key>"))
                val spec = ConfigKeys.find(key) ?: return s.sendMessage(err("Unknown key '$key'. Use /$label config list."))
                s.sendMessage(line(key, svc.display(key) ?: "(not set - default applies)"))
                s.sendMessage("${ChatColor.DARK_GRAY}     ${spec.description} ${typeHint(spec.type)}")
            }
            "set" -> {
                val key = args.getOrNull(1)
                val value = args.drop(2).joinToString(" ")
                if (key == null || value.isEmpty()) return s.sendMessage(err("Usage: /$label config set <key> <value>"))
                when (val r = svc.set(key, value)) {
                    is ConfigService.SetResult.Rejected -> s.sendMessage(err(r.message))
                    is ConfigService.SetResult.Changed -> {
                        s.sendMessage(ok("$key: ${r.oldValue} -> ${r.newValue}"))
                        val applied = plugin.applySettingsChange(key)
                        applied?.let { s.sendMessage(ChatColor.GRAY.toString() + " " + it) }
                    }
                }
            }
            else -> s.sendMessage(err("Usage: /$label config <list|get|set>"))
        }
    }

    private fun activate(s: CommandSender, arena: String?) {
        val rt = plugin.services ?: return
        if (arena == null) return s.sendMessage(err("Usage: /bwloader activate <arena|all>"))
        if (arena.equals("all", ignoreCase = true)) {
            val n = rt.pool.activateAll()
            return s.sendMessage(if (n > 0) ok("Loading $n parked arena(s) (staggered)...") else err("No parked arenas."))
        }
        if (rt.pool.activateByName(arena)) s.sendMessage(ok("Waking up arena '$arena'..."))
        else s.sendMessage(err("'$arena' is not parked. Parked arenas: ${rt.pool.coldNames().take(15).joinToString(", ").ifEmpty { "none" }}"))
    }

    private fun migrate(s: CommandSender, confirm: Boolean) {
        val plan = plugin.planMigration()
        s.sendMessage(title("Migrate maps into ${plugin.services?.store?.storageDir}"))
        s.sendMessage(line("To move", "${plan.movable.size} map(s) from the server root"))
        if (plan.alreadyStored.isNotEmpty()) {
            s.sendMessage(line("Skipped", "${plan.alreadyStored.size} exist in both places (left untouched): " +
                plan.alreadyStored.take(8).joinToString(", ") + if (plan.alreadyStored.size > 8) ", ..." else ""))
        }
        if (plan.inUse.isNotEmpty()) {
            s.sendMessage(line("In use", "${plan.inUse.size} currently loaded: " + plan.inUse.take(8).joinToString(", ")))
        }
        if (plan.movable.isEmpty()) {
            s.sendMessage(ok("Nothing to migrate."))
            return
        }
        s.sendMessage(ChatColor.GRAY.toString() + "   " + plan.movable.take(12).joinToString(", ") + if (plan.movable.size > 12) ", ..." else "")
        if (!confirm) {
            s.sendMessage(ChatColor.YELLOW.toString() + "Run /bwloader migrate confirm to move them. Only maps that belong to a BedWars arena are touched.")
            return
        }
        plugin.runMigration(plan, s)
    }

    private fun arenasReport(s: CommandSender) {
        val rt = plugin.services ?: return
        val cfg = rt.settingsService.settings.loading
        s.sendMessage(title("Loaded arenas"))
        s.sendMessage(line("Idle unload", "${if (cfg.idleUnloadSeconds <= 0) "off" else "${cfg.idleUnloadSeconds} s"}${ChatColor.GRAY}, mode ${cfg.mode}, " +
            "warm per group ${cfg.warmPerGroup}${if (cfg.groupOverrides.isNotEmpty()) " (overrides: ${cfg.groupOverrides})" else ""}" +
            if (rt.pool.listingActive() && (cfg.warmPerGroup > 0 || cfg.groupOverrides.isNotEmpty())) " ${ChatColor.YELLOW}(ignored while list-parked-arenas is on)" else ""))
        s.sendMessage(line("Server type", "${rt.bw.serverType}${ChatColor.GRAY}, parked arenas listed: ${rt.pool.listingActive()}"))
        if (rt.pool.refillPaused) s.sendMessage(line("Warm pool", "${ChatColor.YELLOW}paused by /bwloader sleep: only the group of an arena someone wakes is topped up, until you reload"))
        val rows = rt.pool.sleepReport()
        if (rows.isEmpty()) s.sendMessage(ChatColor.GRAY.toString() + "No arena is loaded right now.")
        rows.take(40).forEach { s.sendMessage(ChatColor.GRAY.toString() + " ▪ " + it) }
        if (rows.size > 40) s.sendMessage(ChatColor.GRAY.toString() + " ... and ${rows.size - 40} more")
    }

    private fun sleep(s: CommandSender, label: String, args: List<String>) {
        val rt = plugin.services ?: return
        if (!rt.pool.lazyActive()) {
            return s.sendMessage(err("Sleeping needs loading.mode LAZY (in EAGER mode / on BungeeCord an arena would simply load again)."))
        }
        val force = args.any { it.equals("force", true) }
        val target = args.firstOrNull { !it.equals("force", true) } ?: "idle"
        val plan = plugin.planSleep(target)
        if (plan.idle.isEmpty() && plan.busy.isEmpty()) return s.sendMessage(err("No loaded arena matches '$target'."))
        val busyPlayers = plan.busy.sumOf { it.players.size + it.spectators.size }
        if (!force && plan.idle.isEmpty() && plan.busy.isNotEmpty()) {
            return s.sendMessage(err("${plan.busy.size} arena(s) are in use ($busyPlayers player(s)). Add force to send them to sleep anyway."))
        }
        val n = plugin.runSleep(plan, force)
        s.sendMessage(ok("Sent $n arena(s) to sleep; their world copies are removed from the server root and the arenas are listed again (they wake up when someone joins)."))
        val warm = rt.settingsService.settings.loading
        if (warm.warmPerGroup > 0 || warm.groupOverrides.isNotEmpty()) {
            s.sendMessage(ChatColor.YELLOW.toString() + "Note: warm-arenas-per-group is ${warm.warmPerGroup}" +
                (if (warm.groupOverrides.isNotEmpty()) " (overrides ${warm.groupOverrides})" else "") +
                ", so that many arenas per group load again as soon as a player asks for one. Set it to 0 to keep everything asleep.")
        }
        if (!force && plan.busy.isNotEmpty()) {
            s.sendMessage(ChatColor.YELLOW.toString() + "${plan.busy.size} arena(s) in use were left alone ($busyPlayers player(s)). Add force to include them.")
        }
    }

    private fun disable(s: CommandSender, label: String, args: List<String>) {
        val target = args.firstOrNull { !it.equals("confirm", true) }
            ?: return s.sendMessage(err("Usage: /$label disable <arena|all> [confirm]"))
        val confirm = args.any { it.equals("confirm", true) }
        val plan = plugin.planDisable(target)
        s.sendMessage(title("Disable arenas"))
        s.sendMessage(line("Loaded", "${plan.loaded.size} (${plan.players} player(s) will be removed)" +
            if (plan.loaded.isNotEmpty()) ": " + plan.loaded.take(8).joinToString(", ") + if (plan.loaded.size > 8) ", ..." else "" else ""))
        s.sendMessage(line("Parked", "${plan.parked.size}"))
        if (plan.busy.isNotEmpty()) s.sendMessage(line("Busy loading", "${plan.busy.size} (try again in a moment): " + plan.busy.take(8).joinToString(", ")))
        if (plan.notFound.isNotEmpty()) s.sendMessage(line("Not found", plan.notFound.joinToString(", ")))
        if (plan.loaded.isEmpty() && plan.parked.isEmpty()) return s.sendMessage(err("Nothing to disable."))
        if (!confirm) {
            s.sendMessage(ChatColor.YELLOW.toString() + "Run /$label disable $target confirm to do it. Disabled arenas stay off until /$label enable or a restart.")
            return
        }
        val n = plugin.runDisable(plan)
        s.sendMessage(ok("Disabled $n arena(s). Their worlds are unloaded in the background; then /$label migrate or /$label restore can move the maps."))
    }

    private fun enable(s: CommandSender, label: String, args: List<String>) {
        val target = args.firstOrNull() ?: return s.sendMessage(err("Usage: /$label enable <arena|all>"))
        val enabled = plugin.enableArenas(target)
        if (enabled.isEmpty()) s.sendMessage(err("Nothing to enable (already active, or no such arena config)."))
        else s.sendMessage(ok("Enabled ${enabled.size} arena(s)" + if (enabled.size <= 8) ": " + enabled.joinToString(", ") else "."))
    }

    private fun restore(s: CommandSender, label: String, args: List<String>) {
        val target = args.firstOrNull { !it.equals("move", true) && !it.equals("confirm", true) }
            ?: return s.sendMessage(err("Usage: /$label restore <arena|all> [move] [confirm]"))
        val move = args.any { it.equals("move", true) }
        val confirm = args.any { it.equals("confirm", true) }
        val plan = plugin.planRestore(target)
        s.sendMessage(title("Restore maps into the server root"))
        s.sendMessage(line("To restore", "${plan.restorable.size} map(s)" + if (move) " (moved out of the library)" else " (copied, library keeps them)"))
        if (plan.conflicts.isNotEmpty()) s.sendMessage(line("Skipped", "${plan.conflicts.size} already exist in the server root: " + plan.conflicts.take(8).joinToString(", ")))
        if (plan.inUse.isNotEmpty()) s.sendMessage(line("In use", "${plan.inUse.size} currently loaded: " + plan.inUse.take(8).joinToString(", ")))
        if (plan.notFound.isNotEmpty()) s.sendMessage(line("Not found", plan.notFound.joinToString(", ")))
        if (plan.restorable.isEmpty()) return s.sendMessage(err("Nothing to restore."))
        s.sendMessage(ChatColor.GRAY.toString() + "   " + plan.restorable.take(12).joinToString(", ") + if (plan.restorable.size > 12) ", ..." else "")
        if (!confirm) {
            s.sendMessage(ChatColor.YELLOW.toString() + "Run /$label restore $target${if (move) " move" else ""} confirm to do it. Existing folders are never overwritten.")
            return
        }
        plugin.runRestore(plan, move, s)
    }

    // ---------------------------------------------------------------------------------------------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("bw1058maploader.admin")) return emptyList()
        val rt = plugin.services ?: return emptyList()
        val prefix = args.lastOrNull()?.lowercase(Locale.ROOT) ?: ""
        val options: List<String> = when {
            args.size <= 1 -> subcommands
            args[0].equals("config", true) -> when {
                args.size == 2 -> listOf("list", "get", "set")
                args.size == 3 && args[1].lowercase(Locale.ROOT) in setOf("get", "set") -> ConfigKeys.concretePaths()
                args.size == 4 && args[1].equals("set", true) -> valueSuggestions(args[2])
                else -> emptyList()
            }
            args[0].equals("activate", true) && args.size == 2 -> rt.pool.coldNames() + "all"
            args[0].equals("sleep", true) && args.size == 2 -> listOf("idle", "all") + plugin.knownArenaNames()
            args[0].equals("sleep", true) -> listOf("force")
            args[0].equals("disable", true) && args.size == 2 -> plugin.knownArenaNames() + "all"
            args[0].equals("disable", true) -> listOf("confirm")
            args[0].equals("enable", true) && args.size == 2 -> plugin.disabledArenaNames() + "all"
            args[0].equals("restore", true) && args.size == 2 -> rt.store.names() + "all"
            args[0].equals("restore", true) -> listOf("move", "confirm")
            args[0].equals("migrate", true) && args.size == 2 -> listOf("confirm")
            else -> emptyList()
        }
        return options.filter { it.lowercase(Locale.ROOT).startsWith(prefix) }
    }

    private fun valueSuggestions(key: String): List<String> {
        val spec = ConfigKeys.find(key) ?: return emptyList()
        return when (val t = spec.type) {
            ValueType.Bool -> listOf("true", "false")
            is ValueType.Choice -> t.values
            else -> emptyList()
        }
    }

    private fun typeHint(t: ValueType): String = when (t) {
        ValueType.Bool -> "(true/false)"
        is ValueType.IntRange -> "(whole number ${t.min}-${t.max})"
        is ValueType.Choice -> "(${t.values.joinToString("/")})"
        ValueType.Text -> "(text)"
        ValueType.TextList -> "(comma separated list)"
    }

    private fun title(t: String) = "${ChatColor.GOLD}${ChatColor.BOLD}$t"
    private fun line(k: String, v: String) = "${ChatColor.GOLD} ▪ ${ChatColor.YELLOW}$k${ChatColor.GRAY}: ${ChatColor.WHITE}$v"
    private fun ok(m: String) = "${ChatColor.GREEN}$m"
    private fun err(m: String) = "${ChatColor.RED}$m"
}
