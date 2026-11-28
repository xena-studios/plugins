package co.xenastudios.core.sponge

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandNode
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.text.AdventureRenderer
import org.spongepowered.api.Sponge
import org.spongepowered.api.SystemSubject
import org.spongepowered.api.command.Command
import org.spongepowered.api.command.CommandCause
import org.spongepowered.api.command.CommandCompletion
import org.spongepowered.api.command.CommandResult
import org.spongepowered.api.command.manager.CommandFailedRegistrationException
import org.spongepowered.api.command.parameter.ArgumentReader
import org.spongepowered.api.entity.living.player.server.ServerPlayer
import org.spongepowered.plugin.PluginContainer
import java.util.Optional
import kotlin.concurrent.Volatile
import net.kyori.adventure.text.Component as AdventureComponent

/**
 * A plugin's commands on Sponge, through our own small bridge (DECISIONS.md, M0: Cloud has no
 * Sponge 8+ module): one `Command.Raw` per label of each root, which hands the whole line to the
 * [CommandDispatcher] and answers completions with its suggestions. Sponge sends clients one
 * greedy argument per raw command, so players see our suggestions but not Brigadier's argument
 * hints, as on Bukkit.
 *
 * Sponge takes commands in its `RegisterCommandEvent`, as the server starts, and can't take one
 * away: so each label stays registered for the life of the server, and the newest dispatcher
 * decides what it does. A root a reload turned off answers as an unknown command would, and is
 * hidden from players' command trees ([Command.Raw.canExecute]); a root a reload turned on that
 * wasn't registered at startup is registered then, through the command manager's registrar, and
 * players get their new tree. A label another plugin already has stays theirs, with a warning,
 * and ours is still `/<plugin id>:<label>`, as Sponge namespaces every command.
 *
 * Commands run on the server thread, where Sponge processes them.
 */
public class SpongeCommands internal constructor(
    private val container: PluginContainer,
    private val permissions: SpongePermissions,
    private val log: Log,
) : CommandRegistrar {

    /** What sources are told after a command; set it when the plugin's translations are loaded ([CommandFeedback.of]). */
    @Volatile
    public var feedback: CommandFeedback = CommandFeedback.ENGLISH

    @Volatile
    private var dispatcher: CommandDispatcher? = null

    // The labels Sponge has a raw command of ours for, each answering for the root of that name.
    private val registered = LinkedHashMap<String, String>()

    // Whether Sponge has taken its commands, after which new labels go through the registrar.
    @Volatile
    private var started = false

    override fun sync(dispatcher: CommandDispatcher): List<String> = synchronized(this) {
        this.dispatcher = dispatcher
        if (!started) return emptyList()
        val warnings = ArrayList<String>()
        val registrar = Sponge.server().commandManager().registrar(Command.Raw::class.java).orElse(null)
        var added = false
        for (root in dispatcher.tree.roots) {
            for (label in labels(root)) {
                if (label in registered) continue
                if (registrar == null) {
                    warnings += "/$label can't be added while the server runs; restart the server for it"
                    continue
                }
                try {
                    registrar.register(container, raw(root.name, label), label)
                    registered[label] = root.name
                    added = true
                } catch (e: CommandFailedRegistrationException) {
                    warnings += "/$label couldn't be registered: ${e.message}"
                }
            }
        }
        if (added) runningServer()?.let { server -> server.onlinePlayers().forEach(server.commandManager()::updateCommandTreeForPlayer) }
        warnings
    }

    override fun clear() {
        // Sponge can't unregister: with no dispatcher, every label answers as unknown.
        dispatcher = null
    }

    /** Registers every label of the current dispatcher's roots: from Sponge's `RegisterCommandEvent`. */
    internal fun register(event: org.spongepowered.api.event.lifecycle.RegisterCommandEvent<Command.Raw>) = synchronized(this) {
        started = true
        val roots = dispatcher?.tree?.roots ?: return@synchronized
        for (root in roots) {
            for (label in labels(root)) {
                if (label in registered) continue
                try {
                    event.register(container, raw(root.name, label), label)
                    registered[label] = root.name
                } catch (e: CommandFailedRegistrationException) {
                    log.warn("/$label couldn't be registered: ${e.message}")
                }
            }
        }
    }

    private fun labels(root: CommandNode): List<String> = listOf(root.name) + root.aliases

    /** Our view of a Sponge command cause, for code that runs commands its own way. */
    public fun source(cause: CommandCause): CommandSource = SpongeCommandSource(cause, permissions)

    // The root [rootName] answers [label] in the newest dispatcher, if it's still there under it.
    private fun current(rootName: String, label: String): CommandDispatcher? {
        val now = dispatcher ?: return null
        val root = now.tree.roots.firstOrNull { it.name == rootName || label in labels(it) } ?: return null
        return if (label in labels(root)) now else null
    }

    private fun raw(rootName: String, label: String): Command.Raw = object : Command.Raw {
        override fun process(cause: CommandCause, arguments: ArgumentReader.Mutable): CommandResult {
            val source = source(cause)
            val now = current(rootName, label)
            if (now == null) {
                source.reply(Component.text("Unknown command: /$label"))
                return CommandResult.success()
            }
            val result = now.dispatch(source, line(label, arguments.remaining()))
            feedback.message(result, source)?.let(source::reply)
            return CommandResult.success()
        }

        override fun complete(cause: CommandCause, arguments: ArgumentReader.Mutable): List<CommandCompletion> {
            val now = current(rootName, label) ?: return emptyList()
            return now.suggest(source(cause), line(label, arguments.remaining())).map { CommandCompletion.of(it) }
        }

        override fun canExecute(cause: CommandCause): Boolean {
            val now = current(rootName, label) ?: return false
            val node = now.tree.roots.firstOrNull { label in labels(it) }?.permission?.node ?: return true
            return permissions.has(cause.subject(), node)
        }

        override fun shortDescription(cause: CommandCause): Optional<AdventureComponent> =
            Optional.ofNullable(dispatcher?.tree?.roots?.firstOrNull { it.name == rootName }?.description?.takeIf { it.isNotEmpty() }?.let { AdventureComponent.text(it) })

        override fun extendedDescription(cause: CommandCause): Optional<AdventureComponent> = shortDescription(cause)

        override fun usage(cause: CommandCause): AdventureComponent = AdventureComponent.text("/$label")
    }

    internal companion object {
        /** The whole line the dispatcher reads: the label, then what was typed after it. */
        fun line(label: String, remaining: String): String = if (remaining.isEmpty()) label else "$label $remaining"
    }
}

/** A Sponge command cause as the core's [CommandSource]. */
internal class SpongeCommandSource(
    private val cause: CommandCause,
    private val permissions: SpongePermissions,
) : CommandSource {
    private val subject = cause.subject()
    private val playerSubject = subject as? ServerPlayer

    override val name: String get() = playerSubject?.name() ?: if (subject is SystemSubject) "CONSOLE" else subject.friendlyIdentifier().orElse(subject.identifier())

    override val player: PlayerRef? get() = playerSubject?.ref

    override fun hasPermission(node: String): Boolean = permissions.has(subject, node)

    override fun reply(message: Component) {
        val text = AdventureRenderer.draw(message)
        val audience = cause.audience()
        onServerThread { audience.sendMessage(text) }
    }
}
