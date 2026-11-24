package co.xenastudios.core.common.command

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.time.DurationError
import co.xenastudios.core.common.time.Durations
import kotlin.time.Duration

/**
 * How one argument of a command is read and completed: an integer in a range, a player who's
 * online, one of some words. The same object parses what's typed, suggests what to type and
 * says what it takes in the docs ([name], [choices], [min], [max]), so the three can't
 * disagree. [ArgumentTypes] has the common ones.
 */
public interface ArgumentType<T : Any> {
    /** What it is, for the docs and the manifest: `integer`, `player`, `duration`, ... */
    public val name: String

    /** Whether it takes the rest of the line, spaces and all, such as a reason. Only an argument that's last can. */
    public val greedy: Boolean get() = false

    /** The words it takes, if it takes only some, for the docs. */
    public val choices: List<String> get() = emptyList()

    /** The smallest number it takes, if it has one, for the docs. */
    public val min: Double? get() = null

    /** The largest number it takes, if it has one, for the docs. */
    public val max: Double? get() = null

    /** Reads [input] (one word, or the rest of the line if it's [greedy]), or says what's wrong with it. */
    public fun parse(input: String, source: CommandSource): Outcome<T, ArgumentError>

    /** What [source] might type here, for tab completion. The dispatcher keeps those starting with what's typed. */
    public fun suggest(source: CommandSource): List<String> = choices
}

/**
 * Why an argument couldn't be read. Data, not text, so each is shown in the reader's language
 * by [CommandFeedback].
 */
public sealed class ArgumentError {
    /** Not a whole number. */
    public data object NotAnInteger : ArgumentError()

    /** Not a number. */
    public data object NotANumber : ArgumentError()

    /** Not `true` or `false`. */
    public data object NotABoolean : ArgumentError()

    /** Smaller than [min]. */
    public data class TooSmall(public val min: Double) : ArgumentError()

    /** Larger than [max]. */
    public data class TooLarge(public val max: Double) : ArgumentError()

    /** None of the [choices]. */
    public data class NotAChoice(public val choices: List<String>) : ArgumentError()

    /** A duration shorter than [min]. */
    public data class TooShort(public val min: Duration) : ArgumentError()

    /** A duration longer than [max]. */
    public data class TooLong(public val max: Duration) : ArgumentError()

    /** Not a duration, as [error] says. */
    public data class NotADuration(public val error: DurationError) : ArgumentError()

    /** No player online by that name (or none the source can see). */
    public data object UnknownPlayer : ArgumentError()

    /** A custom type's own reason, in English. */
    public data class Invalid(public val reason: String) : ArgumentError()
}

/**
 * The players online, as the platform sees them, for [ArgumentTypes.player]. The adapter
 * implements it; a player the source can't see (a vanished one) isn't found or suggested.
 */
public interface OnlinePlayers {
    /** The online player called [name] (any case) whom [viewer] can see, or `null`. */
    public fun find(name: String, viewer: CommandSource): PlayerRef?

    /** The names of the online players [viewer] can see. */
    public fun names(viewer: CommandSource): List<String>
}

