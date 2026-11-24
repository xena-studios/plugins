package co.xenastudios.spike

import net.fabricmc.api.ModInitializer
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.minecraft.commands.CommandSourceStack
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.fabric.FabricServerCommandManager
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.slf4j.LoggerFactory
//? if modcommon {
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences
//?} else {
/*import net.kyori.adventure.platform.fabric.FabricServerAudiences
*///?}

/** One Cloud command that answers with a MiniMessage line drawn through Adventure. */
object XenaSpike : ModInitializer {
    const val MINECRAFT = /*$ minecraft*/"26.3"
    private val log = LoggerFactory.getLogger("xenaspike")
    private val mini = MiniMessage.miniMessage()

    override fun onInitialize() {
        val manager = FabricServerCommandManager.createNative(ExecutionCoordinator.simpleCoordinator())
        manager.command(
            manager.commandBuilder("xspike")
                .literal("hello")
                .optional("name", stringParser())
                .handler { ctx ->
                    val source = ctx.sender()
                    val name = ctx.getOrDefault("name", source.textName)
                    audience(source).sendMessage(
                        mini.deserialize(
                            "<gradient:#ff5555:#5555ff>Hello</gradient>, <bold><name></bold>! " +
                                "<gray>(Kotlin + Cloud + Adventure on <mc>)",
                            Placeholder.unparsed("name", name),
                            Placeholder.unparsed("mc", MINECRAFT),
                        ),
                    )
                    log.info("XSPIKE HELLO {} on {}", name, MINECRAFT)
                },
        )
        log.info("XSPIKE LOADED on Minecraft {} (Kotlin {})", MINECRAFT, KotlinVersion.CURRENT)
    }

    private fun audience(source: CommandSourceStack) =
        //? if modcommon {
        MinecraftServerAudiences.of(source.server).audience(source)
        //?} else
        //FabricServerAudiences.of(source.server).audience(source)
}
