package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.text.Markup
import kotlin.time.Duration

/**
 * A setting or a section of the schema: a [ConfigKey] or a [ConfigSection].
 */
public sealed interface ConfigNode {
    /** Its key in its section: `limit`. */
    public val name: String

    /** Its full path, keys joined with `.`: `homes.limit`. */
    public val path: String

    /** The comment written above it, a line each. */
    public val comment: List<String>
}

/**
 * Settings and sections, in the order they're declared, which is the order the file has
 * them. [ConfigSchema] is the root; [ConfigSection] a section inside it.
 */
public abstract class ConfigGroup internal constructor() {
    private val declared = mutableListOf<ConfigNode>()

    /** What's declared in this group, in order. */
    public val children: List<ConfigNode> get() = declared

    /** This group's path: `""` for the root. */
    public abstract val groupPath: String

    internal fun <N : ConfigNode> register(node: N): N {
        require(NAME.matches(node.name)) { "config key \"${node.name}\" in ${groupPath.ifEmpty { "the root" }}: use letters, digits, - and _" }
        require(declared.none { it.name == node.name }) { "config key ${node.path} declared twice" }
        require(groupPath.isNotEmpty() || node.name != VERSION_KEY) { "$VERSION_KEY is the config's own" }
        declared += node
        return node
    }

    internal fun childPath(name: String): String = if (groupPath.isEmpty()) name else "$groupPath.$name"

    /**
     * A setting of any [type]. A value that fails [check] (which says why, as
     * `must be at least 1`) is reported and the default used. [formerDefaults] are the
     * defaults it used to have: see [ConfigKey.formerDefaults].
     */
    protected fun <T : Any> key(
        name: String,
        type: ConfigType<T>,
        default: T,
        vararg comment: String,
        formerDefaults: List<T> = emptyList(),
        check: ((T) -> String?)? = null,
    ): ConfigKey<T> = register(ConfigKey(childPath(name), name, type, default, comment.lines(), formerDefaults, check, null, null))

    /** Text. */
    protected fun string(name: String, default: String, vararg comment: String, formerDefaults: List<String> = emptyList()): ConfigKey<String> =
        key(name, ConfigType.STRING, default, *comment, formerDefaults = formerDefaults)

    /** A message in our markup. [formerDefaults] are old default messages, as written. */
    protected fun markup(name: String, default: String, vararg comment: String, formerDefaults: List<String> = emptyList()): ConfigKey<Markup> =
        key(name, ConfigType.MARKUP, Markup.parse(default), *comment, formerDefaults = formerDefaults.map { Markup.parse(it) })

    /** A whole number from [min] to [max]. */
    protected fun int(name: String, default: Int, vararg comment: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): ConfigKey<Int> =
        ranged(name, ConfigType.INT, default, comment, min.toDouble(), max.toDouble(), Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()) { it.toDouble() }

    /** A whole number from [min] to [max]. */
    protected fun long(name: String, default: Long, vararg comment: String, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): ConfigKey<Long> =
        ranged(name, ConfigType.LONG, default, comment, min.toDouble(), max.toDouble(), Long.MIN_VALUE.toDouble(), Long.MAX_VALUE.toDouble()) { it.toDouble() }

    /** A number from [min] to [max]. */
    protected fun double(
        name: String,
        default: Double,
        vararg comment: String,
        min: Double = Double.NEGATIVE_INFINITY,
        max: Double = Double.POSITIVE_INFINITY,
    ): ConfigKey<Double> = ranged(name, ConfigType.DOUBLE, default, comment, min, max, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) { it }

    /** `true` or `false`. */
    protected fun boolean(name: String, default: Boolean, vararg comment: String): ConfigKey<Boolean> =
        key(name, ConfigType.BOOLEAN, default, *comment)

    /** A length of time (`30s`, `1h30m`), from [min] to [max]. */
    protected fun duration(
        name: String,
        default: Duration,
        vararg comment: String,
        min: Duration = Duration.ZERO,
        max: Duration = Duration.INFINITE,
        formerDefaults: List<Duration> = emptyList(),
    ): ConfigKey<Duration> {
        val check: (Duration) -> String? = {
            when {
                it < min -> "must be at least ${co.xenastudios.core.common.time.Durations.write(min)}"
                it > max -> "must be at most ${co.xenastudios.core.common.time.Durations.write(max)}"
                else -> null
            }
        }
        return key(name, ConfigType.DURATION, default, *comment, formerDefaults = formerDefaults, check = check)
    }

