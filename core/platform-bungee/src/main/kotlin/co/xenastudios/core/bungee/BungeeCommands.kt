package co.xenastudios.core.bungee

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandNode
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.TabExecutor
import kotlin.concurrent.Volatile

/**
 * A plugin's commands on BungeeCord, registered at runtime: one BungeeCord command per root of
 * the tree, under its name and aliases, that hands the whole line to the [CommandDispatcher] and
 * completes it with the same ([TabExecutor]). Each carries its root's permission, which BungeeCord
 * checks before it sends a 1.13+ client the command. A name another plugin has is ours as
 * `/<plugin id>:<name>` instead, with a warning; a reload re-registers only the roots whose name,
 * aliases or permission changed.
 *
 * BungeeCord runs commands on its network threads; ours run on the plugin's thread
 * ([Where.Global]), where its state lives, and reply from there. Completions are answered on
 * BungeeCord's thread.
 */
public class BungeeCommands internal constructor(private val platform: BungeePlatform) : CommandRegistrar {

    /** What sources are told after a command; set it when the plugin's translations are loaded. */
    @Volatile
    public var feedback: CommandFeedback = CommandFeedback.ENGLISH

    private val registered = LinkedHashMap<String, Root>()

    override fun sync(dispatcher: CommandDispatcher): List<String> = synchronized(this) {
        val warnings = ArrayList<String>()
        val manager = platform.server.pluginManager
        val wanted = dispatcher.tree.roots.associateBy { it.name }
        for ((name, root) in registered.entries.toList()) {
            val node = wanted[name]
            if (node == null || !root.sameShape(node)) {
                manager.unregisterCommand(root)
                registered.remove(name)
            }
        }
        // Other plugins' labels, read once: ours are gone from it above, or stay as they are.
        val taken = manager.commands.filter { (_, command) -> command !is Root }.map { it.key.lowercase() }.toSet()
        for ((name, node) in wanted) {
            val root = registered[name]
            if (root != null) {
                root.dispatcher = dispatcher
                continue
            }
            val labels = (listOf(name) + node.aliases).map { label ->
                if (label !in taken) label else "${platform.id}:$label".also { warnings += "/$label is another plugin's, so ${platform.displayName}'s is /$it" }
            }
            val fresh = Root(node, dispatcher, labels)
            manager.registerCommand(platform.plugin, fresh)
            registered[name] = fresh
        }
        warnings
    }

    override fun clear() {
        synchronized(this) {
            registered.values.forEach(platform.server.pluginManager::unregisterCommand)
            registered.clear()
        }
    }

    /** Who ran a command, as the dispatcher sees them. */
    internal inner class Source(private val sender: CommandSender) : CommandSource {
        override val name: String get() = sender.name
        override val player: PlayerRef? = (sender as? ProxiedPlayer)?.uniqueId?.toPlayerRef()

        override fun hasPermission(node: String): Boolean = sender.hasPermission(node)

        override fun reply(message: Component) {
            platform.text.send(sender, message)
        }
    }

    /** One root of the tree as a BungeeCord command, answered by whichever dispatcher the last sync gave. */
    private inner class Root(private val node: CommandNode, @Volatile var dispatcher: CommandDispatcher, labels: List<String>) :
        Command(labels.first(), node.permission?.node, *labels.drop(1).toTypedArray()), TabExecutor {

        fun sameShape(other: CommandNode): Boolean = other.aliases == node.aliases && other.permission?.node == node.permission?.node

        override fun execute(sender: CommandSender, args: Array<out String>) {
            val source = Source(sender)
            val line = line(args)
            platform.scheduler.run(Where.Global) {
                feedback.message(dispatcher.dispatch(source, line), source)?.let(source::reply)
            }
        }

        override fun onTabComplete(sender: CommandSender, args: Array<out String>): Iterable<String> =
            dispatcher.suggest(Source(sender), line(args))

        // The root's own name: BungeeCord doesn't say which alias was typed, and every one runs the same.
        private fun line(args: Array<out String>): String = if (args.isEmpty()) node.name else node.name + " " + args.joinToString(" ")
    }
}
