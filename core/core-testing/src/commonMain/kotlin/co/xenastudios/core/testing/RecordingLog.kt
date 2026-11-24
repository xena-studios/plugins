package co.xenastudios.core.testing

import co.xenastudios.core.common.platform.Log

/** A [Log] that keeps every line, for tests to read. */
public class RecordingLog : Log {
    /** A line: its [level], [message] and [cause]. */
    public data class Line(public val level: Level, public val message: String, public val cause: Throwable?)

    /** How serious a line is. */
    public enum class Level { INFO, WARN, ERROR }

    private val recorded = ArrayList<Line>()

    /** Every line, oldest first. */
    public val lines: List<Line> get() = recorded.toList()

    /** The messages of the [Level.ERROR] lines. */
    public val errors: List<String> get() = recorded.filter { it.level == Level.ERROR }.map { it.message }

    override fun info(message: String) {
        recorded += Line(Level.INFO, message, null)
    }

    override fun warn(message: String, cause: Throwable?) {
        recorded += Line(Level.WARN, message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        recorded += Line(Level.ERROR, message, cause)
    }

    /** Forgets every line so far. */
    public fun clear() {
        recorded.clear()
    }
}
