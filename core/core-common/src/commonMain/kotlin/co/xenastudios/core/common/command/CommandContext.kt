package co.xenastudios.core.common.command

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import kotlin.time.Duration

/**
 * One run of a command, as its hooks and its handler see it: who ran it, which node, the line
 * as typed and the arguments read from it.
 */
public class CommandContext internal constructor(
    /** Who ran it. */
    public val source: CommandSource,
    /** The command that's running. */
    public val node: CommandNode,
    /** The whole line, without the slash: `xhomes set base`. */
    public val line: String,
    /** The platform clock's [co.xenastudios.core.common.platform.Clock.elapsed] when it was run, for cooldowns. */
    public val elapsed: Duration,
    private val values: Map<Argument<*>, Any>,
) {
    /** The value of a required [argument], or of an optional one that was given. */
    public operator fun <T : Any> get(argument: Argument<T>): T =
        getOrNull(argument) ?: throw IllegalStateException("${node}: ${argument.usage} wasn't given; read an optional argument with getOrNull")

    /** The value of [argument], or `null` if it's optional and wasn't given. */
    public fun <T : Any> getOrNull(argument: Argument<T>): T? {
        require(argument in node.arguments) { "$node has no argument ${argument.usage}" }
        @Suppress("UNCHECKED_CAST")
        return values[argument] as T?
    }

    /** The player who ran it. Only for a [CommandNode.playerOnly] command, which only players reach. */
    public val player: PlayerRef
        get() = source.player ?: throw IllegalStateException("$node isn't playerOnly, so the console can run it: read source.player instead")

    /** Sends [message] to whoever ran it. */
    public fun reply(message: Component) {
        source.reply(message)
    }

    /** Stops the command, telling whoever ran it [message]. Hooks' `after` don't run, so a failed run starts no cooldown. */
    public fun fail(message: Component): Nothing = throw CommandFailure(message)
}

/** Thrown by [CommandContext.fail]; the dispatcher turns it into [CommandResult.Failed]. */
internal class CommandFailure(val feedback: Component) : RuntimeException(feedback.plain())

/**
 * How running a command went. Data, not text: the adapter shows it to the source in their
 * language through [CommandFeedback], so the same result reads right on every platform.
 */
public sealed class CommandResult {
    /** It ran. */
    public data object Ran : CommandResult()

    /** No command of this plugin answers to the first word. */
    public data object Unknown : CommandResult()

    /** The source lacks [permission], which the command (or one above it) needs. */
    public data class NoPermission(public val permission: String) : CommandResult()

    /** Only players can run it, and the source isn't one. */
    public data object PlayersOnly : CommandResult()

    /** What was typed doesn't fit the command: missing or extra words. [usage] is how to type it. */
    public data class Usage(public val usage: String) : CommandResult()

    /** [input], typed as [argument], can't be read, for the reason in [error]. */
    public data class InvalidArgument(
        public val argument: String,
        public val input: String,
        public val error: ArgumentError,
        public val usage: String,
    ) : CommandResult()

    /** The source used it too recently; they can again in [remaining]. */
    public data class OnCooldown(public val remaining: Duration) : CommandResult()

    /** It needs confirming: running [line] again within [window] runs it. */
    public data class ConfirmNeeded(public val line: String, public val window: Duration) : CommandResult()

    /** The handler stopped with [message] ([CommandContext.fail]). */
    public data class Failed(public val message: Component) : CommandResult()

    /** The handler threw [cause], which is logged; the source is told something went wrong. */
    public class Error(public val cause: Throwable) : CommandResult() {
        override fun toString(): String = "Error(${cause::class.simpleName}: ${cause.message})"
    }
}