/** The argument types commands use most. */
public object ArgumentTypes {
    /** One word. */
    public val word: ArgumentType<String> = object : ArgumentType<String> {
        override val name: String get() = "word"

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)
    }

    /** The rest of the line, spaces and all: a reason, a message. */
    public val text: ArgumentType<String> = object : ArgumentType<String> {
        override val name: String get() = "text"
        override val greedy: Boolean get() = true

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)
    }

    /** `true` or `false`. */
    public val boolean: ArgumentType<Boolean> = object : ArgumentType<Boolean> {
        override val name: String get() = "boolean"
        override val choices: List<String> = listOf("true", "false")

        override fun parse(input: String, source: CommandSource): Outcome<Boolean, ArgumentError> = when (input.lowercase()) {
            "true" -> Outcome.Ok(true)
            "false" -> Outcome.Ok(false)
            else -> Outcome.Err(ArgumentError.NotABoolean)
        }
    }

    /** A whole number from [min] to [max]. */
    public fun integer(min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): ArgumentType<Int> {
        require(min <= max) { "an integer from $min to $max" }
        return object : ArgumentType<Int> {
            override val name: String get() = "integer"
            override val min: Double? = min.takeIf { it != Int.MIN_VALUE }?.toDouble()
            override val max: Double? = max.takeIf { it != Int.MAX_VALUE }?.toDouble()

            override fun parse(input: String, source: CommandSource): Outcome<Int, ArgumentError> {
                val value = input.toIntOrNull() ?: return Outcome.Err(ArgumentError.NotAnInteger)
                return ranged(value, value.toDouble(), this.min, this.max)
            }
        }
    }

    /** A number from [min] to [max]. */
    public fun number(min: Double = Double.NEGATIVE_INFINITY, max: Double = Double.POSITIVE_INFINITY): ArgumentType<Double> {
        require(min <= max) { "a number from $min to $max" }
        return object : ArgumentType<Double> {
            override val name: String get() = "number"
            override val min: Double? = min.takeIf { it.isFinite() }
            override val max: Double? = max.takeIf { it.isFinite() }

            override fun parse(input: String, source: CommandSource): Outcome<Double, ArgumentError> {
                val value = input.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Outcome.Err(ArgumentError.NotANumber)
                return ranged(value, value, this.min, this.max)
            }
        }
    }

    /** One of [choices], in any case; the value is the choice as declared. */
    public fun choice(vararg choices: String): ArgumentType<String> {
        require(choices.isNotEmpty()) { "a choice needs choices" }
        require(choices.none { it.isEmpty() || ' ' in it }) { "a choice is one word" }
        val list = choices.toList()
        return object : ArgumentType<String> {
            override val name: String get() = "choice"
            override val choices: List<String> get() = list

            override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> =
                list.firstOrNull { it.equals(input, ignoreCase = true) }?.let { Outcome.Ok(it) }
                    ?: Outcome.Err(ArgumentError.NotAChoice(list))
        }
    }

    /** A duration as the configs write them (`30s`, `1h 30m`), from [min] to [max]. The docs give the limits in seconds. */
    public fun duration(min: Duration = Duration.ZERO, max: Duration = Durations.MAX): ArgumentType<Duration> {
        require(min <= max) { "a duration from $min to $max" }
        return object : ArgumentType<Duration> {
            override val name: String get() = "duration"
            override val min: Double? = min.inWholeMilliseconds.takeIf { it > 0 }?.let { it / 1000.0 }
            override val max: Double? = max.takeIf { it < Durations.MAX }?.let { it.inWholeMilliseconds / 1000.0 }

            override fun parse(input: String, source: CommandSource): Outcome<Duration, ArgumentError> = when (val read = Durations.parse(input)) {
                is Outcome.Err -> Outcome.Err(ArgumentError.NotADuration(read.error))
                is Outcome.Ok -> when {
                    read.value < min -> Outcome.Err(ArgumentError.TooShort(min))
                    read.value > max -> Outcome.Err(ArgumentError.TooLong(max))
                    else -> read
                }
            }
        }
    }

    /** A player online whom the source can see, by name, as [players] finds them. */
    public fun player(players: OnlinePlayers): ArgumentType<PlayerRef> = object : ArgumentType<PlayerRef> {
        override val name: String get() = "player"

        override fun parse(input: String, source: CommandSource): Outcome<PlayerRef, ArgumentError> =
            players.find(input, source)?.let { Outcome.Ok(it) } ?: Outcome.Err(ArgumentError.UnknownPlayer)

        override fun suggest(source: CommandSource): List<String> = players.names(source)
    }

    /**
     * A player's name, online or not: 1 to 16 of a-z, 0-9 and _, as Minecraft allows. For
     * commands about players who may be offline, which look the name up themselves.
     */
    public val playerName: ArgumentType<String> = object : ArgumentType<String> {
        private val valid = Regex("[A-Za-z0-9_]{1,16}")

        override val name: String get() = "player name"

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> =
            if (valid.matches(input)) Outcome.Ok(input) else Outcome.Err(ArgumentError.Invalid("\"$input\" isn't a player's name."))
    }

    private fun <T> ranged(value: T, number: Double, min: Double?, max: Double?): Outcome<T, ArgumentError> = when {
        min != null && number < min -> Outcome.Err(ArgumentError.TooSmall(min))
        max != null && number > max -> Outcome.Err(ArgumentError.TooLarge(max))
        else -> Outcome.Ok(value)
    }
}

/**
 * One argument of a command, as declared: its [name] (`<amount>` in the usage), how it's read
 * ([type]), what it's for, and whether it can be left out. A handler reads its value with
 * `context[argument]`.
 */
public class Argument<T : Any> internal constructor(
    public val name: String,
    public val type: ArgumentType<T>,
    public val description: String,
    /** Whether it must be given. Only the last arguments can be optional. */
    public val required: Boolean,
) {
    /** How the usage line writes it: `<amount>`, or `[reason]` if it's optional. */
    public val usage: String get() = if (required) "<$name>" else "[$name]"

    override fun toString(): String = usage
}
