package co.xenastudios.core.common.permission

import kotlin.concurrent.Volatile

/**
 * Answers a permission the way Bukkit does, on a platform where permissions are only a check:
 * Fabric, Velocity and BungeeCord have no registry to declare nodes in, their defaults or their
 * children, so the plugin keeps its own declarations here ([sync]) and applies them around the
 * permission plugin's answer.
 *
 * For a node, in order:
 * 1. what the permission plugin says, if it says anything: LuckPerms through
 *    fabric-permissions-api on Fabric, through Velocity's permission API there;
 * 2. a declared parent the source has, which grants or takes the node away ([Permission.children]);
 * 3. the node's declared default, by whether the source is an operator. An undeclared node is
 *    for operators, as on Bukkit. A proxy has no operators: there the console is one, and
 *    players aren't.
 *
 * It knows no platform, so these rules are tested in common code; each platform's adapter
 * asks its permission plugin and its notion of an operator.
 */
public class PermissionResolver : PermissionRegistrar {

    private class Declared(
        val defaults: Map<String, PermissionDefault>,
        // A child node to the parents that set it, and what each sets it to.
        val parents: Map<String, List<Pair<String, Boolean>>>,
    )

    // Replaced whole on sync, so checks read it without a lock.
    @Volatile
    private var declared = Declared(emptyMap(), emptyMap())

    override fun sync(permissions: List<Permission>) {
        val parents = HashMap<String, MutableList<Pair<String, Boolean>>>()
        for (permission in permissions) {
            for ((child, value) in permission.children) parents.getOrPut(child) { ArrayList(1) } += permission.node to value
        }
        declared = Declared(permissions.associate { it.node to it.default }, parents)
    }

    /**
     * Whether the plugin declared [node], or a parent of it: what it has a say in, on a platform
     * where every plugin's checks pass through one event (BungeeCord's `PermissionCheckEvent`).
     */
    public fun declares(node: String): Boolean = declared.let { node in it.defaults || node in it.parents }

    /**
     * Whether a source has [node]: [explicit] is the permission mod's answer for a node, `null`
     * when it has none, and [operator] whether the source is an operator.
     */
    public fun has(node: String, operator: Boolean, explicit: (String) -> Boolean?): Boolean =
        resolve(node, operator, explicit, declared, 0)

    private fun resolve(node: String, operator: Boolean, explicit: (String) -> Boolean?, declared: Declared, depth: Int): Boolean {
        explicit(node)?.let { return it }
        // Children can loop (a grants b, b grants a); Bukkit stops too, at the first repeat.
        if (depth < MAX_DEPTH) {
            declared.parents[node]?.let { parents ->
                for ((parent, value) in parents) {
                    if (resolve(parent, operator, explicit, declared, depth + 1)) return value
                }
            }
        }
        return when (declared.defaults[node] ?: PermissionDefault.OP) {
            PermissionDefault.OP -> operator
            PermissionDefault.NOT_OP -> !operator
            PermissionDefault.EVERYONE -> true
            PermissionDefault.NOBODY -> false
        }
    }

    private companion object {
        const val MAX_DEPTH = 8
    }
}
