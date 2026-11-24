package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome

/**
 * Reads, carries forward and edits config files against their [ConfigSchema]. Text in, text
 * out: the platform reads and writes the file (on the JVM, `core-jvm`'s `ConfigFile`, which
 * backs up and writes atomically), so this runs the same on every target.
 *
 * What it keeps true, as today's plugins do:
 * - A value that can't be used is reported with its path and line, and the default is used;
 *   the file keeps the owner's value.
 * - A key the schema doesn't know is reported and kept.
 * - A file only gains settings: nothing the owner wrote is changed or dropped, except by a
 *   migration or a [ConfigKey.formerDefaults] refresh while carrying it forward.
 * - A file from a newer version is read, but never written.
 */
public object Configs {

    /** The default file, as written on first start and in the docs. */
    public fun defaultText(schema: ConfigSchema): String = ConfigWriter.write(schema, null, schema.version)

    /**
     * Reads [text], the file's contents (`null` if there's no file), carrying it forward if
     * it's from an older config version. What to write back, if anything, is in
     * [ConfigLoad.write]. An error means the file isn't YAML the config can read; it should
     * be left as it is, and the last good config (or the defaults) kept.
     */
    public fun load(
        schema: ConfigSchema,
        text: String?,
        reader: YamlReader,
        context: ConfigContext = ConfigContext.DEFAULT,
    ): Outcome<ConfigLoad, ConfigError> {
        if (text == null) {
            val notice = ConfigNotice(ConfigNotice.Kind.WROTE_DEFAULTS, "Wrote the default config.")
            return Outcome.Ok(ConfigLoad(schema.defaults, emptyList(), listOf(notice), 0, defaultText(schema), backup = false))
        }
        var root = when (val parsed = parse(text, reader)) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> return parsed
        }
        val problems = mutableListOf<ConfigProblem>()
        val notices = mutableListOf<ConfigNotice>()

        val versionEntry = root.entry(ConfigGroup.VERSION_KEY)
        val written = (versionEntry?.value as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull()
        val version = when {
            versionEntry == null -> schema.version
            written == null -> {
                problems += ConfigProblem(
                    ConfigProblem.Kind.BAD_VERSION, ConfigGroup.VERSION_KEY, versionEntry.line, found(versionEntry.value),
                    "${ConfigGroup.VERSION_KEY} isn't a whole number; reading the config as the current version.",
                )
                schema.version
            }
            else -> written
        }

        if (version > schema.version) {
            problems += ConfigProblem(
                ConfigProblem.Kind.NEWER_VERSION, ConfigGroup.VERSION_KEY, versionEntry?.line ?: 0, version.toString(),
                "The config is from a newer version of the plugin (config version $version; this one reads ${schema.version}). " +
                    "Settings it doesn't know are ignored, and the file is left as it is.",
            )
            val decoded = Decoder(context).decode(schema, root)
            return Outcome.Ok(ConfigLoad(decoded.config, problems + decoded.problems, notices, 0, null, backup = false))
        }

        var carriedFrom = 0
        if (version < schema.version) {
            root = when (val carried = carry(schema, root, version)) {
                is Outcome.Ok -> carried.value
                is Outcome.Err -> return carried
            }
            val refreshed = refresh(schema, root)
            root = refreshed.first
            if (refreshed.second.isNotEmpty()) {
                val n = refreshed.second.size
                notices += ConfigNotice(
                    ConfigNotice.Kind.REFRESHED,
                    "Gave $n setting${if (n == 1) "" else "s"} you hadn't changed the new default${if (n == 1) "" else "s"}.",
                    refreshed.second,
                )
            }
            notices += ConfigNotice(ConfigNotice.Kind.CARRIED_FORWARD, "Carried the config forward from config version $version to ${schema.version}.")
            carriedFrom = version
        }

        val decoded = Decoder(context).decode(schema, root)
        problems += decoded.problems
        if (decoded.missing.size == 1) {
            notices += ConfigNotice(ConfigNotice.Kind.ADDED, "Added ${decoded.missing[0]} with its default.", decoded.missing)
        } else if (decoded.missing.isNotEmpty()) {
            notices += ConfigNotice(
                ConfigNotice.Kind.ADDED,
                "Added ${decoded.missing.size} new settings with their defaults: ${decoded.missing.joinToString(", ")}.",
                decoded.missing,
            )
        }
        val change = carriedFrom > 0 || decoded.missing.isNotEmpty() || versionEntry == null
        val write = if (change) ConfigWriter.write(schema, root, schema.version) else null
        return Outcome.Ok(ConfigLoad(decoded.config, problems, notices, carriedFrom, write, backup = carriedFrom > 0))
    }

