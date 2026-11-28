package co.xenastudios.core.sponge

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.permission.PermissionResolver
import org.spongepowered.api.SystemSubject
import org.spongepowered.api.service.permission.Subject
import org.spongepowered.api.util.Tristate

/**
 * Permissions on Sponge: the server's permission service answers (LuckPerms, or Sponge's own,
 * which gives operators everything), and when it says nothing about a node, the plugin's
 * declared defaults apply by whether the subject is an operator, as [PermissionResolver]
 * describes. Sponge has no API for operators, so a player is one when the service grants them
 * vanilla's level-2 `/gamemode` (`minecraft.command.gamemode`), which is what an operator is to
 * Sponge's own service; the console is always one.
 *
 * [sync] is the plugin's [PermissionRegistrar]: the declarations are only kept here.
 */
public class SpongePermissions : PermissionRegistrar {
    private val resolver = PermissionResolver()

    override fun sync(permissions: List<Permission>) {
        resolver.sync(permissions)
    }

    /** Whether [subject] has [node]. */
    public fun has(subject: Subject, node: String): Boolean =
        resolver.has(node, isOperator(subject)) { asked ->
            when (subject.permissionValue(asked)) {
                Tristate.TRUE -> true
                Tristate.FALSE -> false
                else -> null
            }
        }

    public companion object {
        /** What an operator has on Sponge without a permission plugin: vanilla's level-2 commands. */
        public const val OPERATOR_NODE: String = "minecraft.command.gamemode"

        /** Whether [subject] is an operator for the plugin's defaults. */
        public fun isOperator(subject: Subject): Boolean = subject is SystemSubject || subject.hasPermission(OPERATOR_NODE)
    }
}
