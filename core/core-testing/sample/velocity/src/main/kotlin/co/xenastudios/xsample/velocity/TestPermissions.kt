package co.xenastudios.xsample.velocity

import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.permission.PermissionsSetupEvent
import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.permission.Tristate
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer

/**
 * A stand-in for LuckPerms in the boot tests, never in a real plugin: Velocity has no permissions
 * of its own, so `-Dxena.test.grants=Joiner=xsample.*,xsample.admin` (players `;` apart, as
 * scripts/boot-proxy.sh's `--grant` sets it) gives those players those nodes through Velocity's
 * permission API, the way LuckPerms answers it: true for those, nothing for the rest. The platform
 * then works out their children (`xsample.admin.debug`) itself, as it would with LuckPerms.
 */
internal object TestPermissions {
    fun install(server: ProxyServer, plugin: Any) {
        val grants = System.getProperty("xena.test.grants")?.split(';')?.mapNotNull { entry ->
            val (name, nodes) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            name.trim() to nodes.split(',').map { it.trim() }.toSet()
        }?.toMap() ?: return
        server.eventManager.register(plugin, PermissionsSetupEvent::class.java, EventHandler { event ->
            val player = event.subject as? Player ?: return@EventHandler
            val granted = grants[player.username] ?: return@EventHandler
            val fallback = event.provider
            event.provider = PermissionProvider { subject ->
                val previous = fallback.createFunction(subject)
                PermissionFunction { node -> if (node in granted) Tristate.TRUE else previous.getPermissionValue(node) }
            }
        })
    }
}
