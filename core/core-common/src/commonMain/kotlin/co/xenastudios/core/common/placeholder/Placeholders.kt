package co.xenastudios.core.common.placeholder

import co.xenastudios.core.api.PlayerRef

/**
 * A value a plugin shows in other plugins' text, such as a player's balance in a scoreboard:
 * its [name] (`balance`), what it shows, an [example] of it, and the code that works it out.
 * Declared in a [PlaceholderSet]; the placeholder plugins' hooks resolve it by name, and the
 * docs list it with how each placeholder plugin writes it ([PlaceholderSet.forms]).
 */
public class Placeholder internal constructor(
    /** Its name within the plugin: `balance`. */
    public val name: String,
    /** What it shows, for the docs. */
    public val description: String,
    /** What it might show, for the docs: `1,250.00`. */
    public val example: String,
    /** Whether it needs a player; one that doesn't works in the console and global text too. */
    public val needsPlayer: Boolean,
    private val resolver: (PlayerRef?) -> String?,
) {
    /**
     * Its value for [player] (`null` outside a player's context), or `null` when it has none,
     * which the placeholder plugin shows its own way. One that needs a player has none without.
     */
    public fun resolve(player: PlayerRef?): String? = if (needsPlayer && player == null) null else resolver(player)

    override fun toString(): String = name
}

/**
 * A plugin's placeholders, built with [placeholders] from its runtime state, since resolving one
 * reads it. The placeholder plugins' hooks call [resolve] by name.
 */
public class PlaceholderSet internal constructor(
    /** The plugin's id, which every placeholder starts with: `xmoney`. */
    public val pluginId: String,
    /** Every placeholder, in order. */
    public val placeholders: List<Placeholder>,
) {
    private val byName = placeholders.associateBy { it.name }

    /** The placeholder called [name], or `null`. */
    public operator fun get(name: String): Placeholder? = byName[name.lowercase()]

    /** The value of the placeholder called [name] for [player], or `null` if there's no such placeholder or it has no value. */
    public fun resolve(name: String, player: PlayerRef?): String? = get(name)?.resolve(player)

    /**
     * How each placeholder plugin writes [placeholder]: `%xmoney_balance%` for PlaceholderAPI,
     * `<xmoney_balance>` for MiniPlaceholders, `%xmoney:balance%` for Text Placeholder API.
     */
    public fun forms(placeholder: Placeholder): Map<String, String> = linkedMapOf(
        "PlaceholderAPI" to "%${pluginId}_${placeholder.name}%",
        "MiniPlaceholders" to "<${pluginId}_${placeholder.name}>",
        "Text Placeholder API" to "%$pluginId:${placeholder.name}%",
    )
}

/**
 * The plugin's placeholders:
 *
 * ```
 * val placeholders = placeholders("xmoney") {
 *     player("balance", "Your balance.", "1,250.00") { economy.formatted(it) }
 *     global("richest", "The richest player's name.", "Notch") { economy.top(1).firstOrNull()?.name }
 * }
 * ```
 */
public fun placeholders(pluginId: String, block: PlaceholderBuilder.() -> Unit): PlaceholderSet {
    require(Regex("[a-z][a-z0-9_-]*").matches(pluginId)) { "plugin id \"$pluginId\": use lower-case a-z, 0-9, - and _, starting with a letter" }
    return PlaceholderSet(pluginId, PlaceholderBuilder().apply(block).declared.toList())
}

/** Declares placeholders. See [placeholders]. */
public class PlaceholderBuilder internal constructor() {
    internal val declared = mutableListOf<Placeholder>()

    /** One about a player, with no value outside a player's context. */
    public fun player(name: String, description: String, example: String, resolve: (PlayerRef) -> String?) {
        add(Placeholder(name, description, example, needsPlayer = true) { resolve(it!!) })
    }

    /** One that's the same for everyone, so it works anywhere. */
    public fun global(name: String, description: String, example: String, resolve: () -> String?) {
        add(Placeholder(name, description, example, needsPlayer = false) { resolve() })
    }

    private fun add(placeholder: Placeholder) {
        require(NAME.matches(placeholder.name)) { "placeholder \"${placeholder.name}\": use lower-case a-z, 0-9 and _" }
        require(declared.none { it.name == placeholder.name }) { "placeholder ${placeholder.name} declared twice" }
        declared += placeholder
    }

    private companion object {
        val NAME = Regex("[a-z0-9][a-z0-9_]*")
    }
}

/**
 * Where the platform's adapter hooks a plugin's placeholders into the placeholder plugins that
 * are installed: PlaceholderAPI and MiniPlaceholders on Bukkit, Text Placeholder API on Fabric.
 * Called at startup and after every reload; each expansion resolves through
 * [PlaceholderSet.resolve], so it always reads the newest set.
 */
public interface PlaceholderRegistrar {
    /** Hooks [placeholders] in, in place of what was hooked before. Returns warnings to log. */
    public fun sync(placeholders: PlaceholderSet): List<String>

    /** Unhooks them, as the plugin is disabled. */
    public fun clear()
}
