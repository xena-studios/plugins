package co.xenastudios.core.common.command

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.permission.Permissible
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.text.Component

/**
 * Who ran a command: a player, the console, a command block. The platform's adapter wraps its
 * own sender in one.
 */
public interface CommandSource : Permissible {
    /** The player's name, or the console's (`CONSOLE`). */
    public val name: String

    /** The player who ran it, or `null` for the console and anything else that isn't a player. */
    public val player: PlayerRef?

    /** Sends [message] to it. Safe from any thread. */
    public fun reply(message: Component)
}

/** What a command does, given what was typed. */
public fun interface CommandHandler {
    /** Runs the command. [CommandContext.fail] stops it with a message; anything else it throws is logged. */
    public fun run(context: CommandContext)
}

/**
 * One literal of a command tree, such as `xhomes` or its `set`: what it's called and also
 * answers to, who may use it, the [arguments] it takes after its name, what it does
 * ([executable]), and the subcommands under it ([children]). Built with [commands]; the
 * dispatcher, the platform's registration and the docs all read the same nodes.
 */
public class CommandNode internal constructor(
    /** The words from the root to here: `["xhomes", "set"]`. */
    public val path: List<String>,
    /** Other names it answers to. */
    public val aliases: List<String>,
    /** What it does, for the docs and help. */
    public val description: String,
    /** The permission it needs on top of its parents', or `null` if they're enough. */
    public val permission: Permission?,
    /** Whether only a player can run it. */
    public val playerOnly: Boolean,
    /** What it takes after its name, in order. */
    public val arguments: List<Argument<*>>,
    /** The subcommands under it, in order. */
    public val children: List<CommandNode>,
    /** What runs before and after its handler: cooldowns, confirmations. */
    public val hooks: List<CommandHook>,
    /** Whether the core declared it for every plugin, as the admin subcommands. */
    public val builtIn: Boolean,
    internal val handler: CommandHandler?,
) {
    /** Its own name: the last word of [path]. */
    public val name: String get() = path.last()

    /** Whether running it does something; a node that's only a parent of subcommands doesn't. */
    public val executable: Boolean get() = handler != null

    /** How to type it, with its arguments or, without a handler, its subcommands: `/xhomes set <name>`, `/xhomes <set|delete>`. */
    public val usage: String get() = usage(children)

    /** It and every node under it, depth first. */
    public fun flatten(): List<CommandNode> = listOf(this) + children.flatMap { it.flatten() }

    /** The child answering to [label] (any case), by its name or an alias. */
    public fun child(label: String): CommandNode? {
        val lower = label.lowercase()
        return children.firstOrNull { it.name == lower || lower in it.aliases }
    }

    /** [usage], listing only the subcommands in [visible]. */
    internal fun usage(visible: List<CommandNode>): String = buildString {
        append('/').append(path.joinToString(" "))
        if (arguments.isNotEmpty()) {
            arguments.forEach { append(' ').append(it.usage) }
        } else if (visible.isNotEmpty()) {
            val names = visible.joinToString("|") { it.name }
            append(' ').append(if (executable) "[$names]" else "<$names>")
        }
    }

    override fun toString(): String = "/" + path.joinToString(" ")
}

/**
 * A plugin's commands: the roots, each with its subcommands, in order. The same tree is what
 * [CommandDispatcher] runs, what the platform registers ([CommandRegistrar]) and what the
 * manifest documents.
 */
public class CommandTree internal constructor(public val roots: List<CommandNode>) {
    /** Every node, root by root, depth first. */
    public val nodes: List<CommandNode> by lazy { roots.flatMap { it.flatten() } }

    /** The permissions the tree checks, each once, in the order they're first used. */
    public val permissions: List<Permission> by lazy {
        (nodes.mapNotNull { it.permission } + nodes.flatMap { it.hooks }.mapNotNull { it.bypass }).distinct()
    }

    /** The root answering to [label] (any case), by its name or an alias. */
    public fun root(label: String): CommandNode? {
        val lower = label.lowercase()
        return roots.firstOrNull { it.name == lower || lower in it.aliases }
    }
}
