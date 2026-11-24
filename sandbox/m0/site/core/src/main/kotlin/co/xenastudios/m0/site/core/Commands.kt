package co.xenastudios.m0.site.core

/** Who ran a command. */
data class Sender(val name: String, val isPlayer: Boolean, val permissions: Set<String>) {
    fun has(p: Permission?) = p == null || p.node in permissions
}

class CommandContext(val sender: Sender, val args: Map<String, Any>)

/** An argument, with how it parses: the same object the docs describe. */
sealed class Arg(val name: String, val type: String, val required: Boolean, val description: String) {
    abstract fun parse(raw: String): Any?

    class PlayerArg(name: String, required: Boolean, description: String) : Arg(name, "player", required, description) {
        override fun parse(raw: String) = raw.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,16}")) }
    }

    class IntegerArg(name: String, required: Boolean, description: String, val min: Int?, val max: Int?) :
        Arg(name, "integer", required, description) {
        override fun parse(raw: String) = raw.toIntOrNull()?.takeIf { (min == null || it >= min) && (max == null || it <= max) }
    }

    class TextArg(name: String, required: Boolean, description: String) : Arg(name, "text", required, description) {
        override fun parse(raw: String) = raw
    }

    class ChoiceArg(name: String, required: Boolean, description: String, val choices: List<String>) :
        Arg(name, "choice", required, description) {
        override fun parse(raw: String) = raw.lowercase().takeIf { it in choices }
    }
}

data class Command(
    val path: List<String>,
    val aliases: List<String>,
    val description: String,
    val permission: Permission?,
    val playerOnly: Boolean,
    val args: List<Arg>,
    val children: List<Command>,
    val builtIn: Boolean,
    val handler: ((CommandContext) -> String)?,
) {
    fun flatten(): List<Command> = listOf(this) + children.flatMap { it.flatten() }

    val usage: String
        get() = "/" + (path + args.map { if (it.required) "<${it.name}>" else "[${it.name}]" }).joinToString(" ")
}

@XenaDsl
class CommandBuilder internal constructor(
    private val path: List<String>,
    private val description: String,
    private val permission: Permission?,
    private val aliases: List<String>,
    private val playerOnly: Boolean,
    private val builtIn: Boolean,
) {
    private val args = mutableListOf<Arg>()
    private val children = mutableListOf<Command>()
    private var handler: ((CommandContext) -> String)? = null

    fun player(name: String, description: String, required: Boolean = true) {
        args += Arg.PlayerArg(name, required, description)
    }

    fun integer(name: String, description: String, min: Int? = null, max: Int? = null, required: Boolean = true) {
        args += Arg.IntegerArg(name, required, description, min, max)
    }

    fun text(name: String, description: String, required: Boolean = true) {
        args += Arg.TextArg(name, required, description)
    }

    fun choice(name: String, description: String, choices: List<String>, required: Boolean = true) {
        args += Arg.ChoiceArg(name, required, description, choices)
    }

    fun subcommand(
        name: String,
        description: String,
        permission: Permission? = null,
        aliases: List<String> = emptyList(),
        playerOnly: Boolean = false,
        block: CommandBuilder.() -> Unit = {},
    ) {
        children += CommandBuilder(path + name, description, permission, aliases, playerOnly, false).apply(block).build()
    }

    /** What the command does; returns the reply, for this spike. */
    fun executes(handler: (CommandContext) -> String) {
        this.handler = handler
    }

    internal fun build(): Command {
        var optionalSeen = false
        for (a in args) {
            require(!(optionalSeen && a.required)) { "/${path.joinToString(" ")}: required <${a.name}> after an optional argument" }
            optionalSeen = optionalSeen || !a.required
        }
        return Command(path, aliases, description, permission, playerOnly, args.toList(), children.toList(), builtIn, handler)
    }
}

/**
 * A stand-in for the platform's dispatcher (Incendo Cloud in the rewrite), enough to show the
 * declared tree, arguments and permissions are what runs.
 */
class Dispatcher(private val roots: List<Command>) {
    fun run(sender: Sender, line: String): String {
        val words = line.removePrefix("/").split(' ').filter { it.isNotEmpty() }
        var cmd = roots.firstOrNull { words.firstOrNull() in it.aliases + it.path.last() } ?: return "Unknown command."
        var i = 1
        while (i < words.size) {
            cmd = cmd.children.firstOrNull { words[i] in it.aliases + it.path.last() } ?: break
            i++
        }
        if (!sender.has(cmd.permission)) return "You don't have permission."
        if (cmd.playerOnly && !sender.isPlayer) return "Only players can use this."
        val parsed = mutableMapOf<String, Any>()
        for (a in cmd.args) {
            val raw = words.getOrNull(i++)
            if (raw == null) {
                if (a.required) return "Usage: ${cmd.usage}"
                break
            }
            parsed[a.name] = a.parse(raw) ?: return "Invalid ${a.name}: $raw"
        }
        val handler = cmd.handler ?: return "Usage: ${cmd.usage}"
        return handler(CommandContext(sender, parsed))
    }
}
