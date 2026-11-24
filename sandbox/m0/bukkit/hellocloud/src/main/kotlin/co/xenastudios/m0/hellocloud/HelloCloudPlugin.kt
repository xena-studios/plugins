package co.xenastudios.m0.hellocloud

import co.xenastudios.m0.loader.Delegate
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser

/** Sends a component: picked once at startup, never per call. */
interface Text {
    val kind: String
    fun send(sender: CommandSender, message: Component)
    fun close() {}
}

/** Paper (and Folia): every CommandSender is already an Audience. */
class NativeText : Text {
    override val kind = "native Adventure"
    override fun send(sender: CommandSender, message: Component) = (sender as Audience).sendMessage(message)
}

/** Spigot and CraftBukkit: adventure-platform-bukkit, fetched at startup. */
class PlatformText(plugin: JavaPlugin) : Text {
    private val audiences = BukkitAudiences.create(plugin)
    override val kind = "adventure-platform-bukkit"
    override fun send(sender: CommandSender, message: Component) = audiences.sender(sender).sendMessage(message)
    override fun close() = audiences.close()
}

class HelloCloudPlugin(private val plugin: JavaPlugin) : Delegate {

    private val mini = MiniMessage.miniMessage()
    private lateinit var text: Text

    override fun enable() {
        text = if (Audience::class.java.isAssignableFrom(CommandSender::class.java)) NativeText() else PlatformText(plugin)
        plugin.logger.info("text: ${text.kind}; Adventure from ${where(Audience::class.java)}; " +
            "Cloud from ${where(LegacyPaperCommandManager::class.java)}")
        plugin.logger.info("getPlugin(\"HelloProvided\") -> ${Bukkit.getPluginManager().getPlugin("HelloProvided")?.name}")

        val manager = LegacyPaperCommandManager.createNative(plugin, ExecutionCoordinator.simpleCoordinator())
        manager.command(
            manager.commandBuilder("hellocloud")
                .optional("name", stringParser())
                .handler { context ->
                    val name = context.getOrDefault("name", "world")
                    text.send(context.sender(), themed(name))
                    plugin.logger.info("hellocloud sent to ${context.sender().name}: " + mini.serialize(themed(name)))
                },
        )
        manager.command(
            manager.commandBuilder("xbench")
                .optional("count", integerParser(1))
                .handler { context -> bench(context.sender(), context.getOrDefault("count", 5000)) },
        )
    }

    override fun disable() = text.close()

    private fun themed(target: String): Component = mini.deserialize(
        "<#7c5cff>xUtilities</#7c5cff> <dark_gray>»</dark_gray> <gray>Teleported to <white><target></white>.</gray>",
        Placeholder.unparsed("target", target),
    )

    /**
     * One themed message, parsed once, sent [count] times to each online player, five rounds
     * after a warm-up round; also the cost of parsing it. Main thread, wall clock.
     */
    private fun bench(sender: CommandSender, count: Int) {
        val players = Bukkit.getOnlinePlayers().toList<Player>()
        if (players.isEmpty()) {
            sender.sendMessage("xbench: nobody online")
            return
        }
        val message = themed("Spawn")
        val rounds = (0..5).map { _ ->
            val start = System.nanoTime()
            repeat(count) { players.forEach { text.send(it, message) } }
            (System.nanoTime() - start).toDouble() / (count * players.size)
        }.drop(1).sorted()
        val cached = if (text is NativeText) "" else {
            // Spigot: drawn once to the server's own forms, then sent without Adventure.
            val hex = Bukkit.getBukkitVersion().substringBefore('-').split('.').getOrNull(1)?.toIntOrNull()?.let { it >= 16 } == true
            val bungee = (if (hex) BungeeComponentSerializer.get() else BungeeComponentSerializer.legacy()).serialize(message)
            val legacy = (if (hex) LegacyComponentSerializer.legacySection().toBuilder().hexColors().useUnusualXRepeatedCharacterHexFormat().build()
                else LegacyComponentSerializer.legacySection()).serialize(message)
            val viaBungee = time(count) { players.forEach { it.spigot().sendMessage(*bungee) } } / players.size
            val viaLegacy = time(count) { players.forEach { it.sendMessage(legacy) } } / players.size
            "; cached BaseComponent[]: median %.0f ns; cached legacy string: median %.0f ns".format(viaBungee, viaLegacy)
        }
        val parse = (0..5).map { _ ->
            val start = System.nanoTime()
            repeat(count) { themed("Spawn") }
            (System.nanoTime() - start).toDouble() / count
        }.drop(1).sorted()
        val line = "xbench [${text.kind}, ${Bukkit.getVersion()}, Java ${System.getProperty("java.version")}] " +
            "send: median %.0f ns (min %.0f, max %.0f) x %d x %d players; parse: median %.0f ns".format(
                rounds[2], rounds.first(), rounds.last(), count, players.size, parse[2],
            ) + cached
        plugin.logger.info(line)
        sender.sendMessage(line)
    }

    /** Median ns per call of five timed rounds of [count] calls, after one warm-up round. */
    private inline fun time(count: Int, call: () -> Unit): Double = (0..5).map { _ ->
        val start = System.nanoTime()
        repeat(count) { call() }
        (System.nanoTime() - start).toDouble() / count
    }.drop(1).sorted()[2]

    private fun where(type: Class<*>): String {
        val path = type.protectionDomain.codeSource?.location?.path ?: "?"
        return path.trimEnd('/').substringAfterLast('/')
    }
}
