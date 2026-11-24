package co.xenastudios.core.common.command

import co.xenastudios.core.common.locked
import co.xenastudios.core.common.permission.Permission
import kotlin.time.Duration

/**
 * Something that runs around a command's handler: a cooldown, a confirmation, later a warmup.
 * [before] runs once the arguments are read and may stop the command; [after] runs once the
 * handler finished without failing. Hooks run in the order the command declares them.
 *
 * A hook's state lives in the hook, and a hook lives in its tree: one built anew on reload
 * starts empty. Called from whichever thread runs the command, so state is locked.
 */
public interface CommandHook {
    /** A permission whose holders skip this hook (the dispatcher doesn't call it for them); `null` if no one does. */
    public val bypass: Permission? get() = null

    /** `null` to go on, or the result that stops the command here. */
    public fun before(context: CommandContext): CommandResult?

    /** The handler ran without failing. */
    public fun after(context: CommandContext) {}
}

/** Who a hook's state is about: a player by UUID, anything else by name. */
internal val CommandSource.key: String get() = player?.uuid?.toString() ?: "name:$name"

/**
 * A command a source can use once per [duration]: it starts when the handler finishes without
 * failing, so a mistyped or failed run costs nothing. Holders of [bypass] have none.
 */
public class Cooldown(
    /** How long a source waits between uses. */
    public val duration: Duration,
    override val bypass: Permission? = null,
) : CommandHook {
    private val lock = Any()
    private val readyAt = HashMap<String, Duration>()

    init {
        require(duration.isPositive()) { "a cooldown is longer than zero" }
    }

    override fun before(context: CommandContext): CommandResult? {
        val ready = locked(lock) { readyAt[context.source.key] } ?: return null
        val remaining = ready - context.elapsed
        return if (remaining.isPositive()) CommandResult.OnCooldown(remaining) else null
    }

    override fun after(context: CommandContext) {
        locked(lock) {
            // Forget the ones already over now and then, so the map holds only those running.
            if (readyAt.size >= PRUNE_AT) readyAt.entries.removeAll { it.value <= context.elapsed }
            readyAt[context.source.key] = context.elapsed + duration
        }
    }

    private companion object {
        const val PRUNE_AT = 64
    }
}

/**
 * A command that runs only when it's typed twice: the first time the source is asked to
 * confirm ([CommandResult.ConfirmNeeded]), and the same line again within [window] runs it.
 * For what can't be undone, such as `/<plugin> forget <player>`.
 */
public class Confirmation(
    /** How long the first run waits for the second. */
    public val window: Duration,
) : CommandHook {
    private class Pending(val line: String, val until: Duration)

    private val lock = Any()
    private val pending = HashMap<String, Pending>()

    init {
        require(window.isPositive()) { "a confirmation window is longer than zero" }
    }

    override fun before(context: CommandContext): CommandResult? {
        val key = context.source.key
        val confirmed = locked(lock) {
            val waiting = pending.remove(key)
            if (waiting != null && waiting.line == context.line && context.elapsed < waiting.until) {
                true
            } else {
                if (pending.size >= PRUNE_AT) pending.entries.removeAll { it.value.until <= context.elapsed }
                pending[key] = Pending(context.line, context.elapsed + window)
                false
            }
        }
        return if (confirmed) null else CommandResult.ConfirmNeeded(context.line, window)
    }

    private companion object {
        const val PRUNE_AT = 64
    }
}