    /** One of an enum's [values] (`Storage.entries`), written in lower case with `-` for `_`. */
    protected fun <E : Enum<E>> enum(name: String, default: E, values: List<E>, vararg comment: String): ConfigKey<E> =
        key(name, ConfigType.enumOf(values), default, *comment)

    /** A list of [element]s. */
    protected fun <T : Any> list(name: String, element: ConfigType<T>, default: List<T>, vararg comment: String): ConfigKey<List<T>> =
        key(name, ConfigType.listOf(element), default, *comment)

    /** A list of text. */
    protected fun stringList(name: String, default: List<String>, vararg comment: String): ConfigKey<List<String>> =
        list(name, ConfigType.STRING, default, *comment)

    private fun <T : Any> ranged(
        name: String,
        type: ConfigType<T>,
        default: T,
        comment: Array<out String>,
        min: Double,
        max: Double,
        lowest: Double,
        highest: Double,
        toDouble: (T) -> Double,
    ): ConfigKey<T> {
        require(min <= max) { "${childPath(name)}: min $min is above max $max" }
        val hasMin = min > lowest
        val hasMax = max < highest
        val bounds = when {
            hasMin && hasMax -> "must be from ${ConfigType.number(min)} to ${ConfigType.number(max)}"
            hasMin -> "must be at least ${ConfigType.number(min)}"
            else -> "must be at most ${ConfigType.number(max)}"
        }
        val check: ((T) -> String?)? = if (!hasMin && !hasMax) null else { value -> toDouble(value).let { if (it < min || it > max) bounds else null } }
        return register(
            ConfigKey(childPath(name), name, type, default, comment.lines(), emptyList(), check, min.takeIf { hasMin }, max.takeIf { hasMax }),
        )
    }

    internal companion object {
        const val VERSION_KEY: String = "config-version"
        private val NAME = Regex("[A-Za-z0-9_-]+")

        // Each comment line on its own, so "a\nb" is two lines.
        fun Array<out String>.lines(): List<String> = flatMap { it.split('\n') }
    }
}

/**
 * One setting: its [type], its [default], and the [comment] written above it. Read it from a
 * loaded [Config] with `config[key]`.
 */
public class ConfigKey<T : Any> internal constructor(
    override val path: String,
    override val name: String,
    public val type: ConfigType<T>,
    public val default: T,
    override val comment: List<String>,
    /**
     * The defaults this setting used to have. When an old file is carried forward (its config
     * version is older), a setting still on one of these was never changed by its owner, so
     * it's given the current default: owners who never touched a message get the new one.
     * Only while carrying a file forward, so a changed default needs a new config version
     * (with an empty migration if nothing moved) to reach existing files.
     */
    public val formerDefaults: List<T>,
    private val check: ((T) -> String?)?,
    /** The lowest number it takes, for the docs, or `null` for no limit. */
    public val min: Double?,
    /** The highest number it takes, for the docs, or `null` for no limit. */
    public val max: Double?,
) : ConfigNode {
    init {
        require(check?.invoke(default) == null) { "$path: the default ${type.encode(default)} ${check?.invoke(default)}" }
    }

    /** Reads [node] and checks it, or says why it can't be used. */
    internal fun decode(node: YamlNode, context: ConfigContext): Outcome<T, String> = when (val read = type.decode(node, context)) {
        is Outcome.Err -> read
        is Outcome.Ok -> check?.invoke(read.value)?.let { Outcome.Err(it) } ?: read
    }

    /** Whether [value] passes this setting's check. */
    internal fun accepts(value: T): String? = check?.invoke(value)

    override fun toString(): String = path
}

/**
 * A section of settings inside a [ConfigSchema] or another section. Declare it as a class
 * and create it as a property of its parent, so it takes its place in declaration order:
 *
 * ```
 * object HomesConfig : ConfigSchema(version = 2) {
 *     val homes: Homes = Homes(this)
 *     class Homes(parent: ConfigGroup) : ConfigSection(parent, "homes", "Where players can go back to.") {
 *         val limit: ConfigKey<Int> = int("limit", 3, "How many homes a player can set.", min = 0)
 *     }
 * }
 * ```
 */
public abstract class ConfigSection(parent: ConfigGroup, final override val name: String, vararg comment: String) : ConfigGroup(), ConfigNode {
    final override val path: String = parent.childPath(name)
    final override val comment: List<String> = comment.lines()
    final override val groupPath: String get() = path

    init {
        parent.register(this)
    }

    override fun toString(): String = path
}

