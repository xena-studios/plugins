package co.xenastudios.core.neoforge

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.permission.PermissionResolver
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.server.permission.PermissionAPI
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes

/**
 * Permissions on NeoForge: whatever permission mod is installed answers through NeoForge's
 * permission API (LuckPerms does), and when none says anything, the plugin's declared defaults
 * apply by operator level, as [PermissionResolver] describes. With no permission mod at all,
 * that's vanilla's ops: an operator is a source with level 2 (game masters, the level vanilla's
 * cheat commands need), and the console has every level.
 *
 * NeoForge's API answers only for nodes registered as the server starts (its
 * `PermissionGatherEvent.Nodes`): each declared node is registered then, with the plugin's own
 * resolution (its parents, then its default) as the node's default, which NeoForge's handler
 * and LuckPerms' use when nothing is set. A node declared later (a reload that adds one) isn't
 * known to the permission mod until the next start, and gets its declared default meanwhile.
 * The console and command blocks aren't players, so for them only operator levels count.
 *
 * [sync] is the plugin's [PermissionRegistrar]: it keeps the declarations.
 */
public class NeoForgePermissions : PermissionRegistrar {
    private val resolver = PermissionResolver()

    // The registered nodes by name, once the server gathered them; replaced whole.
    @Volatile
    private var nodes: Map<String, PermissionNode<Boolean>> = emptyMap()

    @Volatile
    private var declared: List<Permission> = emptyList()

    // A node's default resolution asks the permission mod about its parents, whose defaults ask
    // in turn: this stops a loop of parents (a grants b, b grants a) as PermissionResolver does.
    private val depth = ThreadLocal.withInitial { 0 }

    init {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PermissionGatherEvent.Nodes::class.java) { event ->
            // Another Xena mod may declare the same node (a shared one); NeoForge refuses a repeat.
            val taken = event.nodes.mapTo(HashSet()) { it.nodeName }
            val made = LinkedHashMap<String, PermissionNode<Boolean>>()
            for (permission in declared) {
                val node = node(permission.node) ?: continue
                if (taken.add(node.nodeName)) event.addNodes(node)
                made[permission.node] = node
            }
            nodes = made
        }
    }

    override fun sync(permissions: List<Permission>) {
        declared = permissions
        resolver.sync(permissions)
    }

    /** Whether [source] has [node]. On the server thread, as every command source's check is. */
    public fun has(source: CommandSourceStack, node: String): Boolean {
        val player = source.player
        return if (player != null) has(player, node) else resolver.has(node, isOperator(source)) { null }
    }

    /** Whether [player] has [node], as when they run a command. */
    public fun has(player: ServerPlayer, node: String): Boolean {
        val registered = nodes[node] ?: return resolve(player, node)
        return PermissionAPI.getPermission(player, registered)
    }

    // The plugin's own answer for [player]: a parent the permission mod grants, then the default.
    private fun resolve(player: ServerPlayer, node: String): Boolean {
        val level = depth.get()
        if (level > MAX_DEPTH) return false
        depth.set(level + 1)
        try {
            return resolver.has(node, isOperator(player.createCommandSourceStack())) { asked ->
                if (asked == node) null else nodes[asked]?.let { PermissionAPI.getPermission(player, it) }
            }
        } finally {
            depth.set(level)
        }
    }

    // NeoForge's node for one of ours: `xutilities.admin.reload` is the mod `xutilities`'s
    // `admin.reload`. One NeoForge couldn't name only gets its declared default.
    private fun node(name: String): PermissionNode<Boolean>? {
        val namespace = name.substringBefore('.', "")
        val path = name.substringAfter('.', "")
        if (namespace.isEmpty() || path.isEmpty() || !VALID.matches(namespace) || !VALID_PATH.matches(path)) return null
        return PermissionNode(namespace, path, PermissionTypes.BOOLEAN, { player, _, _ -> player != null && resolve(player, name) })
    }

    public companion object {
        /** The operator level the declared `op` default means: 2, vanilla's game masters. */
        public const val OPERATOR_LEVEL: Int = 2

        private const val MAX_DEPTH = 8
        private val VALID = Regex("[a-z0-9_.-]+")
        private val VALID_PATH = Regex("[a-z0-9_./-]+")

        /** Whether [source] is an operator for the plugin's defaults. */
        public fun isOperator(source: CommandSourceStack): Boolean =
            //? if permission_sets {
            source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
            //?} else
            //source.hasPermission(OPERATOR_LEVEL)
    }
}
