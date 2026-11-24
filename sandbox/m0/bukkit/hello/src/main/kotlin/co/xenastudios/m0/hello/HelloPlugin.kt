package co.xenastudios.m0.hello

import co.xenastudios.m0.loader.Delegate
import org.bukkit.Bukkit
import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin

/** What the command reports, built with ordinary stdlib calls (collections, lambdas, sequences). */
data class Report(val kotlin: String, val stdlibFrom: String, val java: String, val server: String) {
    fun lines(): List<String> = listOf(
        "Kotlin $kotlin on Java $java",
        "stdlib from $stdlibFrom",
        "server $server",
    )
}

class HelloPlugin(private val plugin: JavaPlugin) : Delegate {

    override fun enable() {
        val t0 = System.nanoTime()
        plugin.getCommand("hello")!!.executor = CommandExecutor { sender, _, _, args ->
            val report = report()
            val extra = args.asSequence().map { it.uppercase() }.sorted().joinToString(prefix = " args=")
            report.lines().forEach { sender.sendMessage("[Hello] $it") }
            sender.sendMessage("[Hello] squares=${(1..5).map { it * it }}$extra")
            true
        }
        val t1 = System.nanoTime()
        // provides: does the server resolve another plugin's lookup by the provided name?
        val provided = Bukkit.getPluginManager().getPlugin("HelloProvided")
        plugin.logger.info("getPlugin(\"HelloProvided\") -> ${provided?.name ?: "null"}")
        val t2a = System.nanoTime()
        Bukkit.getVersion()
        val t2 = System.nanoTime()
        plugin.logger.info(report().lines().joinToString(" | "))
        val t3 = System.nanoTime()
        plugin.logger.info("enable steps: command %.1f ms, getPlugin %.1f ms, Bukkit.getVersion %.1f ms, report %.1f ms".format(
            (t1 - t0) / 1e6, (t2a - t1) / 1e6, (t2 - t2a) / 1e6, (t3 - t2) / 1e6))
    }

    override fun disable() {}

    private fun report(): Report {
        val code = Unit::class.java.protectionDomain.codeSource?.location?.path ?: "?"
        return Report(
            kotlin = KotlinVersion.CURRENT.toString(),
            stdlibFrom = code.substringAfterLast('/') + " via " + Unit::class.java.classLoader.javaClass.simpleName,
            java = System.getProperty("java.version"),
            server = Bukkit.getVersion(),
        )
    }
}
