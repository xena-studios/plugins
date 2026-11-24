package co.xenastudios.core.fabric

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionRegistrar
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent
import net.fabricmc.fabric.api.util.TriState
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

/**
 * Permissions on Fabric: whatever permission mod is installed answers through
 * fabric-permissions-api (LuckPerms does), and when none says anything, the plugin's declared
 * defaults apply by operator level, as [PermissionResolver] describes. With no permission mod at
 * all, that's vanilla's ops: an operator is a source with level 2 (game masters, the level
 * vanilla's cheat commands need), and the console has every level.
 *
 * [sync] is the plugin's [PermissionRegistrar]: there's nothing to register on Fabric, so it only
 * keeps the declarations.
 */
public class FabricPermissions : PermissionRegistrar {
    private val resolver = PermissionResolver()

    override fun sync(permissions: List<Permission>) {
        resolver.sync(permissions)
    }

    /** Whether [source] has [node]. On the server thread, as every command source's check is. */
    public fun has(source: CommandSourceStack, node: String): Boolean =
        resolver.has(node, isOperator(source)) { asked ->
            when (PermissionCheckEvent.EVENT.invoker().onPermissionCheck(source, asked)) {
                TriState.TRUE -> true
                TriState.FALSE -> false
                else -> null
            }
        }

    /** Whether [player] has [node], as when they run a command. */
    public fun has(player: ServerPlayer, node: String): Boolean = has(player.createCommandSourceStack(), node)

    public companion object {
        /** The operator level the declared `op` default means: 2, vanilla's game masters. */
        public const val OPERATOR_LEVEL: Int = 2

        /** Whether [source] is an operator for the plugin's defaults. */
        public fun isOperator(source: CommandSourceStack): Boolean =
            //? if permission_sets {
            source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
            //?} else
            //source.hasPermission(OPERATOR_LEVEL)
    }
}
