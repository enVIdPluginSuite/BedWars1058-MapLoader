package dev.bwworldloader.config

/** The kind of value a config key accepts. */
sealed class ValueType {
    object Bool : ValueType()
    data class IntRange(val min: Int, val max: Int) : ValueType()
    data class Choice(val values: List<String>) : ValueType()
    object Text : ValueType()
    object TextList : ValueType()
}

/**
 * Describes one editable config key.
 *
 * @param dynamicPrefix when true, [path] is a prefix and any key below it (`loading.group-overrides.Solo`)
 *                      is accepted with the same type.
 */
data class KeySpec(
    val path: String,
    val type: ValueType,
    val description: String,
    val dynamicPrefix: Boolean = false,
)

sealed class Coerced {
    /** [yaml] is the exact text to write into config.yml; [value] is the typed value. */
    data class Ok(val yaml: String, val value: Any) : Coerced()
    data class Err(val message: String) : Coerced()
}

/** Schema of every setting that can be changed in-game with `/bwloader config set`. */
object ConfigKeys {

    private val modes = LoadMode.values().map { it.name }
    private val selections = Selection.values().map { it.name }
    private val formats = SaveFormat.values().map { it.name }

    val all: List<KeySpec> = listOf(
        KeySpec("storage.path", ValueType.Text, "Folder that holds all BedWars maps (relative to the server root, or absolute)"),
        KeySpec("storage.create-if-missing", ValueType.Bool, "Create the storage folder if it does not exist"),
        KeySpec("storage.format-on-save", ValueType.Choice(formats), "How maps are stored after /bw setupArena (FOLDER or ZIP)"),
        KeySpec("storage.import-from-root", ValueType.Bool, "Move maps found in the server root into the storage folder on first use"),
        KeySpec("storage.index-refresh-seconds", ValueType.IntRange(0, 3600), "How long the folder listing is cached (0 = always rescan)"),

        KeySpec("loading.mode", ValueType.Choice(modes), "LAZY = just-in-time warm pool, EAGER = load every arena (staggered)"),
        KeySpec("loading.warm-arenas-per-group", ValueType.IntRange(0, 1000), "Joinable arenas kept ready per arena group"),
        KeySpec("loading.group-overrides.", ValueType.IntRange(0, 1000), "Per-group warm count, e.g. loading.group-overrides.Solo", dynamicPrefix = true),
        KeySpec("loading.selection", ValueType.Choice(selections), "Which parked arena is woken up next"),
        KeySpec("loading.stagger-ticks", ValueType.IntRange(0, 1200), "Minimum ticks between two world loads"),
        KeySpec("loading.register-per-tick", ValueType.IntRange(1, 1000), "Arenas re-registered per tick at startup"),
        KeySpec("loading.io-threads", ValueType.IntRange(1, 16), "Background threads used for copying/zipping maps"),
        KeySpec("loading.stall-timeout-seconds", ValueType.IntRange(0, 3600), "Skip an arena that has not loaded after this long (0 = off)"),
        KeySpec("loading.jit-join.enabled", ValueType.Bool, "Wake a parked arena when a player runs /bw join <arena>"),
        KeySpec("loading.jit-join.commands", ValueType.TextList, "Command labels that are watched for the join command"),
        KeySpec("loading.jit-join.timeout-seconds", ValueType.IntRange(5, 600), "How long a waiting player is kept in the join queue"),

        KeySpec("world.keep-spawn-in-memory", ValueType.Bool, "Keep arena spawn chunks loaded"),
        KeySpec("world.delete-level-dat", ValueType.Bool, "Ignore level.dat from stored maps (BedWars1058 default)"),
        KeySpec("world.void-generator", ValueType.Bool, "Use a void generator for chunks missing from a map"),

        KeySpec("messages.", ValueType.Text, "Player facing messages (messages.jit-preparing ...)", dynamicPrefix = true),
        KeySpec("debug", ValueType.Bool, "Verbose logging"),
    )

    /** Finds the spec for [path] (exact match first, then dynamic prefixes). */
    fun find(path: String): KeySpec? =
        all.firstOrNull { !it.dynamicPrefix && it.path == path }
            ?: all.firstOrNull { it.dynamicPrefix && path.startsWith(it.path) && path.length > it.path.length }

    /** Every concrete (non-dynamic) path, for tab completion. */
    fun concretePaths(): List<String> = all.filter { !it.dynamicPrefix }.map { it.path }

    /** Validates [raw] text against [spec] and renders it as YAML. */
    fun coerce(spec: KeySpec, raw: String): Coerced {
        val text = raw.trim()
        return when (val t = spec.type) {
            ValueType.Bool -> when (text.lowercase()) {
                "true", "yes", "on" -> Coerced.Ok("true", true)
                "false", "no", "off" -> Coerced.Ok("false", false)
                else -> Coerced.Err("Expected true or false.")
            }
            is ValueType.IntRange -> {
                val n = text.toIntOrNull() ?: return Coerced.Err("Expected a whole number.")
                if (n < t.min || n > t.max) Coerced.Err("Must be between ${t.min} and ${t.max}.")
                else Coerced.Ok(n.toString(), n)
            }
            is ValueType.Choice -> {
                val up = text.uppercase().replace('-', '_')
                if (up in t.values) Coerced.Ok(up, up) else Coerced.Err("Expected one of: ${t.values.joinToString(", ")}.")
            }
            ValueType.Text -> {
                if (text.isEmpty()) Coerced.Err("Value must not be empty.")
                else Coerced.Ok(quote(text), text)
            }
            ValueType.TextList -> {
                val items = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isEmpty()) Coerced.Err("Provide at least one value (comma separated).")
                else Coerced.Ok(items.joinToString(", ", "[", "]") { quote(it) }, items)
            }
        }
    }

    /** Renders [s] as a double-quoted YAML scalar. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
