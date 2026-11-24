package co.xenastudios.core.bukkit

import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.SimpleCommandMap
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.SimpleServicesManager
import org.bukkit.scheduler.BukkitScheduler
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.logging.Logger
import kotlin.concurrent.Volatile

/**
 * An implementation of interface [T] that answers the methods in [answers] (by name) and
 * throws on anything else, so a test sees at once when the adapter calls something it didn't
 * expect. `equals`, `hashCode` and `toString` are the object's own.
 */
internal inline fun <reified T : Any> fake(name: String, answers: Map<String, (Array<out Any?>) -> Any?>): T {
    val type = T::class.java
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        val given = args ?: emptyArray()
        when (method.name) {
            "equals" -> proxy === given[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> name
            else -> answers[method.name]?.invoke(given) ?: if (method.name in answers) null else throw UnsupportedOperationException("$name.${method.name}")
        }
    } as T
}

/**
 * The Bukkit server the tests run against: one, since Bukkit's static `Bukkit.getServer()` can
 * be set only once and Bukkit's own classes (a `Permission`'s constructor) call it. Each test
 * sets the parts it needs with [reset].
 */
internal object FakeServer {
    val logger: Logger = Logger.getLogger("FakeServer")

    @Volatile var primaryThread: Thread? = null
    @Volatile var scheduler: BukkitScheduler? = null
    @Volatile var players: List<Player> = emptyList()
    lateinit var commandMap: SimpleCommandMap
    lateinit var pluginManager: SimplePluginManager
    lateinit var services: SimpleServicesManager
    val console: Inbox = Inbox()
    private val consoleSender: ConsoleCommandSender = fake(
        "CONSOLE",
        mapOf("getName" to { _ -> "CONSOLE" }, "sendMessage" to { args -> console.received += args[0]!! }),
    )

    val server: Server = fake(
        "Server",
        mapOf(
            "getName" to { _ -> "CraftBukkit" },
            "getVersion" to { _ -> "test" },
            "getBukkitVersion" to { _ -> "1.8.8-R0.1-SNAPSHOT" },
            "getLogger" to { _ -> logger },
            "isPrimaryThread" to { _ -> Thread.currentThread() === primaryThread },
            "getScheduler" to { _ -> scheduler ?: throw UnsupportedOperationException("no scheduler in this test") },
            "getPluginManager" to { _ -> pluginManager },
            "getCommandMap" to { _ -> commandMap },
            "getCommandAliases" to { _ -> emptyMap<String, Array<String>>() },
            "getOnlinePlayers" to { _ -> players },
            "getPlayerExact" to { args -> players.firstOrNull { it.name.equals(args[0] as String, ignoreCase = true) } },
            "getPlayer" to { args -> players.firstOrNull { it.uniqueId == args[0] as? UUID } },
            "getWorlds" to { _ -> emptyList<Any>() },
            "getWorld" to { _ -> null },
            "getServicesManager" to { _ -> services },
            "getConsoleSender" to { _ -> consoleSender },
        ),
    )

    init {
        Bukkit.setServer(server)
        reset()
    }

    /** A fresh command map and plugin manager, the test's thread as the main thread, and no players or scheduler. */
    fun reset() {
        primaryThread = Thread.currentThread()
        scheduler = null
        players = emptyList()
        commandMap = SimpleCommandMap(server)
        pluginManager = SimplePluginManager(server, commandMap)
        services = SimpleServicesManager()
        console.received.clear()
    }

    /** A plugin called [name], enabled, on this server. */
    fun plugin(name: String = "XSample"): Plugin = fake(
        name,
        mapOf(
            "getName" to { _ -> name },
            "getServer" to { _ -> server },
            "isEnabled" to { _ -> true },
            "getLogger" to { _ -> logger },
        ),
    )

    /** The plugin manager, as the interface the adapter takes. */
    val plugins: PluginManager get() = pluginManager
}

/** What a [fakePlayer] was sent: `§` strings, and Spigot's components. */
internal class Inbox {
    val received: MutableList<Any> = java.util.Collections.synchronizedList(ArrayList())
}

/** An online player called [name], who sees everyone [canSee] allows and sent [locale]. */
internal fun fakePlayer(
    name: String,
    inbox: Inbox = Inbox(),
    locale: String? = "en_us",
    canSee: (Player) -> Boolean = { true },
    permissions: Set<String> = emptySet(),
): Player {
    val id = UUID.nameUUIDFromBytes(name.toByteArray())
    val spigot = object : Player.Spigot() {
        override fun getLocale(): String? = locale

        override fun sendMessage(component: net.md_5.bungee.api.chat.BaseComponent) {
            inbox.received += component
        }

        override fun sendMessage(vararg components: net.md_5.bungee.api.chat.BaseComponent) {
            inbox.received.add(components.toList())
        }
    }
    return fake(
        name,
        mapOf(
            "getName" to { _ -> name },
            "getUniqueId" to { _ -> id },
            "spigot" to { _ -> spigot },
            "canSee" to { args -> canSee(args[0] as Player) },
            "sendMessage" to { args -> inbox.received += args[0]!! },
            "hasPermission" to { args -> args[0] as String in permissions },
            "isValid" to { _ -> true },
        ),
    )
}
