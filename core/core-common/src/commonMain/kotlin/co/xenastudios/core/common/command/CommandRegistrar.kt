package co.xenastudios.core.common.command

import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.time.Durations

/**
 * Where the platform's adapter puts a plugin's commands on the server. The core calls [sync] at
 * startup and after every reload with a dispatcher on the newly built tree, so turning a
 * command on or off, or changing its aliases or permission, applies on reload (DECISIONS.md,
 * "Commands are registered at runtime").
 *
 * An adapter registers each root of [CommandDispatcher.tree] under its name and aliases and
 * answers it with [CommandDispatcher.dispatch] and [CommandDispatcher.suggest], showing the
 * result through [CommandFeedback]. Three ways to do that:
 *
 * - **Directly**, as today's core does on Bukkit: one platform command per root that hands the
 *   whole line to the dispatcher. Enough where the server doesn't send clients a command tree.
 * - **Straight onto Brigadier** (Fabric): a literal per node, a string argument per argument
 *   with our suggestions, every node running the whole line through the dispatcher. Minecraft
 *   sends that tree to clients itself, so Cloud would add nothing but a jar pinned to one
 *   Minecraft version (DECISIONS.md, "platform-fabric").
 * - **Through Cloud 2** (Paper, Bukkit, NeoForge, Velocity), so clients get the full
 *   tree, with Brigadier's argument hints. The adapter walks the tree: every executable node
 *   becomes one `commandBuilder(root, aliases)` chain of `.literal(name, aliases)` for its
 *   path, then `.required` or `.optional` per argument with a parser that calls
 *   [ArgumentType.parse] (a greedy one as Cloud's greedy string) and a suggestion provider
 *   that calls [ArgumentType.suggest]; `.permission(...)` with the node's permissions and
 *   `.senderType(player)` for [CommandNode.playerOnly]. Its handler hands the raw input back to
 *   [CommandDispatcher.dispatch], so hooks, errors and the result are ours, not Cloud's, and
 *   behave the same where there's no Cloud: Sponge 8+ gets a small bridge of its own
 *   (DECISIONS.md, M0), Pumpkin its own commands.
 *   Cloud can't unregister on every platform, so a reload re-registers only what changed.
 *
 * Called on the global thread.
 */
public interface CommandRegistrar {
    /**
     * Makes the plugin's commands on the server exactly [dispatcher]'s roots: registers new
     * ones, unregisters ones that are gone, and answers the ones that stay with the new
     * dispatcher. Returns warnings to log, such as a name another plugin already has.
     */
    public fun sync(dispatcher: CommandDispatcher): List<String>

    /** Unregisters every command of the plugin, as it's disabled. */
    public fun clear()
}

/**
 * What a source is told after running a command, in their language. [ENGLISH] until the
 * translations' messages take over; an adapter calls [message] with every result and replies
 * with what it gets, if anything.
 */
public fun interface CommandFeedback {
    /** What to tell the source about [result], or `null` for nothing (a command that [CommandResult.Ran] replies itself). */
    public fun message(result: CommandResult): Component?

    public companion object {
        /** Plain English, as today's core says it. */
        public val ENGLISH: CommandFeedback = CommandFeedback { result ->
            when (result) {
                CommandResult.Ran -> null
                CommandResult.Unknown -> Component.text("Unknown command.")
                is CommandResult.NoPermission -> Component.text("You don't have permission to do that.")
                CommandResult.PlayersOnly -> Component.text("Only players can use this command.")
                is CommandResult.Usage -> Component.text("Usage: ${result.usage}")
                is CommandResult.InvalidArgument -> Component.text("${describe(result.error, result.input)} Usage: ${result.usage}")
                is CommandResult.OnCooldown -> Component.text("You can use that again in ${Durations.format(result.remaining)}.")
                is CommandResult.ConfirmNeeded ->
                    Component.text("Type /${result.line} again within ${Durations.format(result.window)} to confirm.")
                is CommandResult.Failed -> result.message
                is CommandResult.Error -> Component.text("Something went wrong running that command; the console has the details.")
            }
        }

        private fun describe(error: ArgumentError, input: String): String = when (error) {
            ArgumentError.NotAnInteger -> "\"$input\" isn't a whole number."
            ArgumentError.NotANumber -> "\"$input\" isn't a number."
            ArgumentError.NotABoolean -> "\"$input\" isn't true or false."
            is ArgumentError.TooSmall -> "$input is less than ${number(error.min)}."
            is ArgumentError.TooLarge -> "$input is more than ${number(error.max)}."
            is ArgumentError.TooShort -> "$input is shorter than ${Durations.format(error.min)}."
            is ArgumentError.TooLong -> "$input is longer than ${Durations.format(error.max)}."
            is ArgumentError.NotAChoice -> "\"$input\" isn't one of ${error.choices.joinToString(", ")}."
            is ArgumentError.NotADuration -> "\"$input\" isn't a duration, such as 30s or 1h 30m."
            ArgumentError.UnknownPlayer -> "No player called $input is online."
            is ArgumentError.Invalid -> error.reason
        }

        private fun number(value: Double): String = if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    }
}
