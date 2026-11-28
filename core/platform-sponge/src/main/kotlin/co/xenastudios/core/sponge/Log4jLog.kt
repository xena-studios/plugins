package co.xenastudios.core.sponge

import co.xenastudios.core.common.platform.Log
import org.apache.logging.log4j.Logger

/**
 * The plugin's console lines through the Log4j logger Sponge gives each plugin, which its
 * console shows by the plugin's id: `[Server thread/INFO] [xsample]: ...`. Boot tests find our
 * warnings by it. Log4j is the server's, never shipped.
 */
internal class Log4jLog(private val logger: Logger) : Log {
    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String, cause: Throwable?) {
        if (cause == null) logger.warn(message) else logger.warn(message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        if (cause == null) logger.error(message) else logger.error(message, cause)
    }
}
