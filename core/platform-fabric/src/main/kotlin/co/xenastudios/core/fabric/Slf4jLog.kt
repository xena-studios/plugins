package co.xenastudios.core.fabric

import co.xenastudios.core.common.platform.Log
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The plugin's console lines through SLF4J, which Minecraft has shipped since 1.18. A dedicated
 * server's log doesn't show the logger's name, so each line starts with the plugin's, as
 * Bukkit's do: `[Server thread/INFO]: [xSample] ...`. Boot tests find our warnings by it.
 */
public class Slf4jLog(private val name: String) : Log {
    private val logger: Logger = LoggerFactory.getLogger(name)
    private val prefix = "[$name] "

    override fun info(message: String) {
        logger.info(prefix + message)
    }

    override fun warn(message: String, cause: Throwable?) {
        if (cause == null) logger.warn(prefix + message) else logger.warn(prefix + message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        if (cause == null) logger.error(prefix + message) else logger.error(prefix + message, cause)
    }
}
