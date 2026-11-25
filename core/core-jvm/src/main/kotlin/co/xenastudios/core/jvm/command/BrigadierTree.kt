package co.xenastudios.core.jvm.command

import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandNode
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.permission.has
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode

/**
 * Builds Brigadier nodes from a plugin's command tree, so clients get the whole tree: its
 * literals, argument names to fill in and our suggestions, and see only the commands they may
 * use. Running one hands the line back to [CommandDispatcher], so parsing, hooks and errors are
 * the core's, the same as on every platform ([co.xenastudios.core.common.command.CommandRegistrar]).
 *
 * Every argument is a string to Brigadier, read by our own parser when it runs: a word, and the
 * last argument of a command the rest of the line, so extra words reach our dispatcher and get
 * our usage line. Vanilla clients know Brigadier's string types, so nothing needs installing,
 * where an argument type of our own would need a client mod. Every node runs, so a command
 * typed short gets our usage line too, not "Unknown or incomplete command".
 *
 * Generic over the server's command source [S], so it's tested on the JVM with plain Brigadier:
 * platform-fabric gives it Minecraft's, platform-velocity Velocity's. Brigadier is the server's
 * (Minecraft's, or Velocity's fork of it, which keeps Mojang's API), never shipped.
 */
public class BrigadierTree<S>(
    /** Our view of a Brigadier source: permissions, name, replies. */
    private val wrap: (S) -> CommandSource,
    /** The dispatcher in use now: a reload replaces it without rebuilding these nodes' callbacks. */
    private val dispatcher: () -> CommandDispatcher,
    /** What a source is told after a command: read on every run, so the plugin can set it once its translations are loaded. */
    private val feedback: () -> CommandFeedback = { CommandFeedback.ENGLISH },
    /**
     * Where a command runs: right away on Brigadier's thread by default (Minecraft's server
     * thread on Fabric), or handed to the plugin's own thread on a proxy, whose commands arrive
     * on the proxy's pool. A handed-off command always counts as run for Brigadier; our reply
     * says how it went.
     */
    private val runOn: ((() -> Unit) -> Unit)? = null,
) {
    /** One root node, called [label]: the root's name or one of its aliases. */
    public fun build(root: CommandNode, label: String): LiteralCommandNode<S> = literal(root, label).build()

    private fun literal(node: CommandNode, label: String): LiteralArgumentBuilder<S> {
        val builder = LiteralArgumentBuilder.literal<S>(label)
        builder.requires { source -> wrap(source).has(node.permission) }
        builder.executes(run)
        if (node.children.isNotEmpty()) {
            for (child in node.children) {
                for (name in listOf(child.name) + child.aliases) builder.then(literal(child, name))
            }
        } else {
            arguments(node, 0)?.let { builder.then(it) }
        }
        return builder
    }

    // The arguments from [index] on, each the child of the one before.
    private fun arguments(node: CommandNode, index: Int): ArgumentBuilder<S, *>? {
        val argument = node.arguments.getOrNull(index) ?: return null
        val last = index == node.arguments.lastIndex
        val type = if (last) StringArgumentType.greedyString() else StringArgumentType.word()
        val builder = RequiredArgumentBuilder.argument<S, String>(argument.name, type)
        builder.suggests(suggest)
        builder.executes(run)
        arguments(node, index + 1)?.let { builder.then(it) }
        return builder
    }

    private val run = Command<S> { context ->
        val source = wrap(context.source)
        val line = ours(line(context))
        val hop = runOn
        if (hop != null) {
            hop { feedback().message(dispatcher().dispatch(source, line), source)?.let(source::reply) }
            return@Command Command.SINGLE_SUCCESS
        }
        val result = dispatcher().dispatch(source, line)
        feedback().message(result, source)?.let(source::reply)
        if (result == CommandResult.Ran) Command.SINGLE_SUCCESS else 0
    }

    // A root registered under the plugin's prefix (`xsample:pay`, when another plugin has
    // `/pay`) is our dispatcher's `pay`.
    private fun ours(line: String): String {
        val colon = line.indexOf(':')
        val space = line.indexOf(' ').let { if (it < 0) line.length else it }
        return if (colon in 1 until space) line.substring(colon + 1) else line
    }

    // Completes the word being typed, which starts after the last space: our dispatcher
    // suggests whole words, and a greedy argument's "word" is only its last one.
    private val suggest = SuggestionProvider<S> { context, builder ->
        val typed = ours(builder.input.substring(commandStart(context), builder.start) + builder.remaining)
        val wordStart = builder.start + builder.remaining.lastIndexOf(' ') + 1
        val offset = builder.createOffset(wordStart)
        for (suggestion in dispatcher().suggest(wrap(context.source), typed)) offset.suggest(suggestion)
        offset.buildFuture()
    }

    public companion object {
        /**
         * The line our dispatcher gets: from this plugin's root word on, so `/execute as Zach
         * run pay Bob 5` hands over `pay Bob 5`.
         */
        public fun <S> line(context: CommandContext<S>): String = context.input.substring(commandStart(context))

        private fun <S> commandStart(context: CommandContext<S>): Int {
            // The last context in a chain (after execute's "run") is the one with our nodes.
            var last = context
            while (true) last = last.child ?: break
            return last.nodes.firstOrNull()?.range?.start ?: 0
        }
    }
}
