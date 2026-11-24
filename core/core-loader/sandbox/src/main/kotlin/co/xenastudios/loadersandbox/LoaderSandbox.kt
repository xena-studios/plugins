package co.xenastudios.loadersandbox

import co.xenastudios.core.loader.PluginEntry
import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin

/** Where the stdlib came from, built with ordinary stdlib calls. */
private data class Report(val kotlin: String, val stdlib: String, val loader: String, val java: String)

/**
 * The Kotlin side: loaded by the shim once the stdlib is visible. `/loadersandbox` runs some
 * Kotlin and prints `LOADER PASS` with where the stdlib came from, which the boot tests expect.
 */
public class LoaderSandbox(private val plugin: JavaPlugin) : PluginEntry {

    override fun load() {
        plugin.logger.info("Kotlin entry point loaded: ${report().stdlib}")
    }

    override fun enable() {
        plugin.getCommand("loadersandbox")?.executor = CommandExecutor { sender, _, _, args ->
            val report = report()
            val squares = (1..5).asSequence().map { it * it }.toList()
            val words = args.sortedBy { it.lowercase() }.joinToString(",", prefix = "[", postfix = "]")
            sender.sendMessage(
                "LOADER PASS kotlin=${report.kotlin} stdlib=${report.stdlib} loader=${report.loader}" +
                    " java=${report.java} squares=$squares args=$words",
            )
            true
        }
    }

    override fun disable() {
        plugin.logger.info("Kotlin entry point disabled")
    }

    private fun report(): Report {
        val unit = Unit::class.java
        // A jar's path, or a jar: URL to the class inside it (Paper's library loader).
        val location = unit.protectionDomain.codeSource?.location?.toString() ?: "?"
        val jar = location.substringBefore("!/").trimEnd('/').substringAfterLast('/')
        return Report(KotlinVersion.CURRENT.toString(), jar, unit.classLoader.javaClass.simpleName, System.getProperty("java.version"))
    }
}
