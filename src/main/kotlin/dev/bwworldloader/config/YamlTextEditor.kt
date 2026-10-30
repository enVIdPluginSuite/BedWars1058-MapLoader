package dev.bwworldloader.config

/**
 * Minimal, comment-preserving editor for the plugin's own `config.yml`.
 *
 * Bukkit's `saveConfig()` throws away every comment on servers older than 1.18.1, which would wipe the
 * documentation out of the file the first time an admin runs `/bwloader config set`. This editor
 * changes only the one line that needs to change.
 *
 * It understands the subset of YAML used by our config: nested block mappings with 2-space style
 * indentation, scalar values, flow lists (`[a, b]`) and block lists (`- a`). Anything it cannot place
 * confidently makes [set] return `null`, and the caller falls back to Bukkit's serializer.
 */
object YamlTextEditor {

    private val KEY_LINE = Regex("""^(\s*)("[^"]*"|'[^']*'|[^\s:#\-][^:#]*?)\s*:(\s.*|)$""")

    private class KeyLine(val index: Int, val indent: Int, val key: String, val path: String, val rest: String)

    /**
     * Sets [path] (dotted) to [renderedValue] (already valid YAML, e.g. `"text"`, `12`, `[a, b]`).
     * Returns the new document text, or `null` when the edit cannot be applied safely.
     */
    fun set(text: String, path: String, renderedValue: String): String? {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split("\r\n", "\n").toMutableList()
        // A trailing newline produces a final empty element; keep it out of the way and add it back.
        val hadTrailingNewline = lines.isNotEmpty() && lines.last().isEmpty()
        if (hadTrailingNewline) lines.removeAt(lines.size - 1)

        val keys = scan(lines) ?: return null
        val existing = keys.firstOrNull { it.path == path }

        if (existing != null) {
            if (!replaceExisting(lines, existing, renderedValue, keys)) return null
        } else {
            if (!insertNew(lines, keys, path, renderedValue)) return null
        }
        return lines.joinToString(eol) + if (hadTrailingNewline) eol else ""
    }

    // ------------------------------------------------------------------------------------------

    private fun scan(lines: List<String>): List<KeyLine>? {
        val result = ArrayList<KeyLine>()
        val stack = ArrayList<KeyLine>()
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ") || trimmed == "-") continue
            if (line.contains('\t') && line.substring(0, line.length - line.trimStart().length).contains('\t')) return null
            val m = KEY_LINE.matchEntire(line) ?: continue
            val indent = m.groupValues[1].length
            val key = unquote(m.groupValues[2].trim())
            while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeAt(stack.size - 1)
            val path = (stack.map { it.key } + key).joinToString(".")
            val kl = KeyLine(i, indent, key, path, m.groupValues[3])
            stack += kl
            result += kl
        }
        return result
    }

    private fun replaceExisting(
        lines: MutableList<String>,
        kl: KeyLine,
        rendered: String,
        keys: List<KeyLine>,
    ): Boolean {
        val rest = kl.rest
        val valuePart = rest.trimStart()
        val prefix = lines[kl.index].substring(0, lines[kl.index].length - rest.length) // indent + key + ':'

        if (valuePart.isEmpty() || valuePart.startsWith("#")) {
            // `key:` with nothing inline -> either an (empty) null, a nested mapping or a block list.
            val hasChildMapping = keys.any { it.path.startsWith(kl.path + ".") }
            if (hasChildMapping) return false
            var end = kl.index + 1
            // swallow an existing block list that belongs to this key
            while (end < lines.size) {
                val t = lines[end].trim()
                val ind = lines[end].length - lines[end].trimStart().length
                if ((t.startsWith("- ") || t == "-") && ind >= kl.indent) end++ else break
            }
            val comment = if (valuePart.startsWith("#")) " $valuePart" else ""
            lines[kl.index] = "$prefix $rendered$comment"
            for (k in (end - 1) downTo (kl.index + 1)) lines.removeAt(k)
            return true
        }

        val comment = trailingComment(valuePart)
        lines[kl.index] = "$prefix $rendered${if (comment.isEmpty()) "" else " $comment"}"
        return true
    }

    private fun insertNew(lines: MutableList<String>, keys: List<KeyLine>, path: String, rendered: String): Boolean {
        val parts = path.split('.')
        // longest existing ancestor
        var ancestor: KeyLine? = null
        var depth = 0
        for (n in parts.size - 1 downTo 1) {
            val candidate = parts.take(n).joinToString(".")
            val found = keys.firstOrNull { it.path == candidate }
            if (found != null) {
                ancestor = found
                depth = n
                break
            }
        }

        val remaining = parts.drop(depth)
        if (ancestor == null) {
            // Append a brand-new top-level branch at the end of the file.
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += renderBranch(remaining, 0, rendered)
            return true
        }

        // The ancestor must be a mapping we can add children to.
        val restTrim = ancestor.rest.trim()
        val inlineValue = restTrim.isNotEmpty() && !restTrim.startsWith("#")
        if (inlineValue) {
            val comment = trailingComment(restTrim)
            val valueOnly = restTrim.removeSuffix(comment).trim()
            if (valueOnly == "{}" || valueOnly == "~" || valueOnly == "null") {
                val prefix = lines[ancestor.index].substring(0, lines[ancestor.index].length - ancestor.rest.length)
                lines[ancestor.index] = if (comment.isEmpty()) prefix else "$prefix $comment"
            } else {
                return false
            }
        }

        // find the end of the ancestor's block
        var end = ancestor.index + 1
        var lastContent = ancestor.index
        while (end < lines.size) {
            val l = lines[end]
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) {
                end++
                continue
            }
            val ind = l.length - l.trimStart().length
            if (ind <= ancestor.indent) break
            lastContent = end
            end++
        }

        val childIndent = keys.filter { it.path.startsWith(ancestor.path + ".") }
            .minOfOrNull { it.indent } ?: (ancestor.indent + 2)
        lines.addAll(lastContent + 1, renderBranch(remaining, childIndent, rendered))
        return true
    }

    private fun renderBranch(parts: List<String>, baseIndent: Int, rendered: String): List<String> {
        val out = ArrayList<String>()
        for ((i, p) in parts.withIndex()) {
            val indent = " ".repeat(baseIndent + i * 2)
            val keyText = if (needsQuoting(p)) ConfigKeys.quote(p) else p
            out += if (i == parts.size - 1) "$indent$keyText: $rendered" else "$indent$keyText:"
        }
        return out
    }

    private fun needsQuoting(key: String): Boolean =
        key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } ||
            key.lowercase() in setOf("true", "false", "yes", "no", "on", "off", "null", "~")

    private fun unquote(s: String): String =
        if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))))
            s.substring(1, s.length - 1) else s

    /** Returns the trailing `# comment` of an inline value ("" if none), honoring quotes and flow lists. */
    private fun trailingComment(valuePart: String): String {
        var inDouble = false
        var inSingle = false
        var i = 0
        while (i < valuePart.length) {
            val c = valuePart[i]
            when {
                inDouble -> if (c == '\\') i++ else if (c == '"') inDouble = false
                inSingle -> if (c == '\'') inSingle = false
                c == '"' -> inDouble = true
                c == '\'' -> inSingle = true
                c == '#' && (i == 0 || valuePart[i - 1] == ' ') -> return valuePart.substring(i).trimEnd()
            }
            i++
        }
        return ""
    }
}
