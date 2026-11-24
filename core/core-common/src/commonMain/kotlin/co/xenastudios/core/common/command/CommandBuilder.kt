package co.xenastudios.core.common.command

import co.xenastudios.core.common.permission.Permission
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Marks the command DSL, so a subcommand's block can't reach its parent's builder by mistake. */
@DslMarker
public annotation class CommandDsl

/**
 * A plugin's commands, declared once:
 *
 * ```
 * val tree = commands {
 *     root("xhomes", "Your homes.") {
 *         literal("set", "Sets a home where you stand.", permission = HomesPermissions.set) {
 *             playerOnly()
 *             val name = argument("name", ArgumentTypes.word, "What to call it.")
 *             cooldown(5.seconds)
 *             executes { ctx -> homes.set(ctx.player, ctx[name]) }
 *         }
 *         mount(admin)
 *     }
 * }
 * ```
 *
 * The tree is checked as it's built: names are lower-case words, no two siblings answer to the
 * same word, required arguments come before optional ones, an argument that takes the rest of
 * the line is last, and every node either runs something or has subcommands. A plugin builds
 * its tree again on reload, from the new config.
 */
public fun commands(block: CommandTreeBuilder.() -> Unit): CommandTree {
    val roots = CommandTreeBuilder().apply(block).roots
    return CommandTree(roots.toList())
}

/** Declares the root commands. See [commands]. */
@CommandDsl
public class CommandTreeBuilder internal constructor() {
    internal val roots = mutableListOf<CommandNode>()

    /** A command, such as `/xhomes`, with its [aliases] and the [permission] it and everything under it needs. */
    public fun root(
        name: String,
        description: String,
        permission: Permission? = null,
        aliases: List<String> = emptyList(),
        block: CommandBuilder.() -> Unit,
    ) {
        val node = CommandBuilder(listOf(name), description, permission, aliases, builtIn = false).apply(block).build()
        checkSiblings(roots, node, "a root command")
        roots += node
    }
}

/** Declares one command or subcommand. See [commands]. */
@CommandDsl
public class CommandBuilder internal constructor(
    private val path: List<String>,
    private val description: String,
    private val permission: Permission?,
    aliases: List<String>,
    private val builtIn: Boolean,
) {
    private val aliases = aliases.map { it.lowercase() }.distinct()
    private val arguments = mutableListOf<Argument<*>>()
    private val children = mutableListOf<CommandNode>()
    private val hooks = mutableListOf<CommandHook>()
    private var playerOnly = false
    private var handler: CommandHandler? = null

    init {
        for (word in path + this.aliases) require(WORD.matches(word)) { "${where()}: \"$word\" isn't a command word (lower-case a-z, 0-9, - and _)" }
        require(path.last() !in this.aliases) { "${where()}: an alias is the same as its name" }
    }

    /** Only players can run it; the console is told so. */
    public fun playerOnly() {
        playerOnly = true
    }

    /** A required argument, read by [type]: `<name>` in the usage. Arguments are read in the order they're declared. */
    public fun <T : Any> argument(name: String, type: ArgumentType<T>, description: String): Argument<T> = add(Argument(name, type, description, required = true))

    /** An argument that can be left out: `[name]` in the usage. Only arguments after it can be optional too. */
    public fun <T : Any> optional(name: String, type: ArgumentType<T>, description: String): Argument<T> = add(Argument(name, type, description, required = false))

    /** A subcommand: `/<this> <name>`, needing [permission] on top of this one's. */
    public fun literal(
        name: String,
        description: String,
        permission: Permission? = null,
        aliases: List<String> = emptyList(),
        block: CommandBuilder.() -> Unit,
    ) {
        child(CommandBuilder(path + name, description, permission, aliases, builtIn).apply(block).build())
    }

    /** What it does. A command with subcommands may run something too, when it's typed without one. */
    public fun executes(handler: CommandHandler) {
        check(this.handler == null) { "${where()} already has a handler" }
        this.handler = handler
    }

    /** A source uses it once per [duration]; holders of [bypass] have no cooldown. See [Cooldown]. */
    public fun cooldown(duration: Duration, bypass: Permission? = null) {
        hook(Cooldown(duration, bypass))
    }

    /** It runs only when typed twice within [window]. See [Confirmation]. */
    public fun confirm(window: Duration = 30.seconds) {
        hook(Confirmation(window))
    }

    /** Runs [hook] around the handler, after the ones declared before it. */
    public fun hook(hook: CommandHook) {
        hooks += hook
    }

    /** Puts the admin subcommands (`reload`, `debug`, ...) under this command. See [AdminCommands]. */
    public fun mount(admin: AdminCommands) {
        require(path.size == 1) { "${where()}: the admin subcommands go under the plugin's own root command" }
        admin.declare(path).forEach(::child)
    }

    internal fun child(node: CommandNode) {
        require(arguments.isEmpty()) { "${where()}: a command takes arguments or has subcommands, not both" }
        checkSiblings(children, node, "a subcommand of ${where()}")
        children += node
    }

    internal fun build(): CommandNode {
        require(handler != null || children.isNotEmpty()) { "${where()} neither runs anything nor has subcommands" }
        require(hooks.isEmpty() || handler != null) { "${where()} has hooks but nothing they run around" }
        return CommandNode(path, aliases, description, permission, playerOnly, arguments.toList(), children.toList(), hooks.toList(), builtIn, handler)
    }

    private fun <T : Any> add(argument: Argument<T>): Argument<T> {
        require(children.isEmpty()) { "${where()}: a command takes arguments or has subcommands, not both" }
        require(ARGUMENT.matches(argument.name)) { "${where()}: argument \"${argument.name}\" should be lower-case a-z, 0-9, - and _" }
        require(arguments.none { it.name == argument.name }) { "${where()}: two arguments called ${argument.name}" }
        val last = arguments.lastOrNull()
        require(last == null || !last.type.greedy) { "${where()}: ${last?.usage} takes the rest of the line, so nothing can follow it" }
        require(!argument.required || last == null || last.required) { "${where()}: required ${argument.usage} after optional ${last?.usage}" }
        arguments += argument
        return argument
    }

    private fun where(): String = "/" + path.joinToString(" ")

    internal companion object {
        val WORD = Regex("[a-z0-9][a-z0-9_-]*")
        val ARGUMENT = Regex("[a-z0-9][a-z0-9_-]*")
    }
}

private fun checkSiblings(siblings: List<CommandNode>, node: CommandNode, what: String) {
    val words = listOf(node.name) + node.aliases
    for (sibling in siblings) {
        val taken = words.firstOrNull { it == sibling.name || it in sibling.aliases }
        require(taken == null) { "$what: \"$taken\" already names $sibling" }
    }
}
