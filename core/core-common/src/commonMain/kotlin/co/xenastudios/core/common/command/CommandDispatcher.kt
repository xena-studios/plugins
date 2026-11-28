package co.xenastudios.core.common.command

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.permission.has
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log

/**
 * Runs and completes a [CommandTree], the same on every platform: the adapter hands it the
 * line a source typed, and shows the [CommandResult] it gets back through [CommandFeedback].
 * Where the platform has its own command framework (Cloud on the JVM, Pumpkin's commands),
 * the adapter registers the tree's shape there and still calls this to run a command, so
 * parsing, permissions, hooks and errors behave alike everywhere ([CommandRegistrar]).
 *
 * Words are split on spaces; an argument that takes the rest of the line ([ArgumentType.greedy])
 * gets it as typed. Literal words match in any case. A node's permission is needed on top of
 * every permission above it. Nothing a handler throws leaves [dispatch]: it's logged and
 * returned as [CommandResult.Error]. Safe from any thread; the tree is never changed, and
 * hooks lock their own state.
 */
public class CommandDispatcher(
    /** The commands it runs. */
    public val tree: CommandTree,
    private val clock: Clock,
    private val log: Log,
) {
    /** Runs [line] (without the slash: `xhomes set base`) as [source]. */
    public fun dispatch(source: CommandSource, line: String): CommandResult {
        val words = Words.of(line)
        if (words.size == 0) return CommandResult.Unknown
        var node = tree.root(words.text(0)) ?: return CommandResult.Unknown
        node.permission?.let { if (!source.has(it)) return CommandResult.NoPermission(it.node) }
        var at = 1
        while (at < words.size) {
            val child = node.child(words.text(at)) ?: break
            child.permission?.let { if (!source.has(it)) return CommandResult.NoPermission(it.node) }
            node = child
            at++
        }
        val handler = node.handler ?: return CommandResult.Usage(node.usage(visible(node, source)))
        if (node.playerOnly && source.player == null) return CommandResult.PlayersOnly

        val values = LinkedHashMap<Argument<*>, Any>()
        for (argument in node.arguments) {
            if (at >= words.size) {
                if (argument.required) return CommandResult.Usage(node.usage)
                break
            }
            val input = if (argument.type.greedy) words.rest(at) else words.text(at)
            at = if (argument.type.greedy) words.size else at + 1
            when (val read = argument.type.parse(input, source)) {
                is Outcome.Ok -> values[argument] = read.value
                is Outcome.Err -> return CommandResult.InvalidArgument(argument.name, input, read.error, node.usage)
            }
        }
        if (at < words.size) return CommandResult.Usage(node.usage(visible(node, source)))

        val context = CommandContext(source, node, words.normalized(), clock.elapsed(), values)
        val hooks = node.hooks.filter { hook -> hook.bypass.let { it == null || !source.has(it) } }
        return try {
            for (hook in hooks) hook.before(context)?.let { return it }
            handler.run(context)
            for (hook in hooks) hook.after(context)
            CommandResult.Ran
        } catch (failure: CommandFailure) {
            CommandResult.Failed(failure.feedback)
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            log.error("/${context.line} failed for ${source.name}", e)
            CommandResult.Error(e)
        }
    }

    /**
     * What [source] might type next, for tab completion: the last word of [line] completed,
     * or everything that fits after a trailing space. Only commands the source may use are
     * suggested.
     */
    public fun suggest(source: CommandSource, line: String): List<String> {
        val words = Words.of(line)
        val partial = if (line.endsWith(' ') || words.size == 0) "" else words.text(words.size - 1)
        val typed = if (line.endsWith(' ') || words.size == 0) words.size else words.size - 1
        if (typed == 0) return matching(tree.roots.filter { source.has(it.permission) }.map { it.name }, partial)

        var node = tree.root(words.text(0))?.takeIf { source.has(it.permission) } ?: return emptyList()
        var at = 1
        while (at < typed) {
            val child = node.child(words.text(at)) ?: break
            if (!source.has(child.permission)) return emptyList()
            node = child
            at++
        }
        if (node.children.isNotEmpty()) {
            return if (at == typed) matching(visible(node, source).map { it.name }, partial) else emptyList()
        }
        if (node.playerOnly && source.player == null) return emptyList()
        // Which argument the last word is: a greedy one takes everything after it, and completes
        // each of its words from what it suggests (CoreProtect-style parameters, `u:` `t:`...).
        var index = 0
        while (at < typed) {
            val argument = node.arguments.getOrNull(index) ?: return emptyList()
            if (argument.type.greedy) return matching(argument.type.suggest(source), partial)
            at++
            index++
        }
        val argument = node.arguments.getOrNull(index) ?: return emptyList()
        return matching(argument.type.suggest(source), partial)
    }

    private fun visible(node: CommandNode, source: CommandSource): List<CommandNode> = node.children.filter { source.has(it.permission) }

    private fun matching(options: List<String>, partial: String): List<String> =
        options.filter { it.startsWith(partial, ignoreCase = true) }.distinct()
}

/** A line split into words on spaces, remembering where each starts so the rest of the line can be taken as typed. */
private class Words private constructor(private val line: String, private val starts: IntArray, private val ends: IntArray) {
    val size: Int get() = starts.size

    fun text(index: Int): String = line.substring(starts[index], ends[index])

    /** From word [index] to the end, as typed, without trailing spaces. */
    fun rest(index: Int): String = line.substring(starts[index]).trimEnd()

    /** The words joined by single spaces, the first without a slash or namespace: what a confirmation compares. */
    fun normalized(): String = (0 until size).joinToString(" ") { text(it) }

    companion object {
        fun of(raw: String): Words {
            // The adapter may pass the label as typed: "/xhomes" or "xhomes:xhomes".
            var line = raw.removePrefix("/")
            val firstSpace = line.indexOf(' ').let { if (it < 0) line.length else it }
            val colon = line.lastIndexOf(':', firstSpace - 1)
            if (colon >= 0) line = line.substring(colon + 1)
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var i = 0
            while (i < line.length) {
                while (i < line.length && line[i] == ' ') i++
                if (i >= line.length) break
                starts += i
                while (i < line.length && line[i] != ' ') i++
                ends += i
            }
            return Words(line, starts.toIntArray(), ends.toIntArray())
        }
    }
}