    /**
     * Changes settings in [text], the file's contents (`null` if there's none), as an in-game
     * edit does: the owner's other values are kept, and what [change] reads is the file
     * itself, so a hand edit made since the last reload counts. Returns the new file, or
     * `null` if the change changed nothing. Reload afterwards to apply it.
     */
    public fun edit(
        schema: ConfigSchema,
        text: String?,
        reader: YamlReader,
        context: ConfigContext = ConfigContext.DEFAULT,
        change: ConfigEditor.() -> Unit,
    ): Outcome<String?, ConfigError> {
        val root = if (text == null) YamlNode.Mapping(emptyList()) else when (val parsed = parse(text, reader)) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> return parsed
        }
        val written = (root[ConfigGroup.VERSION_KEY] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull() ?: schema.version
        if (written > schema.version) {
            return Outcome.Err(ConfigError("The config is from a newer version of the plugin, so it isn't changed.", 0))
        }
        val editor = ConfigEditor(root, context)
        editor.change()
        editor.error?.let { return Outcome.Err(it) }
        if (text != null && editor.root == root) return Outcome.Ok(null)
        return Outcome.Ok(ConfigWriter.write(schema, editor.root, written))
    }

    private fun parse(text: String, reader: YamlReader): Outcome<YamlNode.Mapping, ConfigError> {
        val node = when (val read = reader.read(text)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return Outcome.Err(ConfigError("The config isn't valid YAML: ${read.error.message}", read.error.line, read.error.column))
        }
        return when (node) {
            is YamlNode.Mapping -> Outcome.Ok(node)
            // An empty file, or one with only comments: every setting is added.
            is YamlNode.Null -> Outcome.Ok(YamlNode.Mapping(emptyList(), node.line))
            else -> Outcome.Err(ConfigError("The config should be settings (key: value), not a single value or a list.", node.line))
        }
    }

    private fun carry(schema: ConfigSchema, root: YamlNode.Mapping, from: Int): Outcome<YamlNode.Mapping, ConfigError> {
        val scope = MigrationScope(root)
        for (step in maxOf(from, 1) until schema.version) {
            val migration = schema.migration(step)
            try {
                scope.migration()
            } catch (e: Exception) {
                return Outcome.Err(ConfigError("Couldn't carry the config forward from config version $step: ${e.message}", 0))
            }
        }
        scope[ConfigGroup.VERSION_KEY] = YamlNode.Scalar(schema.version.toString())
        return Outcome.Ok(scope.root)
    }

    // Settings still on a former default get the current one. Returns the paths it changed.
    private fun refresh(schema: ConfigSchema, root: YamlNode.Mapping): Pair<YamlNode.Mapping, List<String>> {
        var result = root
        val changed = mutableListOf<String>()
        for (key in schema.keys) {
            if (key.formerDefaults.isEmpty()) continue
            val current = Paths.get(result, key.path) ?: continue
            val default = encodeDefault(key)
            if (current != default && formerlyDefault(key, current)) {
                result = Paths.set(result, key.path, default)
                changed += key.path
            }
        }
        return result to changed
    }

    private fun <T : Any> encodeDefault(key: ConfigKey<T>): YamlNode = key.type.encode(key.default)

    private fun <T : Any> formerlyDefault(key: ConfigKey<T>, node: YamlNode): Boolean = key.formerDefaults.any { key.type.encode(it) == node }

    internal fun found(node: YamlNode): String? = when (node) {
        is YamlNode.Scalar -> node.content
        is YamlNode.Sequence -> "a list"
        is YamlNode.Mapping -> "a section"
        is YamlNode.Null -> null
    }
}

/**
 * What [Configs.load] made of a file: the [config] to use, the [problems] it worked around,
 * the [notices] of what it changed, the config version it was carried forward from
 * ([carriedFrom], 0 if it wasn't), and the text to [write] back, if anything changed (`null`
 * if nothing should be written). [backup] says the old file should be copied aside first:
 * if that can't be done, don't write either, so the old file is never lost.
 */
public class ConfigLoad internal constructor(
    public val config: Config,
    public val problems: List<ConfigProblem>,
    public val notices: List<ConfigNotice>,
    public val carriedFrom: Int,
    public val write: String?,
    public val backup: Boolean,
)

/** Why a config couldn't be read at all: the [message], and where ([line] and [column], 0 if unknown). */
public data class ConfigError(public val message: String, public val line: Int, public val column: Int = 0) {
    override fun toString(): String = if (line > 0) "$message (line $line)" else message
}

/**
 * The settings of a file being edited. [get] reads what the file says now; [set] and [reset]
 * change it.
 */
public class ConfigEditor internal constructor(root: YamlNode.Mapping, private val context: ConfigContext) {
    internal var root: YamlNode.Mapping = root
        private set
    internal var error: ConfigError? = null
        private set

