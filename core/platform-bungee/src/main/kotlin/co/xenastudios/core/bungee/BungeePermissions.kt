package co.xenastudios.core.bungee

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.permission.PermissionResolver
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PermissionCheckEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority

/**
 * Permissions on BungeeCord, where a check is a yes or no: its groups in `config.yml`, then
 * whatever a permission plugin says in `PermissionCheckEvent` (LuckPerms, at its own priority).
 * BungeeCord has no defaults, so the plugin's declared defaults and children
 * ([PermissionResolver]) go in at the event's [EventPriority.LOWEST], before LuckPerms: a node
 * for everyone is a player's unless a permission plugin takes it away, and one for operators is
 * the console's alone (BungeeCord has no operators) until a group or LuckPerms grants it. Only
 * the plugin's own nodes are touched.
 *
 * [sync] is the plugin's [PermissionRegistrar]: BungeeCord has no registry for nodes.
 */
public class BungeePermissions internal constructor(private val platform: BungeePlatform) : PermissionRegistrar, Listener {
    private val resolver = PermissionResolver()

    override fun sync(permissions: List<Permission>) {
        resolver.sync(permissions)
    }

    /** Applies the declared defaults to a player's check of one of the plugin's nodes. */
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onPermissionCheck(event: PermissionCheckEvent) {
        val node = event.permission ?: return
        val player = event.sender as? ProxiedPlayer ?: return
        if (!resolver.declares(node)) return
        val granted = player.permissions
        val answer = resolver.has(node, operator = false) { asked -> if (asked == node && event.hasPermission() || asked in granted) true else null }
        event.setHasPermission(answer)
    }

    internal fun start() {
        platform.server.pluginManager.registerListener(platform.plugin, this)
    }

    internal fun close() {
        platform.server.pluginManager.unregisterListener(this)
    }
}