/**
 * A plugin's config, declared once as an `object`: its settings, with their defaults, types
 * and comments, in the order the file has them. From this the core writes the default file,
 * reads and checks the live one, carries old ones forward, and dumps it for the docs
 * ([describe]).
 *
 * [version] is the config version; a file with an older one is carried forward through the
 * [migrate] steps from its version up, each run once per carry. [header] is written at the
 * top of the file.
 */
public abstract class ConfigSchema(public val version: Int = 1, vararg header: String) : ConfigGroup() {
    init {
        require(version >= 1) { "the config version starts at 1" }
    }

    /** The lines written at the top of the file. */
    public val header: List<String> = header.lines()

    final override val groupPath: String get() = ""

    private val steps = mutableMapOf<Int, MigrationScope.() -> Unit>()

    /**
     * How to carry a file from config version [from] to the next. Each version below
     * [version] needs one. A step should be safe to run on a file it has already changed
     * (the [MigrationScope] operations are: a move whose source is gone does nothing), since
     * a file whose carry couldn't be saved is carried again on the next start.
     */
    protected fun migrate(from: Int, step: MigrationScope.() -> Unit) {
        require(from in 1 until version) { "a migration from $from: the config version is $version" }
        require(from !in steps) { "two migrations from $from" }
        steps[from] = step
    }

    internal fun migration(from: Int): MigrationScope.() -> Unit =
        steps[from] ?: throw IllegalStateException("${this::class.simpleName}: no migration from config version $from")

    /** Every setting, depth first, in file order. */
    public val keys: List<ConfigKey<*>> by lazy { flatten(this) }

    /** The defaults, as a [Config]. */
    public val defaults: Config by lazy { Config(this, keys.associateWith { it.default }) }

    /** The schema as data, for the docs' generated config page. */
    public fun describe(): ConfigDescription = ConfigDescription.of(this)

    private fun flatten(group: ConfigGroup): List<ConfigKey<*>> = group.children.flatMap {
        when (it) {
            is ConfigKey<*> -> listOf(it)
            is ConfigSection -> flatten(it)
        }
    }
}

/**
 * The settings of a file being carried forward, as YAML, with the edits a migration makes.
 * Paths are keys joined with `.`. Each operation can run again on a file it has already
 * changed and leave it as it is.
 */
public class MigrationScope internal constructor(root: YamlNode.Mapping) {
    /** The file's settings as they stand. */
    public var root: YamlNode.Mapping = root
        private set

    /** The value at [path], or `null` if there's none. */
    public operator fun get(path: String): YamlNode? = Paths.get(root, path)

    /** Whether the file has [path]. */
    public operator fun contains(path: String): Boolean = get(path) != null

    /** Sets [path] to [value], making sections on the way. */
    public operator fun set(path: String, value: YamlNode) {
        root = Paths.set(root, path, value)
    }

    /** Sets [path] to the text [value]. */
    public operator fun set(path: String, value: String) {
        set(path, YamlNode.Scalar(value))
    }

    /** Removes [path], if it's there. */
    public fun remove(path: String) {
        root = Paths.remove(root, path)
    }

    /**
     * Moves [from] to [to]. Nothing happens if [from] isn't there (already moved), or if [to]
     * already is (the owner set it): then [from] is just removed.
     */
    public fun move(from: String, to: String) {
        val value = get(from) ?: return
        remove(from)
        if (to !in this) set(to, value)
    }

    /** Changes the value at [path] with [change], if it's there. */
    public fun change(path: String, change: (YamlNode) -> YamlNode) {
        get(path)?.let { set(path, change(it)) }
    }
}

internal object Paths {
    fun get(root: YamlNode.Mapping, path: String): YamlNode? {
        var node: YamlNode = root
        for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return null
        return node
    }

    fun set(root: YamlNode.Mapping, path: String, value: YamlNode): YamlNode.Mapping {
        val keys = path.split('.')
        fun go(mapping: YamlNode.Mapping, i: Int): YamlNode.Mapping {
            if (i == keys.lastIndex) return mapping.with(keys[i], value)
            val child = mapping[keys[i]] as? YamlNode.Mapping ?: YamlNode.Mapping(emptyList())
            return mapping.with(keys[i], go(child, i + 1))
        }
        return go(root, 0)
    }

    fun remove(root: YamlNode.Mapping, path: String): YamlNode.Mapping {
        val keys = path.split('.')
        fun go(mapping: YamlNode.Mapping, i: Int): YamlNode.Mapping {
            if (i == keys.lastIndex) return mapping.without(keys[i])
            val child = mapping[keys[i]] as? YamlNode.Mapping ?: return mapping
            return mapping.with(keys[i], go(child, i + 1))
        }
        return go(root, 0)
    }
}