    /** What the file says for [key] now, or `null` if it doesn't say or says something that can't be used. */
    public operator fun <T : Any> get(key: ConfigKey<T>): T? {
        val node = Paths.get(root, key.path) ?: return null
        return (key.decode(node, context) as? Outcome.Ok)?.value
    }

    /** Sets [key] to [value]. A value its checks refuse makes the edit fail, writing nothing. */
    public operator fun <T : Any> set(key: ConfigKey<T>, value: T) {
        val reason = key.accepts(value)
        if (reason != null) {
            if (error == null) error = ConfigError("${key.path} can't be ${ConfigWriter.inline(key.type.encode(value), null)}: it $reason.", 0)
            return
        }
        root = Paths.set(root, key.path, key.type.encode(value))
    }

    /** Takes [key] out of the file, so it's written with its default. */
    public fun reset(key: ConfigKey<*>) {
        root = Paths.remove(root, key.path)
    }
}

/** Reads a file's settings into a [Config], noting what it had to work around. */
internal class Decoder(private val context: ConfigContext) {
    val problems = mutableListOf<ConfigProblem>()
    private val missing = mutableListOf<String>()
    private val values = HashMap<ConfigKey<*>, Any>()

    class Result(val config: Config, val problems: List<ConfigProblem>, val missing: List<String>)

    fun decode(schema: ConfigSchema, root: YamlNode.Mapping): Result {
        group(schema, root, track = true)
        return Result(Config(schema, values), problems, missing)
    }

    // track: whether a missing setting gets added; not inside a section that's missing or broken.
    private fun group(group: ConfigGroup, raw: YamlNode.Mapping?, track: Boolean) {
        for (child in group.children) {
            val entry = raw?.entry(child.name)
            when (child) {
                is ConfigKey<*> -> {
                    if (entry == null) {
                        values[child] = child.default
                        if (track) missing += child.path
                    } else {
                        values[child] = key(child, entry)
                    }
                }
                is ConfigSection -> when (val value = entry?.value) {
                    null -> {
                        if (track) missing += child.path
                        group(child, null, track = false)
                    }
                    is YamlNode.Mapping -> group(child, value, track)
                    is YamlNode.Null -> group(child, YamlNode.Mapping(emptyList(), value.line), track)
                    else -> {
                        bad(child.path, entry.line, value, "should be a section of settings", "using the defaults for this section")
                        group(child, null, track = false)
                    }
                }
            }
        }
        if (raw == null) return
        val known = group.children.mapTo(HashSet()) { it.name }
        for (extra in raw.entries) {
            if (extra.key in known || (group is ConfigSchema && extra.key == ConfigGroup.VERSION_KEY)) continue
            val path = group.childPath(extra.key)
            problems += ConfigProblem(
                ConfigProblem.Kind.UNKNOWN_KEY, path, extra.line, null,
                "$path isn't a setting of this version (a typo?); it's left in the file and ignored.",
            )
        }
    }

    private fun <T : Any> key(key: ConfigKey<T>, entry: YamlNode.Mapping.Entry): T {
        val node = entry.value
        if (node is YamlNode.Null) {
            val empty = key.type.empty()
            if (empty != null && key.accepts(empty) == null) return empty
            problems += ConfigProblem(
                ConfigProblem.Kind.EMPTY, key.path, entry.line, null,
                "${key.path} is empty; using the default. Quote values that start with # (YAML reads them as comments).",
            )
            return key.default
        }
        val type = key.type
        if (type is ConfigType.ListType<*> && node is YamlNode.Sequence) {
            items(type, node, key.path)
        }
        return when (val read = key.decode(node, context)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> {
                bad(key.path, entry.line, node, read.error, "using the default")
                key.default
            }
        }
    }

    // A list keeps the items it can read; each one it can't is reported and skipped.
    private fun <T : Any> items(type: ConfigType.ListType<T>, node: YamlNode.Sequence, path: String) {
        node.items.forEachIndexed { i, item ->
            val read = type.element.decode(item, context)
            if (read is Outcome.Err) bad("$path[$i]", item.line, item, if (item is YamlNode.Null) "is empty" else read.error, "so it's skipped")
        }
    }

    private fun bad(path: String, line: Int, node: YamlNode, reason: String, outcome: String) {
        val found = Configs.found(node)
        val shown = when (node) {
            is YamlNode.Scalar -> " (\"$found\")"
            is YamlNode.Null -> ""
            else -> " ($found)"
        }
        problems += ConfigProblem(ConfigProblem.Kind.BAD_VALUE, path, line, found, "$path$shown $reason; $outcome.")
    }
}
