package co.xenastudios.core.velocity

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.permission.PermissionResolver
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.permission.Tristate
import com.velocitypowered.api.proxy.ConsoleCommandSource

/**
 * Permissions on Velocity: its permission API answers first (LuckPerms through it, or whatever
 * permission plugin is installed), and where it says nothing, `UNDEFINED`, the plugin's declared
 * defaults and children decide ([PermissionResolver]). Velocity has no operators, so a node for
 * operators (`op`, every undeclared node) is the console's alone until a permission plugin
 * grants it; one for everyone is everyone's without one.
 *
 * [sync] is the plugin's [PermissionRegistrar]: Velocity has no registry for nodes, so it only
 * keeps the declarations.
 */
public class VelocityPermissions internal constructor() : PermissionRegistrar {
    private val resolver = PermissionResolver()

    override fun sync(permissions: List<Permission>) {
        resolver.sync(permissions)
    }

    /** Whether [source] has [node]. Any thread. */
    public fun has(source: CommandSource, node: String): Boolean =
        resolver.has(node, operator = source is ConsoleCommandSource) { asked ->
            when (source.getPermissionValue(asked)) {
                Tristate.TRUE -> true
                Tristate.FALSE -> false
                else -> null
            }
        }
}
