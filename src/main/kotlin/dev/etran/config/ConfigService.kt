package dev.etran.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Owns `config.yml`: loading, validation, hot reload and in-game editing.
 *
 * The in-memory [settings] snapshot is replaced atomically, so any thread can read it without locking.
 */
class ConfigService(private val plugin: JavaPlugin) {

    private val file = File(plugin.dataFolder, "config.yml")

    @Volatile
    var settings: LoaderSettings = LoaderSettings.DEFAULT
        private set

    sealed class ReloadResult {
        data class Applied(val settings: LoaderSettings, val warnings: List<String>) : ReloadResult()
        data class Failed(val message: String) : ReloadResult()
    }

    sealed class SetResult {
        data class Changed(val path: String, val oldValue: String, val newValue: String, val settings: LoaderSettings) : SetResult()
        data class Rejected(val message: String) : SetResult()
    }

    /** First load at startup. Warnings are returned for logging; values that are invalid use defaults. */
    fun load(): List<String> {
        if (!file.exists()) plugin.saveDefaultConfig()
        val text = readText()
        val parsed = parseText(text)
        return when (parsed) {
            is Parsed.Ok -> {
                settings = parsed.outcome.settings
                parsed.outcome.problems
            }
            is Parsed.Broken -> {
                settings = LoaderSettings.DEFAULT
                listOf("config.yml is not valid YAML (${parsed.message}); using built-in defaults.")
            }
        }
    }

    /** Re-reads config.yml from disk. On a broken file the previous settings stay active. */
    fun reload(): ReloadResult {
        if (!file.exists()) plugin.saveDefaultConfig()
        return when (val parsed = parseText(readText())) {
            is Parsed.Broken -> ReloadResult.Failed("config.yml is not valid YAML: ${parsed.message}")
            is Parsed.Ok -> {
                settings = parsed.outcome.settings
                ReloadResult.Applied(parsed.outcome.settings, parsed.outcome.problems)
            }
        }
    }

    /** Current value of [path] as shown to the admin, or `null` when the key is unknown. */
    fun display(path: String): String? {
        if (ConfigKeys.find(path) == null) return null
        val parsed = parseText(readText()) as? Parsed.Ok ?: return null
        val v = parsed.raw.get(path) ?: return currentFromSettings(path)
        return if (v is List<*>) v.joinToString(", ") else v.toString()
    }

    /**
     * Validates and applies `path = rawValue`: the file is edited in place (comments survive), then the new
     * settings take effect immediately. Nothing is written when validation fails.
     */
    fun set(path: String, rawValue: String): SetResult {
        val spec = ConfigKeys.find(path)
            ?: return SetResult.Rejected("Unknown key '$path'. Use /bwloader config list.")
        val coerced = when (val c = ConfigKeys.coerce(spec, rawValue)) {
            is Coerced.Err -> return SetResult.Rejected(c.message)
            is Coerced.Ok -> c
        }

        val oldText = readText()
        val old = display(path) ?: "(default)"

        val edited = YamlTextEditor.set(oldText, path, coerced.yaml)
        val newText = edited ?: fallbackSerialize(oldText, path, coerced.value)
            ?: return SetResult.Rejected("Could not edit config.yml (see console).")

        // Validate the *whole* resulting document strictly before touching the disk.
        val parsed = parseText(newText)
        if (parsed is Parsed.Broken) return SetResult.Rejected("That change would produce invalid YAML: ${parsed.message}")
        parsed as Parsed.Ok
        // Only complain about problems this edit introduced; an unrelated typo elsewhere must not block it.
        val before = (parseText(oldText) as? Parsed.Ok)?.outcome?.problems.orEmpty().toSet()
        val introduced = parsed.outcome.problems.filter { it !in before }
        if (introduced.isNotEmpty()) {
            return SetResult.Rejected(introduced.first())
        }

        writeAtomically(newText)
        settings = parsed.outcome.settings
        return SetResult.Changed(path, old, display(path) ?: coerced.value.toString(), parsed.outcome.settings)
    }

    // ------------------------------------------------------------------------------------------

    private sealed class Parsed {
        class Ok(val raw: RawConfig, val outcome: ParseOutcome) : Parsed()
        class Broken(val message: String) : Parsed()
    }

    private fun parseText(text: String): Parsed {
        val yaml = YamlConfiguration()
        return try {
            yaml.loadFromString(text)
            val raw = BukkitRawConfig(yaml)
            Parsed.Ok(raw, SettingsParser.parse(raw))
        } catch (ex: Exception) {
            Parsed.Broken(ex.message?.lineSequence()?.firstOrNull() ?: ex.javaClass.simpleName)
        }
    }

    private fun readText(): String =
        if (file.exists()) String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) else ""

    private fun writeAtomically(text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "config.yml.tmp")
        Files.write(tmp.toPath(), text.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Last resort: let Bukkit serialize the file (this drops comments on servers < 1.18.1). */
    private fun fallbackSerialize(oldText: String, path: String, value: Any): String? = try {
        val yaml = YamlConfiguration()
        yaml.loadFromString(oldText)
        yaml.set(path, value)
        plugin.logger.warning("Could not edit config.yml in place; rewrote it (comments may have been lost).")
        yaml.saveToString()
    } catch (ex: Exception) {
        plugin.logger.warning("Failed to update config.yml: ${ex.message}")
        null
    }

    private fun currentFromSettings(path: String): String? {
        val s = settings
        return when (path) {
            "storage.path" -> s.storage.path
            "storage.create-if-missing" -> s.storage.createIfMissing.toString()
            "storage.format-on-save" -> s.storage.formatOnSave.name
            "storage.import-from-root" -> s.storage.importFromRoot.toString()
            "storage.index-refresh-seconds" -> s.storage.indexRefreshSeconds.toString()
            "storage.backups-to-keep" -> s.storage.backupsToKeep.toString()
            "loading.mode" -> s.loading.mode.name
            "loading.warm-arenas-per-group" -> s.loading.warmPerGroup.toString()
            "loading.selection" -> s.loading.selection.name
            "loading.stagger-ticks" -> s.loading.staggerTicks.toString()
            "loading.register-per-tick" -> s.loading.registerPerTick.toString()
            "loading.io-threads" -> s.loading.ioThreads.toString()
            "loading.list-parked-arenas" -> s.loading.listParkedArenas.toString()
            "loading.idle-unload-seconds" -> s.loading.idleUnloadSeconds.toString()
            "loading.stall-timeout-seconds" -> s.loading.stallTimeoutSeconds.toString()
            "loading.jit-join.enabled" -> s.loading.jitJoin.enabled.toString()
            "loading.jit-join.commands" -> s.loading.jitJoin.commands.joinToString(", ")
            "loading.jit-join.timeout-seconds" -> s.loading.jitJoin.timeoutSeconds.toString()
            "world.keep-spawn-in-memory" -> s.world.keepSpawnInMemory.toString()
            "world.delete-level-dat" -> s.world.deleteLevelDat.toString()
            "world.void-generator" -> s.world.voidGenerator.toString()
            "debug" -> s.debug.toString()
            else -> null
        }
    }
}
