package co.xenastudios.xvote.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xvote.DeferredImports
import co.xenastudios.xvote.JvmDatabase
import co.xenastudios.xvote.JvmFiles
import co.xenastudios.xvote.JvmZones
import co.xenastudios.xvote.VoteServer
import co.xenastudios.xvote.XMoneyEconomy
import co.xenastudios.xvote.XVotePlugin
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.receiver.ReceiverHost
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * xVote as a server-side Fabric mod: the shared plugin on platform-fabric, with its own Votifier
 * receiver. `config.yml` (in `config/xvote/`) is read as the mod starts, so its commands are
 * registered when Minecraft builds its command tree; the database is opened (its driver downloaded
 * the first time: Fabric servers have none) and every voter read off the server thread, then the
 * receiver listens. Rewards run through Minecraft's own commands where Minecraft has the words for
 * them on every line: items through `/give` (so what doesn't fit drops at the player's feet, as
 * `/give` does, rather than waiting for `/vote claim`) and sounds through `/playsound`. Money through
 * xMoney's API when it's installed. Behind a proxy, Fabric has no end of the proxy layer yet, so votes
 * the proxy receives reach a Fabric server through the database's change feed.
 */
public object XVoteMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XVotePlugin.PLUGIN_ID, XVotePlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XVotePlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XVotePlugin.VERSION).substringBefore('+')
        val server = FabricVoteServer(platform, family.players)
        val config = files.config()
        val drivers = Drivers(XVoteMod::class.java.classLoader, platform.drivers(XVoteMod::class.java.classLoader))
        val gameDir = FabricLoader.getInstance().gameDir
        val receiver = ReceiverHost(folder, platform.log, "config/xvote/rsa/public.key", null, files)
        val xvote = XVotePlugin(
            platform, server, files, family.players, platform.players, family.players,
            opener = JvmDatabase(family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            receiver = receiver,
            // A Bukkit server's folders, should their old plugins' files be copied here.
            imports = DeferredImports(gameDir.resolve("plugins"), folder, drivers, platform.log, files),
            zones = JvmZones,
            version = version,
        )
        receiver.plugin = xvote
        platform.commands.feedback = xvote.texts.feedback
        xvote.start(config)
        xvote.open()
        platform.scheduler.run(Where.Async) { _ ->
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xvote.apply(loaded) }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> xvote.joined(handler.player.ref, handler.player.scoreboardName) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> xvote.quit(handler.player.ref, handler.player.scoreboardName) }
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            // Every mod shares one class loader, so xMoney's API is found by name once mods are up.
            XMoneyEconomy.shared()?.let { api ->
                val (economy, why) = XMoneyEconomy.of(api)
                if (economy != null) {
                    xvote.economy = economy
                    xvote.log.info("Money rewards are paid through xMoney.")
                } else {
                    xvote.log.warn("Couldn't pay money rewards through xMoney: $why")
                }
            }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xvote.updates = platform.checkUpdates(XVotePlugin.PLUGIN_ID, xvote.admin.updates, XVoteConfig.updates.settings(config.config)) {
            xvote.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xvote.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xVote's database didn't finish in time: $e")
            }
        }
        platform.log.info("xVote ${xvote.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XVotePlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
        val out = LinkedHashMap<LanguageTag, String>()
        // The shrunk jar has them in one entry (LangFolder.BUNDLE); a dev build, one file each.
        val bundle = mod.findPath(LangFolder.BUNDLE).orElse(null)?.let { LangFolder.bundle(String(Files.readAllBytes(it), StandardCharsets.UTF_8)) }.orEmpty()
        for (tag in JvmFiles.LANGUAGES) {
            val path = mod.findPath("lang/$tag.yml").orElse(null)
            if (path == null) {
                bundle[tag.toString()]?.let { out[tag] = it }
                continue
            }
            out[tag] = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }
        return out
    }
}

/** xVote's [VoteServer] on Fabric: Minecraft's players, on the server thread, and its own `/give` and `/playsound` for rewards. */
internal class FabricVoteServer(private val platform: FabricPlatform, private val cache: PlayerCache) : VoteServer {
    private fun server(): MinecraftServer? = platform.server

    private fun player(ref: PlayerRef): ServerPlayer? = server()?.player(ref)

    private fun onServer(work: () -> Unit) {
        val server = server() ?: return
        if (server.isSameThread) work() else server.execute(work)
    }

    override fun online(): List<PlayerRef> = server()?.playerList?.players?.map { it.ref }.orEmpty()

    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        onServer { player(player)?.sendSystemMessage(text) }
    }

    override fun console(message: Component) {
        platform.log.info(message.plain())
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } ?: false

    override fun dispatch(line: String) {
        onServer {
            val server = server() ?: return@onServer
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), line)
        }
    }

    override fun known(name: String): Pair<PlayerRef, String>? = cache.find(name)?.let { it.player to it.name }

    // An item as /give takes it: an id with optional components, then an amount. Minecraft reads it
    // as it gives it, and says in the console if it can't.
    override fun item(written: String): Outcome<Any, String> {
        var text = written.trim()
        var amount = 1
        val space = text.lastIndexOf(' ')
        if (space > 0 && text.substring(space + 1).let { it.length in 1..4 && it.all(Char::isDigit) }) {
            amount = text.substring(space + 1).toInt()
            text = text.substring(0, space).trim()
        }
        if (text.isEmpty() || amount < 1 || ' ' in text.substringBefore('[')) return Outcome.Err("isn't an item, such as diamond 3")
        if (text.removePrefix("minecraft:").substringBefore('[') == "air") return Outcome.Err("is air")
        return Outcome.Ok("$text ${minOf(amount, 6400)}")
    }

    override fun give(player: PlayerRef, items: List<Any>): Pair<List<ByteArray>, Int> {
        val name = name(player) ?: return emptyList<ByteArray>() to 0
        for (item in items) dispatch("give $name $item")
        return emptyList<ByteArray>() to 0
    }

    // Held items are a Bukkit server's (a network sharing the database): Fabric can't read them, so
    // they stay for a Bukkit server to hand out.
    override fun handOut(player: PlayerRef, held: List<ByteArray>): Triple<Int, List<ByteArray>, Int> = Triple(0, held, held.size)

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val name = name(player) ?: return
        dispatch("execute as $name at @s run playsound ${sound.name} master @s ~ ~ ~ ${sound.volume} ${sound.pitch}")
    }
}
