package dev.bwworldloader.config

/** How arena worlds are brought online. */
enum class LoadMode {
    /** Just-in-time: arenas are parked and a small warm pool per group is kept loaded. */
    LAZY,

    /** Every arena is loaded (like stock BedWars1058) but from the custom path and staggered. */
    EAGER,
}

/** How the pool picks which parked arena to wake up next. */
enum class Selection {
    /** The arena that has not been loaded for the longest time (best map variety). */
    LEAST_RECENT,
    RANDOM,
    ROUND_ROBIN,
}

/** How a map is stored in the storage folder after setup. */
enum class SaveFormat { FOLDER, ZIP }

data class StorageSettings(
    val path: String = "bedwars_worlds",
    val createIfMissing: Boolean = true,
    val formatOnSave: SaveFormat = SaveFormat.FOLDER,
    val importFromRoot: Boolean = true,
    val indexRefreshSeconds: Int = 30,
    /** Previous versions of a map kept in `<storage>/.backups` whenever a setup session or delete replaces it. */
    val backupsToKeep: Int = 3,
)

data class JitJoinSettings(
    val enabled: Boolean = true,
    val commands: List<String> = listOf("bw", "bedwars"),
    val timeoutSeconds: Int = 30,
)

data class LoadingSettings(
    val mode: LoadMode = LoadMode.LAZY,
    val warmPerGroup: Int = 0,
    val groupOverrides: Map<String, Int> = emptyMap(),
    val selection: Selection = Selection.LEAST_RECENT,
    val staggerTicks: Int = 10,
    val registerPerTick: Int = 10,
    val ioThreads: Int = 2,
    val stallTimeoutSeconds: Int = 60,
    /** Keep parked arenas visible (waiting) in BedWars' arena list, GUIs and /bw join random. */
    val listParkedArenas: Boolean = true,
    /** Arenas without players are unloaded again after this many seconds (0 = never). */
    val idleUnloadSeconds: Int = 300,
    val jitJoin: JitJoinSettings = JitJoinSettings(),
) {
    /** Number of joinable arenas to keep ready for [group]. */
    fun warmFor(group: String): Int =
        groupOverrides.entries.firstOrNull { it.key.equals(group, ignoreCase = true) }?.value ?: warmPerGroup
}

data class WorldSettings(
    val keepSpawnInMemory: Boolean = true,
    val deleteLevelDat: Boolean = true,
    val voidGenerator: Boolean = true,
)

data class Messages(
    val jitPreparing: String = "&eThe arena &6{arena}&e is being prepared, you will join automatically...",
    val jitJoining: String = "&aArena ready, joining!",
    val jitFailed: String = "&cCould not prepare that arena. Please try again later.",
    val jitTimeout: String = "&cThe arena took too long to prepare. Please try again.",
)

data class LoaderSettings(
    val storage: StorageSettings = StorageSettings(),
    val loading: LoadingSettings = LoadingSettings(),
    val world: WorldSettings = WorldSettings(),
    val messages: Messages = Messages(),
    val debug: Boolean = false,
) {
    companion object {
        val DEFAULT = LoaderSettings()
    }
}

