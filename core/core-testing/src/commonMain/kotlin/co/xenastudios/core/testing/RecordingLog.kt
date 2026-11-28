package co.xenastudios.core.testing

import co.xenastudios.core.common.platform.Log
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A [Log] that keeps every line, for tests to read. */
@OptIn(ExperimentalAtomicApi::class)
public class RecordingLog : Log {
    /** A line: its [level], [message] and [cause]. */
    public data class Line(public val level: Level, public val message: String, public val cause: Throwable?)

    /** How serious a line is. */
    public enum class Level { INFO, WARN, ERROR }

    // Lines come from any thread (a scheduler's worker, a database callback) while a test reads
    // them on its own, so each line makes a new list: a reader always sees a whole one, and no
    // line is lost to two threads adding at once.
    private val recorded = AtomicReference<List<Line>>(emptyList())

    /** Every line, oldest first. */
    public val lines: List<Line> get() = recorded.load()

    /** The warnings' messages, oldest first. */
    public val warnings: List<String> get() = lines.filter { it.level == Level.WARN }.map { it.message }

    /** The messages of the [Level.ERROR] lines. */
    public val errors: List<String> get() = lines.filter { it.level == Level.ERROR }.map { it.message }

    override fun info(message: String) {
        add(Line(Level.INFO, message, null))
    }

    override fun warn(message: String, cause: Throwable?) {
        add(Line(Level.WARN, message, cause))
    }

    override fun error(message: String, cause: Throwable?) {
        add(Line(Level.ERROR, message, cause))
    }

    private fun add(line: Line) {
        while (true) {
            val now = recorded.load()
            if (recorded.compareAndSet(now, now + line)) return
        }
    }

    /** Forgets every line so far. */
    public fun clear() {
        recorded.store(emptyList())
    }
}
