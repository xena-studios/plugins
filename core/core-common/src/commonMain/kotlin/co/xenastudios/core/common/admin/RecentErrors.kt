package co.xenastudios.core.common.admin

import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import kotlin.time.Instant

/**
 * The plugin's [Log], keeping its last [capacity] warnings and errors for `/<plugin> debug`'s
 * report: what went wrong lately is usually the first thing support asks for, and an owner
 * rarely has the console scrollback. Everything is passed on to [log] as it comes.
 *
 * The plugin hands this to everything that logs, in place of the platform's own. Safe from
 * any thread.
 */
public class RecentErrors(
    private val log: Log,
    private val clock: Clock,
    /** How many lines it keeps; older ones are dropped. */
    public val capacity: Int = 50,
) : Log {
    /** One warning or error, when it happened, and its cause's stack trace, if it had one. */
    public class Entry(
        public val at: Instant,
        /** `WARN` or `ERROR`. */
        public val level: String,
        public val message: String,
        public val trace: String?,
    ) {
        override fun toString(): String = "[$at] $level $message" + (trace?.let { "\n$it" } ?: "")
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    init {
        require(capacity >= 1) { "it keeps at least one line" }
    }

    /** The lines kept, oldest first. */
    public val recent: List<Entry> get() = locked(lock) { entries.toList() }

    override fun info(message: String) {
        log.info(message)
    }

    override fun warn(message: String, cause: Throwable?) {
        log.warn(message, cause)
        keep("WARN", message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        log.error(message, cause)
        keep("ERROR", message, cause)
    }

    private fun keep(level: String, message: String, cause: Throwable?) {
        // A trace is cut short: the first lines say where, and a report has room for fifty.
        val trace = cause?.stackTraceToString()?.lineSequence()?.take(TRACE_LINES)?.joinToString("\n")
        val entry = Entry(clock.now(), level, message, trace)
        locked(lock) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    private companion object {
        const val TRACE_LINES = 12
    }
}
