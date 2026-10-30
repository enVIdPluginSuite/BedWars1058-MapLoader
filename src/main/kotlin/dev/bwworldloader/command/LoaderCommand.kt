package dev.bwworldloader.command

import dev.bwworldloader.WorldLoaderPlugin
import dev.bwworldloader.config.ConfigKeys
import dev.bwworldloader.config.ConfigService
import dev.bwworldloader.config.ValueType
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale

/** `/bwloader` - status, reload, in-game config editing and map migration. */
class LoaderCommand(private val plugin: WorldLoaderPlugin) : CommandExecutor, TabCompleter {

    private val subcommands = listOf("help", "status", "reload", "config", "scan", "activate", "migrate")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("bwworldloader.admin")) {
            sender.sendMessage(ChatColor.RED.toString() + "You do not have permission to do that.")
            return true
        }
        val rt = plugin.services
        if (rt == null) {
            sender.sendMessage(err("BedWarsWorldLoader is not active (see the console for the reason)."))
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
            else -> {
                sender.sendMessage(err("Unknown subcommand '${args[0]}'."))
                help(sender, label)
            }
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------

    private fun help(s: CommandSender, label: String) {
        s.sendMessage(title("BedWarsWorldLoader"))
        for ((cmd, desc) in listOf(
            "status" to "Show mode, map library and arena pool",
            "reload" to "Reload config.yml and apply it immediately",
            "config list" to "List every setting and its value",
            "config get <key>" to "Show one setting",
            "config set <key> <value>" to "Change a setting (saved + applied now)",
            "scan" to "Re-read the map library folder",
            "activate <arena>" to "Load a parked arena right now",
            "migrate [confirm]" to "Move arena maps from the server root into the library",
        )) {
            s.sendMessage("${ChatColor.GOLD} ▪ ${ChatColor.GRAY}/$label ${ChatColor.YELLOW}$cmd ${ChatColor.GRAY}- $desc")
        }
    }

    private fun status(s: CommandSender) {
        val rt = plugin.services ?: return
        val cfg = rt.settingsService.settings
        val kinds = rt.store.templates().groupingBy { it.kind }.eachCount()
        s.sendMessage(title("BedWarsWorldLoader ${plugin.description.version}"))
        val modeNote = if (!rt.pool.lazyActive() && cfg.loading.mode == dev.bwworldloader.config.LoadMode.LAZY)
            " ${ChatColor.RED}(inactive: BungeeCord mode loads on demand by itself)" else ""
        s.sendMessage(line("Mode", "${cfg.loading.mode}$modeNote${ChatColor.GRAY}, ${cfg.loading.warmPerGroup} warm per group, selection ${cfg.loading.selection}"))
        s.sendMessage(line("Library", "${rt.store.storageDir} ${ChatColor.GRAY}(${rt.store.templates().size} maps: " +
            "${kinds[dev.bwworldloader.storage.WorldStore.Kind.FOLDER] ?: 0} folders, ${kinds[dev.bwworldloader.storage.WorldStore.Kind.ZIP] ?: 0} zips)"))
        s.sendMessage(line("Arenas", "${rt.bw.arenaUtil.arenas.size} active, ${rt.pool.loadingCount()} loading, " +
            "${rt.pool.coldCount()} parked, ${rt.bw.queue.size} in BedWars' enable queue"))
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
        if (arena == null) return s.sendMessage(err("Usage: /bwloader activate <arena>"))
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

    // ---------------------------------------------------------------------------------------------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("bwworldloader.admin")) return emptyList()
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
            args[0].equals("activate", true) && args.size == 2 -> rt.pool.coldNames()
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
