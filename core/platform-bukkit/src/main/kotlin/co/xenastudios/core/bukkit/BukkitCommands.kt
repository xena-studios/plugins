package co.xenastudios.core.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandNode
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.text.Component
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.command.SimpleCommandMap
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import kotlin.concurrent.Volatile

/**
 * A plugin's commands on Bukkit's command map, registered at runtime as today's core does
 * (DECISIONS.md, "Commands are registered at runtime"), so none are in `plugin.yml`: one Bukkit
 * command per root of the tree, under its name and aliases and `<plugin>:<name>`, that hands
 * the whole line to the [CommandDispatcher] and completes it with the same. Each root carries its
 * permission, so 1.13+ clients aren't sent commands they can't use. A command of the server's
 * own by the same name (vanilla `/gamemode`, Bukkit's `/version`) gives way to ours and comes
 * back when ours is turned off, as today's core does; another plugin's is never replaced.
 *
 * Bukkit's command map is reached the one way every version allows: `getCommandMap()` on the
 * server object, and its map of names (`getKnownCommands()` from 1.13, the field before) to
 * unregister, each looked up once. After a reload changes the commands, 1.13+ servers rebuild
 * the tree they send clients (`syncCommands`); at startup the server does that itself, once
 * every plugin is enabled.
 *
 * Results are shown in [feedback]: English, or the plugin's lang files once it sets [CommandFeedback.of].
 */
public class BukkitCommands internal constructor(
    private val plugin: Plugin,
    private val text: BukkitText,
    private val log: Log,
    private val map: CommandMap,
    private val known: MutableMap<String, Command>,
    private val refresh: (() -> Unit)?,
) : CommandRegistrar {

    /** What sources are told after a command; set it when the plugin's translations are loaded. */
    @Volatile
    public var feedback: CommandFeedback = CommandFeedback.ENGLISH

    private val prefix = plugin.name.lowercase()
    private val registered = LinkedHashMap<String, Root>()

    // The server's own commands ours took a label from, to give back when ours goes.
    private val displaced = HashMap<String, Command>()
    private var synced = false

    override fun sync(dispatcher: CommandDispatcher): List<String> = synchronized(this) {
        val warnings = ArrayList<String>()
        val wanted = dispatcher.tree.roots.associateBy { it.name }
        var changed = false
        for ((name, root) in registered.entries.toList()) {
            val node = wanted[name]
            if (node == null || !root.sameShape(node)) {
                unregister(root)
                registered.remove(name)
                changed = true
            }
        }
        for ((name, node) in wanted) {
            val root = registered[name]
            if (root != null) {
                root.dispatcher = dispatcher
                continue
            }
            val fresh = Root(node, dispatcher)
            // The server's own command of a name (vanilla /gamemode, Bukkit's /version) gives way
            // to ours, and comes back when ours goes; another plugin's never does.
            for (label in listOf(name) + node.aliases) {
                val existing = known[label] ?: continue
                if (existing !is PluginIdentifiableCommand && existing !is Root) {
                    displaced.putIfAbsent(label, existing)
                    known.remove(label)
                }
            }
            if (!map.register(name, prefix, fresh)) warnings += "/$name is another plugin's, so ${plugin.name}'s is /$prefix:$name"
            for (alias in node.aliases) {
                if (known[alias] !== fresh) warnings += "/$alias is another plugin's, so ${plugin.name}'s is /$prefix:$alias"
            }
            registered[name] = fresh
            changed = true
        }
        if (changed && synced) refreshClients()
        synced = true
        warnings
    }

    override fun clear() {
        synchronized(this) {
            registered.values.forEach(::unregister)
            registered.clear()
        }
    }

    private fun unregister(root: Root) {
        // Key by key: Paper's map forwards to Brigadier, and its entry views may not remove.
        for (label in known.filterValues { it === root }.keys) known.remove(label)
        root.unregister(map)
        // A server command ours displaced comes back once no command of ours holds its label.
        for (label in root.labels) {
            val server = displaced[label] ?: continue
            if (!known.containsKey(label)) {
                known[label] = server
                displaced.remove(label)
            }
        }
    }

    private fun refreshClients() {
        val refresh = refresh ?: return
        try {
            refresh()
        } catch (e: Exception) {
            // Commands still run; clients see the old list until they rejoin.
            log.warn("Couldn't send players the new commands: ${e.cause ?: e}")
        }
    }

    /** Who ran a command, as the dispatcher sees them. */
    internal inner class Source(private val sender: CommandSender) : CommandSource {
        override val name: String get() = sender.name
        override val player: PlayerRef? = (sender as? Player)?.ref

        override fun hasPermission(node: String): Boolean = sender.hasPermission(node)

        override fun reply(message: Component) {
            text.send(sender, message)
        }
    }

    /** One root of the tree as a Bukkit command, answered by whichever dispatcher the last sync gave. */
    private inner class Root(private val node: CommandNode, @Volatile var dispatcher: CommandDispatcher) :
        Command(node.name, node.description, node.usage, node.aliases) {

        init {
            permission = node.permission?.node
        }

        /** Its name and aliases, as registered. */
        val labels: List<String> = listOf(node.name) + node.aliases

        // What Bukkit was given: a root whose name, aliases or permission changed is registered again.
        fun sameShape(other: CommandNode): Boolean =
            other.aliases == node.aliases && other.permission?.node == node.permission?.node && other.description == node.description

        override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
            val source = Source(sender)
            val result = dispatcher.dispatch(source, line(label, args))
            feedback.message(result, source)?.let(source::reply)
            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> =
            dispatcher.suggest(Source(sender), line(alias, args))

        private fun line(label: String, args: Array<out String>): String =
            if (args.isEmpty()) label else label + " " + args.joinToString(" ")
    }

    internal companion object {
        /** The registrar on [server]'s command map, or why it can't be reached. */
        fun create(plugin: Plugin, server: Server, text: BukkitText, log: Log, refreshes: Boolean): BukkitCommands {
            val map = server.javaClass.getMethod("getCommandMap").invoke(server) as CommandMap
            return BukkitCommands(plugin, text, log, map, knownCommands(map), if (refreshes) refresher(server) else null)
        }

        @Suppress("UNCHECKED_CAST")
        internal fun knownCommands(map: CommandMap): MutableMap<String, Command> {
            val method = map.javaClass.methods.firstOrNull { it.name == "getKnownCommands" && it.parameterTypes.isEmpty() }
            if (method != null) return method.invoke(map) as MutableMap<String, Command>
            // Before 1.13 the map is only a protected field.
            val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
            field.isAccessible = true
            return field.get(map) as MutableMap<String, Command>
        }

        // CraftServer.syncCommands(), 1.13 on: rebuilds the tree clients are sent, and sends it.
        private fun refresher(server: Server): (() -> Unit)? {
            val sync: Method = server.javaClass.methods.firstOrNull { it.name == "syncCommands" && it.parameterTypes.isEmpty() } ?: return null
            return { sync.invoke(server) }
        }
    }
}
