package co.xenastudios.loadersandbox

import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPluginLoader

/**
 * Boots the built jars the way Bukkit 1.8.8 does, through its own `JavaPluginLoader` and
 * `PluginClassLoader`, on a stand-in server, and runs this folder's boot-checks.txt against
 * them: the jar as Shadow built it, and as R8 shrank it (the xena.r8 convention passes both).
 * So the keep rules are checked on every build, not only when a real server boots the jar.
 *
 * The stdlib is on the class path, as a server that read `libraries:` would have it, so the
 * loader finds it and downloads nothing. This module's own classes aren't (build.gradle.kts):
 * every class the plugin runs comes from the jar.
 */
class JarBootTest {

    @Test
    fun shadedJarBoots() = boot(jar("xena.r8.inputJar"))

    @Test
    fun shrunkJarBoots() = boot(jar("xena.r8.jar"))

    private fun jar(property: String): File =
        File(System.getProperty(property) ?: fail("$property isn't set: run this through Gradle")).also {
            assertTrue(it.isFile, "no jar at $it")
        }

    private fun boot(jar: File) {
        val plugins = Files.createTempDirectory("plugins").toFile()
        val copy = jar.copyTo(File(plugins, jar.name))
        val log = mutableListOf<LogRecord>()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { synchronized(log) { log += record } }
                override fun flush() {}
                override fun close() {}
            })
        }
        val commands = mutableMapOf<String, PluginCommand>()
        var plugin: Plugin? = null
        val server = Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, args ->
            when (method.name) {
                "getLogger" -> logger
                "getName" -> "JarBootTest"
                "getVersion", "getBukkitVersion" -> "1.8.8-R0.1-SNAPSHOT"
                // As a server does from plugin.yml; only once the loader has made the plugin.
                "getPluginCommand" -> plugin?.let { owner ->
                    val label = (args!![0] as String).substringAfter(':')
                    if (owner.description.commands?.containsKey(label) != true) null
                    else commands.getOrPut(label) { newCommand(label, owner) }
                }
                else -> default(method.returnType)
            }
        } as Server
        // Bukkit's static server, which the plugin loader reads; set once per server, so set here
        // by hand for each boot.
        Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }.set(null, server)

        // Deprecated for plugins, which get theirs from the server; a server makes it this way.
        @Suppress("DEPRECATION")
        val loader = JavaPluginLoader(server)
        val loaded = loader.loadPlugin(copy)
        plugin = loaded
        loaded.onLoad()
        loader.enablePlugin(loaded)
        assertTrue(loaded.isEnabled, "the plugin disabled itself:\n" + render(log))

        val replies = mutableListOf<String>()
        val sender = Proxy.newProxyInstance(CommandSender::class.java.classLoader, arrayOf(CommandSender::class.java)) { _, method, args ->
            when (method.name) {
                "sendMessage" -> {
                    when (val message = args!![0]) {
                        is String -> replies += message
                        is Array<*> -> message.forEach { replies += it.toString() }
                    }
                    null
                }
                "getName" -> "CONSOLE"
                "getServer" -> server
                "isOp", "hasPermission", "isPermissionSet" -> true
                else -> default(method.returnType)
            }
        } as CommandSender

        val checks = File(System.getProperty("xena.bootChecks") ?: fail("xena.bootChecks isn't set"))
        var since = 0
        for (line in checks.readLines().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val step = line.substringBefore(':')
            val value = line.substringAfter(':').trim()
            // Steps for one server only aren't for this one.
            if (value.startsWith("paper ") || value.startsWith("folia ")) continue
            when (step) {
                "logged" -> assertTrue(render(log).contains(value), "\"$value\" was never logged:\n" + render(log))
                "run" -> {
                    since = replies.size
                    val words = value.split(' ').filter { it.isNotEmpty() }
                    val command = server.getPluginCommand(words[0]) ?: fail("${words[0]} isn't a command of the plugin")
                    command.execute(sender, words[0], words.drop(1).toTypedArray())
                }
                "expect" -> assertTrue(replies.drop(since).any { value in it }, "\"$value\" wasn't in the replies: ${replies.drop(since)}")
                else -> fail("unknown step in ${checks.name}: $line")
            }
        }

        loader.disablePlugin(loaded)
        val problems = synchronized(log) { log.filter { it.level.intValue() >= Level.WARNING.intValue() } }
        assertTrue(problems.isEmpty(), "${jar.name} logged warnings or errors:\n" + render(problems))
        plugins.deleteRecursively()
    }

    private fun newCommand(label: String, owner: Plugin): PluginCommand =
        PluginCommand::class.java.getDeclaredConstructor(String::class.java, Plugin::class.java)
            .apply { isAccessible = true }
            .newInstance(label, owner)

    private fun render(records: List<LogRecord>): String = synchronized(records) {
        records.joinToString("\n") { "${it.level} ${it.message}" + (it.thrown?.let { e -> "\n  $e" } ?: "") }
    }

    private companion object {
        /** What a stand-in returns when it has nothing to say: another stand-in for an interface, so calls chain. */
        fun default(type: Class<*>): Any? = when {
            type == Void.TYPE -> null
            type == java.lang.Boolean.TYPE -> false
            type == Integer.TYPE -> 0
            type == java.lang.Long.TYPE -> 0L
            type == java.lang.Double.TYPE -> 0.0
            type == java.lang.Float.TYPE -> 0f
            type.isPrimitive -> 0.toByte()
            type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { _, method, _ -> default(method.returnType) })
            else -> null
        }
    }
}
