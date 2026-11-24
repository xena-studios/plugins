package co.xenastudios.m0.site.core

// What a plugin declares, once. The same objects drive the runtime (the command tree, the
// permission checks, config loading, message lookups) and the docs (Dump.kt), so the docs
// can't describe a command, key or default the plugin doesn't have.

/** Implemented by the plugin's `object` that the build names in `xena { definition = ... }`. */
interface PluginDefinitionProvider {
    val definition: PluginDefinition
}

class PluginDefinition internal constructor(
    val id: String,
    val name: String,
    val description: String,
    val commands: List<Command>,
    val permissions: List<Permission>,
    val config: ConfigSpec,
    val messages: MessageSpec,
    val placeholders: List<Placeholder>,
    val events: List<EventDecl>,
)

class Permission internal constructor(
    val node: String,
    val description: String,
    val default: PermissionDefault,
    val children: Map<String, Boolean>,
)

/** A placeholder: what it shows, and the code that resolves it for a player. */
class Placeholder internal constructor(
    val id: String,
    val description: String,
    val example: String,
    val resolve: (PlayerRef) -> String,
)

/** A player by UUID, as the core's APIs take them. */
data class PlayerRef(val uuid: String, val name: String)

class EventDecl internal constructor(
    val className: String,
    val description: String,
    val cancellable: Boolean,
    val since: String,
    val fields: List<FieldDoc>,
)

fun xenaPlugin(id: String, name: String, description: String, block: PluginBuilder.() -> Unit): PluginDefinition =
    PluginBuilder(id, name, description).apply(block).build()

@DslMarker
annotation class XenaDsl

@XenaDsl
class PluginBuilder internal constructor(val id: String, private val name: String, private val description: String) {
    private val commands = mutableListOf<Command>()
    private val permissions = mutableListOf<Permission>()
    private val placeholders = mutableListOf<Placeholder>()
    private val events = mutableListOf<EventDecl>()
    private var config = ConfigSpec()
    private var messages = MessageSpec()

    /** `permission("give", ...)` declares `<id>.give`. */
    fun permission(
        suffix: String,
        description: String,
        default: PermissionDefault = PermissionDefault.OP,
        children: Map<String, Boolean> = emptyMap(),
    ): Permission {
        val p = Permission("$id.$suffix", description, default, children)
        require(permissions.none { it.node == p.node }) { "Permission ${p.node} declared twice" }
        permissions += p
        return p
    }

    fun command(
        name: String,
        description: String,
        permission: Permission? = null,
        aliases: List<String> = emptyList(),
        playerOnly: Boolean = false,
        block: CommandBuilder.() -> Unit = {},
    ) {
        commands += CommandBuilder(listOf(name), description, permission, aliases, playerOnly, false).apply(block).build()
    }

    fun config(spec: ConfigSpec) {
        config = spec
    }

    fun messages(spec: MessageSpec) {
        messages = spec
    }

    fun placeholder(id: String, description: String, example: String, resolve: (PlayerRef) -> String) {
        placeholders += Placeholder(id, description, example, resolve)
    }

    fun event(className: String, description: String, cancellable: Boolean, since: String, block: EventBuilder.() -> Unit = {}) {
        events += EventDecl(className, description, cancellable, since, EventBuilder().apply(block).fields)
    }

    internal fun build(): PluginDefinition {
        // Built once, in the core (ROADMAP.md, "Admin commands"): every plugin gets reload and
        // debug under its own command, each with its own permission under <id>.admin.
        val reload = permission("admin.reload", "Use /$id reload.")
        val debug = permission("admin.debug", "Use /$id debug.")
        permission("admin", "Every admin subcommand.", children = mapOf(reload.node to true, debug.node to true))
        val admin = listOf(
            CommandBuilder(listOf(id, "reload"), "Reloads config.yml and the language files.", reload, emptyList(), false, true).build(),
            CommandBuilder(listOf(id, "debug"), "Checks the database and dependencies, then prints a paste link for support.", debug, emptyList(), false, true).build(),
        )
        val root = commands.indexOfFirst { it.path == listOf(id) }
        if (root >= 0) {
            commands[root] = commands[root].copy(children = commands[root].children + admin)
        } else {
            commands += Command(listOf(id), emptyList(), "$name's admin commands.", null, false, emptyList(), admin, true, null)
        }
        for (c in commands.flatMap { it.flatten() }) {
            val p = c.permission ?: continue
            require(p in permissions) { "/${c.path.joinToString(" ")} uses ${p.node}, which isn't declared" }
        }
        return PluginDefinition(id, name, description, commands.toList(), permissions.toList(), config, messages, placeholders.toList(), events.toList())
    }
}

@XenaDsl
class EventBuilder internal constructor() {
    internal val fields = mutableListOf<FieldDoc>()

    fun field(name: String, type: String, description: String) {
        fields += FieldDoc(name, type, description)
    }
}
