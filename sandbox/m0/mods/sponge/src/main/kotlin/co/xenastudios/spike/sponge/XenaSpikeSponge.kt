package co.xenastudios.spike.sponge

import com.google.inject.Inject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.apache.logging.log4j.Logger
import org.incendo.cloud.CommandManager
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.internal.CommandRegistrationHandler
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.spongepowered.api.Platform
import org.spongepowered.api.Sponge
import org.spongepowered.api.command.Command
import org.spongepowered.api.command.CommandCause
import org.spongepowered.api.command.CommandCompletion
import org.spongepowered.api.command.CommandResult
import org.spongepowered.api.command.parameter.ArgumentReader
import org.spongepowered.api.event.Listener
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent
import org.spongepowered.api.event.lifecycle.StartedEngineEvent
import org.spongepowered.api.Server
import org.spongepowered.plugin.PluginContainer
import org.spongepowered.plugin.builtin.jvm.Plugin
import java.util.Optional

/**
 * Cloud 2 publishes no Sponge API 8+ integration (only cloud-sponge7), so this is the
 * smallest one: Cloud's core parses, Sponge sees one raw command per root literal.
 */
class SpongeCloud : CommandManager<CommandCause>(
    ExecutionCoordinator.simpleCoordinator(),
    CommandRegistrationHandler.nullCommandRegistrationHandler(),
) {
    override fun hasPermission(sender: CommandCause, permission: String): Boolean =
        permission.isEmpty() || sender.hasPermission(permission)

    fun raw(root: String): Command.Raw = object : Command.Raw {
        override fun process(cause: CommandCause, arguments: ArgumentReader.Mutable): CommandResult {
            commandExecutor().executeCommand(cause, "$root ${arguments.remaining()}".trim()).join()
            return CommandResult.success()
        }

        override fun complete(cause: CommandCause, arguments: ArgumentReader.Mutable): List<CommandCompletion> =
            suggestionFactory().suggestImmediately(cause, "$root ${arguments.remaining()}")
                .list().map { CommandCompletion.of(it.suggestion()) }

        override fun canExecute(cause: CommandCause) = true
        override fun shortDescription(cause: CommandCause): Optional<Component> = Optional.empty()
        override fun extendedDescription(cause: CommandCause): Optional<Component> = Optional.empty()
        override fun usage(cause: CommandCause): Component = Component.text("/$root")
    }
}

@Plugin("xenaspike")
class XenaSpikeSponge @Inject constructor(
    private val container: PluginContainer,
    private val logger: Logger,
) {
    private val mini = MiniMessage.miniMessage()
    private val cloud = SpongeCloud()

    init {
        cloud.command(
            cloud.commandBuilder("xspike").literal("hello").optional("name", stringParser()).handler { ctx ->
                val cause = ctx.sender()
                val name = ctx.getOrDefault("name", cause.friendlyIdentifier().orElse("someone"))
                // CommandCause is an Audience: Sponge's Adventure is native, no platform adapter.
                cause.audience().sendMessage(
                    mini.deserialize(
                        "<gradient:#ff5555:#5555ff>Hello</gradient>, <bold><name></bold>! <gray>(Kotlin + Cloud + Adventure on Sponge <mc>)",
                        Placeholder.unparsed("name", name),
                        Placeholder.unparsed("mc", Sponge.platform().minecraftVersion().name()),
                    ),
                )
                logger.info("XSPIKE HELLO {} on Sponge", name)
            },
        )
    }

    @Listener
    fun onRegisterCommands(event: RegisterCommandEvent<Command.Raw>) {
        event.register(container, cloud.raw("xspike"), "xspike")
    }

    @Listener
    fun onStarted(event: StartedEngineEvent<Server>) {
        val api = Sponge.platform().container(Platform.Component.API).metadata().version()
        logger.info("XSPIKE LOADED on Sponge API {} (Kotlin {})", api, KotlinVersion.CURRENT)
    }
}
