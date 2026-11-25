package co.xenastudios.core.velocity

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandNode
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.command.BrigadierTree
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import kotlin.concurrent.Volatile
import com.velocitypowered.api.command.CommandSource as VelocitySource

/**
 * A plugin's commands on Velocity's Brigadier (DECISIONS.md, "The proxy platforms"), built by
 * core-jvm's [BrigadierTree] as on Fabric: a literal per node and a string per argument with our
 * suggestions, each node needing its permission, so players are sent only the commands they may
 * use, and every node handing the whole line to the [CommandDispatcher]. Each label of a root,
 * its name and every alias, is a Velocity command of its own, so a reload re-registers only the
 * roots whose shape changed and unregisters the rest; a label another plugin already has is ours
 * as `/<plugin id>:<label>` instead, with a warning.
 *
 * Velocity runs commands on its own pool; ours run on the plugin's thread ([Where.Global]),
 * where its state lives, and reply from there. Suggestions are answered on Velocity's thread.
 */
public class VelocityCommands internal constructor(
    private val plugin: Any,
    private val server: ProxyServer,
    private val text: VelocityText,
    private val permissions: VelocityPermissions,
    private val scheduler: Scheduler,
    private val log: Log,
    private val prefix: String,
) : CommandRegistrar {

    /** What sources are told after a command; set it when the plugin's translations are loaded. */
    @Volatile
    public var feedback: CommandFeedback = CommandFeedback.ENGLISH

    @Volatile
    private var dispatcher: CommandDispatcher? = null

    private val tree = BrigadierTree<VelocitySource>(
        wrap = ::Source,
        dispatcher = { dispatcher ?: error("no commands registered") },
        feedback = { feedback },
        runOn = { work -> scheduler.run(Where.Global) { work() } },
    )

    // Each root's registered labels, by the root's name.
    private val registered = LinkedHashMap<String, Root>()

    private class Root(val node: CommandNode, val metas: List<CommandMeta>)

    override fun sync(dispatcher: CommandDispatcher): List<String> = synchronized(this) {
        this.dispatcher = dispatcher
        val warnings = ArrayList<String>()
        val wanted = dispatcher.tree.roots.associateBy { it.name }
        val manager = server.commandManager
        for ((name, root) in registered.entries.toList()) {
            val node = wanted[name]
            if (node == null || !sameShape(root.node, node)) {
                root.metas.forEach(manager::unregister)
                registered.remove(name)
            }
        }
        for ((name, node) in wanted) {
            if (name in registered) continue
            val metas = ArrayList<CommandMeta>()
            for (label in listOf(name) + node.aliases) {
                val free = !manager.hasCommand(label)
                val used = if (free) label else "$prefix:$label"
                if (!free) warnings += "/$label is another plugin's, so ${prefix}'s is /$used"
                val meta = manager.metaBuilder(used).plugin(plugin).build()
                manager.register(meta, BrigadierCommand(tree.build(node, used)))
                metas += meta
            }
            registered[name] = Root(node, metas)
        }
        warnings
    }

    override fun clear() {
        synchronized(this) {
            for (root in registered.values) root.metas.forEach(server.commandManager::unregister)
            registered.clear()
        }
    }

    // What Velocity was given: a root whose aliases, permission, arguments or children changed is
    // built again. The nodes' callbacks read the newest dispatcher, so the rest stay.
    private fun sameShape(old: CommandNode, new: CommandNode): Boolean =
        old.aliases == new.aliases && old.permission?.node == new.permission?.node && shape(old) == shape(new)

    private fun shape(node: CommandNode): String = buildString {
        append(node.name).append(node.permission?.node).append(node.arguments.map { it.name })
        node.children.forEach { append('(').append(shape(it)).append(')') }
    }

    /** Who ran a command, as the dispatcher sees them. */
    internal inner class Source(private val source: VelocitySource) : CommandSource {
        override val name: String get() = (source as? Player)?.username ?: "CONSOLE"
        override val player: PlayerRef? = (source as? Player)?.uniqueId?.toPlayerRef()

        override fun hasPermission(node: String): Boolean = permissions.has(source, node)

        override fun reply(message: Component) {
            text.send(source, message)
        }
    }

}
