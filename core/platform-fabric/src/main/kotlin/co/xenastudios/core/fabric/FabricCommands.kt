package co.xenastudios.core.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.text.Component
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer
import com.mojang.brigadier.CommandDispatcher as BrigadierDispatcher

/**
 * A plugin's commands on Fabric: registered on Minecraft's Brigadier dispatcher through Fabric
 * API's command callback, built by [BrigadierTree] so clients get the tree and our suggestions.
 *
 * Minecraft builds a new dispatcher at startup and on every `/reload`, and Fabric API calls us
 * each time; [sync] also changes the running server's dispatcher in place and sends players
 * their new command tree, so a plugin's reload applies command changes without a restart
 * (DECISIONS.md, "Commands are registered at runtime"). Brigadier can't remove a command, so
 * that one step reaches into its maps, only when a command goes away.
 *
 * Minecraft's own command of the same name (`/gamemode`, `/clear`) gives way to ours and comes
 * back when ours is turned off, as on Bukkit. A name another mod already has is left to it, with
 * a warning: Brigadier would merge the two trees, which is worse than either.
 */
public class FabricCommands(
    private val server: () -> MinecraftServer?,
    private val permissions: FabricPermissions,
    feedback: CommandFeedback = CommandFeedback.ENGLISH,
) : CommandRegistrar {

    /** What sources are told after a command; set it when the plugin's translations are loaded ([CommandFeedback.of]). */
    @Volatile
    public var feedback: CommandFeedback = feedback

    @Volatile
    private var dispatcher: CommandDispatcher? = null

    // The nodes this plugin put on the dispatcher in use, by label.
    private val registered = LinkedHashMap<String, LiteralCommandNode<CommandSourceStack>>()

    // Minecraft's own commands ours took a label from, to give back when ours goes.
    private val displaced = LinkedHashMap<String, CommandNode<CommandSourceStack>>()

    private val tree = BrigadierTree<CommandSourceStack>(
        wrap = ::source,
        dispatcher = { dispatcher ?: error("The plugin's commands ran after it was disabled") },
        feedback = { this.feedback },
    )

    init {
        // Startup, and every /reload, when Minecraft makes a new dispatcher.
        CommandRegistrationCallback.EVENT.register { brigadier, _, _ ->
            registered.clear()
            // A new dispatcher has Minecraft's commands afresh.
            displaced.clear()
            dispatcher?.let { register(brigadier, it) }
        }
    }

    override fun sync(dispatcher: CommandDispatcher): List<String> {
        this.dispatcher = dispatcher
        val running = server() ?: return emptyList()
        val brigadier = running.commands.dispatcher
        unregister(brigadier.root)
        val warnings = register(brigadier, dispatcher)
        for (player in running.playerList.players) running.commands.sendCommands(player)
        return warnings
    }

    override fun clear() {
        dispatcher = null
        server()?.let { unregister(it.commands.dispatcher.root) }
    }

    /** Our view of a Minecraft command source, for code that runs commands its own way. */
    public fun source(stack: CommandSourceStack): CommandSource = FabricCommandSource(stack, permissions)

    private fun register(brigadier: BrigadierDispatcher<CommandSourceStack>, dispatcher: CommandDispatcher): List<String> {
        val warnings = ArrayList<String>()
        for (root in dispatcher.tree.roots) {
            for (label in listOf(root.name) + root.aliases) {
                val existing = brigadier.root.getChild(label)
                if (existing != null && registered[label] !== existing && MinecraftCommands.owns(existing)) {
                    // Minecraft's own command (/gamemode, /clear) gives way to ours, and comes back when ours goes.
                    displaced.putIfAbsent(label, existing)
                    BrigadierMaps.remove(brigadier.root, label)
                } else if (existing != null) {
                    warnings += "/$label is already a command on this server, so ${root.name}'s isn't registered under that name"
                    continue
                }
                val node = tree.build(root, label)
                brigadier.root.addChild(node)
                registered[label] = node
            }
        }
        return warnings
    }

    private fun unregister(root: RootCommandNode<CommandSourceStack>) {
        for ((label, node) in registered) {
            if (root.getChild(label) === node) BrigadierMaps.remove(root, label)
        }
        registered.clear()
        // Minecraft's commands come back; sync registers ours again over the ones still wanted.
        for ((label, node) in displaced) {
            if (root.getChild(label) == null) root.addChild(node)
        }
        displaced.clear()
    }
}

/** A Minecraft command source as the core's [CommandSource]. */
internal class FabricCommandSource(
    private val stack: CommandSourceStack,
    private val permissions: FabricPermissions,
) : CommandSource {
    override val name: String get() = stack.textName

    override val player: PlayerRef? get() = stack.player?.ref

    override fun hasPermission(node: String): Boolean = permissions.has(stack, node)

    override fun reply(message: Component) {
        val text = MinecraftRenderer.draw(message)
        val server = stack.server
        if (server.isSameThread) stack.sendSystemMessage(text) else server.execute { stack.sendSystemMessage(text) }
    }
}

/**
 * Takes a command off a Brigadier node, which Brigadier has no method for: its three maps are
 * private, so this reaches in, once per command removed (on a plugin's reload, never at
 * startup or per command run). Their names haven't changed since Brigadier was published.
 */
internal object BrigadierMaps {
    private val fields by lazy {
        listOf("children", "literals", "arguments").map { name ->
            CommandNode::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
    }

    fun remove(node: CommandNode<*>, name: String) {
        for (field in fields) (field.get(node) as MutableMap<*, *>).remove(name)
    }
}

/**
 * Tells Minecraft's own commands from other mods' without a mixin: a command's code is
 * Minecraft's when the first executor in its tree is a lambda of one of Minecraft's classes
 * (`net.minecraft.server.commands.GameModeCommand$$Lambda...`, or `net.minecraft.class_...` in
 * intermediary names). A mod's command runs the mod's own code, even when it borrows
 * Minecraft's permission check.
 */
internal object MinecraftCommands {
    fun owns(node: CommandNode<*>): Boolean {
        val command = firstCommand(node, depth = 0) ?: return false
        return command.javaClass.name.startsWith("net.minecraft.")
    }

    private fun firstCommand(node: CommandNode<*>, depth: Int): Any? {
        node.command?.let { return it }
        // A command's tree is shallow; a redirect loop or a huge tree stops here.
        if (depth > 8) return null
        for (child in node.children) firstCommand(child, depth + 1)?.let { return it }
        return null
    }
}
