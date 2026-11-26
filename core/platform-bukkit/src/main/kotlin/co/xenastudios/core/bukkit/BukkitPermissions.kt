package co.xenastudios.core.bukkit

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionRegistrar
import org.bukkit.plugin.PluginManager
import java.lang.reflect.Method
import org.bukkit.permissions.Permission as BukkitPermission
import org.bukkit.permissions.PermissionDefault as BukkitDefault

/**
 * A plugin's permissions in Bukkit's plugin manager, declared at runtime rather than in
 * `plugin.yml`, so a reload that changes a default or a child applies without a restart. Bukkit
 * recalculates who has what when one is added or removed. LuckPerms and the like read them
 * from there for their editors.
 *
 * Bukkit works out everyone's permissions again on each one added (the console's included, so
 * at startup too): a plugin with a hundred nodes paid that a hundred times. Paper's
 * `addPermissions` adds them all and works it out once; other servers add them one by one.
 */
internal class BukkitPermissions(private val plugins: PluginManager) : PermissionRegistrar {

    // What this plugin added, by node: only these are ever changed or removed.
    private val declared = LinkedHashMap<String, BukkitPermission>()

    override fun sync(permissions: List<Permission>): Unit = synchronized(this) {
        val wanted = permissions.associateBy { it.node }
        for ((node, added) in declared.entries.toList()) {
            val permission = wanted[node]
            if (permission == null || !same(added, permission)) {
                plugins.removePermission(added)
                declared.remove(node)
            }
        }
        val adding = ArrayList<BukkitPermission>()
        // Bukkit's names ignore case, so a node twice in two cases is added once, as before.
        val names = HashSet<String>()
        for ((node, permission) in wanted) {
            if (node in declared) continue
            // Another plugin's node stays as that plugin has it.
            if (plugins.getPermission(node) != null || !names.add(node.lowercase())) continue
            val added = BukkitPermission(node, permission.description, default(permission.default), LinkedHashMap(permission.children))
            adding += added
            declared[node] = added
        }
        val batch = addAll
        if (batch != null && adding.size > 1) {
            batch.invoke(plugins, adding)
        } else {
            adding.forEach(plugins::addPermission)
        }
    }

    private companion object {
        // Paper's PluginManager.addPermissions(List); Spigot has none.
        val addAll: Method? = try {
            PluginManager::class.java.getMethod("addPermissions", List::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
    }

    private fun same(added: BukkitPermission, permission: Permission): Boolean =
        added.description == permission.description && added.default == default(permission.default) && added.children == permission.children

    private fun default(default: PermissionDefault): BukkitDefault = when (default) {
        PermissionDefault.OP -> BukkitDefault.OP
        PermissionDefault.EVERYONE -> BukkitDefault.TRUE
        PermissionDefault.NOBODY -> BukkitDefault.FALSE
        PermissionDefault.NOT_OP -> BukkitDefault.NOT_OP
    }
}