/** Result of parsing a config tree: the settings (defaults substituted for bad values) and what was wrong. */
data class ParseOutcome(val settings: LoaderSettings, val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

/** Turns a [RawConfig] into a validated [LoaderSettings]. Never throws: bad values fall back to defaults. */
object SettingsParser {

    fun parse(raw: RawConfig): ParseOutcome {
        val p = Collector(raw)
        val d = LoaderSettings.DEFAULT

        val storage = StorageSettings(
            path = p.string("storage.path", d.storage.path) { v ->
                when {
                    v.isBlank() -> "must not be blank"
                    v.trim() == "." || v.trim() == "./" -> "must not be the server root itself"
                    else -> null
                }
            }.trim(),
            createIfMissing = p.bool("storage.create-if-missing", d.storage.createIfMissing),
            formatOnSave = p.enum("storage.format-on-save", d.storage.formatOnSave),
            importFromRoot = p.bool("storage.import-from-root", d.storage.importFromRoot),
            indexRefreshSeconds = p.int("storage.index-refresh-seconds", d.storage.indexRefreshSeconds, 0, 3600),
            backupsToKeep = p.int("storage.backups-to-keep", d.storage.backupsToKeep, 0, 50),
        )

        val overrides = LinkedHashMap<String, Int>()
        for (key in raw.childKeys("loading.group-overrides")) {
            val path = "loading.group-overrides.$key"
            val value = p.int(path, -1, 0, 1000)
            if (value >= 0) overrides[key] = value
        }

        val jit = JitJoinSettings(
            enabled = p.bool("loading.jit-join.enabled", d.loading.jitJoin.enabled),
            commands = p.stringList("loading.jit-join.commands", d.loading.jitJoin.commands)
                .map { it.trim().trimStart('/').lowercase() }
                .filter { it.isNotEmpty() },
            timeoutSeconds = p.int("loading.jit-join.timeout-seconds", d.loading.jitJoin.timeoutSeconds, 5, 600),
        )

        val loading = LoadingSettings(
            mode = p.enum("loading.mode", d.loading.mode),
            warmPerGroup = p.int("loading.warm-arenas-per-group", d.loading.warmPerGroup, 0, 1000),
            groupOverrides = overrides,
            selection = p.enum("loading.selection", d.loading.selection),
            staggerTicks = p.int("loading.stagger-ticks", d.loading.staggerTicks, 0, 1200),
            registerPerTick = p.int("loading.register-per-tick", d.loading.registerPerTick, 1, 1000),
            ioThreads = p.int("loading.io-threads", d.loading.ioThreads, 1, 16),
            stallTimeoutSeconds = p.int("loading.stall-timeout-seconds", d.loading.stallTimeoutSeconds, 0, 3600),
            listParkedArenas = p.bool("loading.list-parked-arenas", d.loading.listParkedArenas),
            idleUnloadSeconds = idleSeconds(raw, p, d.loading.idleUnloadSeconds),
            jitJoin = jit,
        )

        val world = WorldSettings(
            keepSpawnInMemory = p.bool("world.keep-spawn-in-memory", d.world.keepSpawnInMemory),
            deleteLevelDat = p.bool("world.delete-level-dat", d.world.deleteLevelDat),
            voidGenerator = p.bool("world.void-generator", d.world.voidGenerator),
        )

        val messages = Messages(
            jitPreparing = p.string("messages.jit-preparing", d.messages.jitPreparing),
            jitJoining = p.string("messages.jit-joining", d.messages.jitJoining),
            jitFailed = p.string("messages.jit-failed", d.messages.jitFailed),
            jitTimeout = p.string("messages.jit-timeout", d.messages.jitTimeout),
        )

        val settings = LoaderSettings(storage, loading, world, messages, p.bool("debug", d.debug))
        return ParseOutcome(settings, p.problems)
    }

    /** `loading.idle-unload-seconds`; a config written by an earlier build only has `idle-unload-minutes`, which is converted. */
    private fun idleSeconds(raw: RawConfig, p: Collector, def: Int): Int {
        if (raw.get("loading.idle-unload-seconds") == null) {
            val minutes = raw.get("loading.idle-unload-minutes")
            if (minutes != null) {
                val n = (minutes as? Number)?.toInt() ?: minutes.toString().trim().toIntOrNull()
                if (n != null && n in 0..1440) return n * 60
            }
        }
        return p.int("loading.idle-unload-seconds", def, 0, 86400)
    }

    private class Collector(private val raw: RawConfig) {
        val problems = ArrayList<String>()

        fun bool(path: String, def: Boolean): Boolean {
            val v = raw.get(path) ?: return def
            return when (v) {
                is Boolean -> v
                is String -> when (v.trim().lowercase()) {
                    "true", "yes", "on" -> true
                    "false", "no", "off" -> false
                    else -> bad(path, "expected true/false but got '$v'", def)
                }
                else -> bad(path, "expected true/false but got '$v'", def)
            }
        }

        fun int(path: String, def: Int, min: Int, max: Int): Int {
            val v = raw.get(path) ?: return def
            val n = when (v) {
                is Int -> v
                is Long -> v.toInt()
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> null
            } ?: return bad(path, "expected a whole number but got '$v'", def)
            if (n < min || n > max) return bad(path, "must be between $min and $max (got $n)", def)
            return n
        }

        fun string(path: String, def: String, check: ((String) -> String?)? = null): String {
            val v = raw.get(path) ?: return def
            val s = v.toString()
            val err = check?.invoke(s)
            return if (err != null) bad(path, err, def) else s
        }

        fun stringList(path: String, def: List<String>): List<String> {
            val v = raw.get(path) ?: return def
            return when (v) {
                is List<*> -> v.map { it.toString() }
                is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> bad(path, "expected a list but got '$v'", def)
            }
        }

        inline fun <reified E : Enum<E>> enum(path: String, def: E): E {
            val v = raw.get(path) ?: return def
            val name = v.toString().trim().uppercase().replace('-', '_')
            return enumValues<E>().firstOrNull { it.name == name }
                ?: bad(path, "expected one of ${enumValues<E>().joinToString("/")} but got '$v'", def)
        }

        fun <T> bad(path: String, message: String, fallback: T): T {
            problems += "$path: $message"
            return fallback
        }
    }
}
