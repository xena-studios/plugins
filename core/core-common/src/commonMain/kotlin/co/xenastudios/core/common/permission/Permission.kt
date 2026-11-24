package co.xenastudios.core.common.permission

/**
 * A permission node a plugin declares: its [node] (`xhomes.set`), what it [description]s, who
 * has it when no permissions plugin says otherwise ([default]), and the nodes it grants (`true`)
 * or takes away (`false`) with it ([children]), as Bukkit's `plugin.yml` has them.
 *
 * Declared once in a [PermissionSet]; the same object is what a command checks, what the
 * platform registers ([PermissionRegistrar]) and what the docs list.
 */
public class Permission internal constructor(
    /** The full node: `xhomes.set`. */
    public val node: String,
    /** What having it allows, for the docs and permission plugins' editors. */
    public val description: String,
    /** Who has it without a permissions plugin. */
    public val default: PermissionDefault,
    /** The nodes it sets with it, in order: `true` grants one, `false` takes it away. */
    public val children: Map<String, Boolean>,
    /** Declared by the core for every plugin, such as `<plugin>.admin.reload`. */
    public val builtIn: Boolean,
) {
    override fun toString(): String = node

    internal companion object {
        /** Lower-case words of a-z, 0-9, - and _, joined with `.`; the last may be `*`. */
        val NODE = Regex("[a-z0-9][a-z0-9_-]*(\\.([a-z0-9][a-z0-9_-]*|\\*))*")
    }
}

/** Who has a [Permission] when no permissions plugin says otherwise: Bukkit's four. */
public enum class PermissionDefault(
    /** How `plugin.yml` and the docs write it. */
    public val id: String,
) {
    /** Operators. Most commands. */
    OP("op"),

    /** Everyone. */
    EVERYONE("everyone"),

    /** No one, until it's given. */
    NOBODY("nobody"),

    /** Everyone but operators. */
    NOT_OP("not-op"),
}

/** Something that has permissions or doesn't: a player, the console. */
public interface Permissible {
    /** Whether it has [node], as the platform's permissions say. */
    public fun hasPermission(node: String): Boolean
}

/** Whether it has [permission]; `null` is a command anyone may use. */
public fun Permissible.has(permission: Permission?): Boolean = permission == null || hasPermission(permission.node)

/**
 * A plugin's permissions, declared once as an `object`, in the order the docs list them:
 *
 * ```
 * object HomesPermissions : PermissionSet("xhomes") {
 *     val set: Permission = permission("set", "Set a home with /sethome.", PermissionDefault.EVERYONE)
 *     val others: Permission = permission("others", "Visit other players' homes.")
 *     val all: Permission = permission("*", "Every permission of xHomes.", children = grants(set, others))
 * }
 * ```
 *
 * Every node starts with the plugin's id. The admin subcommands' permissions are the core's
 * ([co.xenastudios.core.common.command.AdminCommands.permissions]).
 */
public abstract class PermissionSet(
    /** The plugin's id, which every node starts with: `xhomes`. */
    public val pluginId: String,
) {
    private val declared = mutableListOf<Permission>()

    init {
        require(PLUGIN_ID.matches(pluginId)) { "plugin id \"$pluginId\": use lower-case a-z, 0-9, - and _, starting with a letter" }
    }

    /** Every permission, in order. */
    public val permissions: List<Permission> get() = declared

    /** The permission with this full [node], or `null` if none here has it. */
    public operator fun get(node: String): Permission? = declared.firstOrNull { it.node == node }

    /**
     * `<pluginId>.<suffix>`: who has it by [default] (operators unless set), and the nodes it
     * grants or takes away ([children], by full node: other plugins' too).
     */
    protected fun permission(
        suffix: String,
        description: String,
        default: PermissionDefault = PermissionDefault.OP,
        children: Map<String, Boolean> = emptyMap(),
    ): Permission = declare("$pluginId.$suffix", description, default, children, builtIn = false)

    /** [permissions] as children that grant each, for [permission]'s `children`. */
    protected fun grants(vararg permissions: Permission): Map<String, Boolean> = permissions.associate { it.node to true }

    internal fun declare(node: String, description: String, default: PermissionDefault, children: Map<String, Boolean>, builtIn: Boolean): Permission {
        require(Permission.NODE.matches(node)) { "permission \"$node\": use lower-case words of a-z, 0-9, - and _, joined with ." }
        require(declared.none { it.node == node }) { "permission $node declared twice" }
        for (child in children.keys) {
            require(Permission.NODE.matches(child)) { "$node: child \"$child\" isn't a permission node" }
            require(child != node) { "$node can't be its own child" }
        }
        return Permission(node, description, default, LinkedHashMap(children), builtIn).also { declared += it }
    }

    internal companion object {
        val PLUGIN_ID = Regex("[a-z][a-z0-9_-]*")
    }
}

/**
 * Where the platform's adapter declares permissions, with their defaults and children: Bukkit's
 * `PluginManager`, Fabric's permissions API, LuckPerms' registry, and so on. The adapter
 * implements it; the core calls [sync] at startup and on every reload, so a permission a
 * reload no longer declares is removed and a changed default applies.
 */
public interface PermissionRegistrar {
    /**
     * Declares exactly [permissions] for this plugin: adds the new ones, updates the changed
     * ones and removes the ones it declared before that aren't here. A node another plugin
     * declared is left as that plugin has it. Called on the global thread.
     */
    public fun sync(permissions: List<Permission>)
}
