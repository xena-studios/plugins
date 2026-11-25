package co.xenastudios.core.jvm.platform

import co.xenastudios.core.common.platform.Log
import org.slf4j.Logger

/**
 * The plugin's console lines through SLF4J: Velocity gives each plugin a logger of its own,
 * which its console shows as `[xsample]`, and Minecraft has shipped SLF4J since 1.18, where a
 * dedicated server's log doesn't show the logger's name, so Fabric passes a [prefix] with the
 * plugin's, as Bukkit's lines have: `[Server thread/INFO]: [xSample] ...`. Boot tests find our
 * warnings by it. SLF4J is the server's, never shipped.
 */
public class Slf4jLog @JvmOverloads constructor(private val logger: Logger, private val prefix: String = "") : Log {
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
