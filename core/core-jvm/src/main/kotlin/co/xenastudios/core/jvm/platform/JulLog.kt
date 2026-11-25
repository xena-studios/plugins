package co.xenastudios.core.jvm.platform

import co.xenastudios.core.common.platform.Log
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The core's [Log] on a plugin's own `java.util.logging` logger, which Bukkit and BungeeCord
 * prefix with `[<Plugin>]`: warnings and errors at Java's `WARNING` and `SEVERE`, which their
 * consoles show as `WARN` and `ERROR`, and the boot tests look for.
 */
public class JulLog(private val logger: Logger) : Log {
    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String, cause: Throwable?) {
        logger.log(Level.WARNING, message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        logger.log(Level.SEVERE, message, cause)
    }
}
