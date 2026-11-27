package co.xenastudios.xbans.fabric

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xbans.BansServer
import co.xenastudios.xbans.FileImports
import co.xenastudios.xbans.JvmDatabase
import co.xenastudios.xbans.JvmFiles
import co.xenastudios.xbans.Power
import co.xenastudios.xbans.XBansPlugin
import co.xenastudios.xbans.ZChatMutes
import co.xenastudios.xbans.config.XBansConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.lang.reflect.Field
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toKotlinUuid

/**
 * xBans as a server-side Fabric mod: the shared plugin on platform-fabric. `config.yml` (in
 * `config/xbans/`) is read as the mod starts, so its commands are registered when Minecraft builds
 * its command tree; the database is opened (its driver downloaded the first time: Fabric servers
 * have none) and the bans read off the server thread. A banned player is refused as they log in,
 * before they're in the world (the login's query stage, which waits up to 10 seconds for the bans
 * just after a start), and again at join; muted players' chat, `/me` and `/say` are stopped
 * through Fabric's message events, and zChat's `/msg` through its mute seam. Minecraft has no
 * event for other commands, nor for signs, books or anvils, so those mute settings are Bukkit's.
 */
public object XBansMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XBansPlugin.PLUGIN_ID, XBansPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XBansPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XBansPlugin.VERSION).substringBefore('+')
        val server = FabricBansServer(platform)
        val config = files.config()
        val drivers = Drivers(XBansMod::class.java.classLoader, platform.drivers(XBansMod::class.java.classLoader))
        val gameDir = FabricLoader.getInstance().gameDir
        val xbans = XBansPlugin(
            platform, server, files, family.players, platform.players, family.players,
            opener = JvmDatabase(family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            // The server's own ban lists; the other importers read Bukkit plugins' files.
            imports = FileImports(gameDir, gameDir.resolve("mods"), drivers, platform.log, listOf("vanilla")),
            version = version,
        )
        platform.commands.feedback = xbans.texts.feedback
        xbans.start(config)
        xbans.open()
        platform.scheduler.run(Where.Async) { _ ->
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xbans.apply(loaded) }
        }
        listen(platform, xbans, server)
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            xbans.ready.whenComplete { _, _ -> ZChatMutes.fabricApi()?.let { ZChatMutes.register(it, xbans, xbans.log) } }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xbans.updates = platform.checkUpdates(XBansPlugin.PLUGIN_ID, xbans.admin.updates, XBansConfig.updates.settings(config.config)) {
            xbans.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xbans.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xBans' database didn't finish saving in time: $e")
            }
        }
        platform.log.info("xBans ${xbans.version} on ${platform.name}")
    }

    private fun listen(platform: FabricPlatform, xbans: XBansPlugin, server: FabricBansServer) {
        // The door: once the player's profile is known, before they're in the world.
        ServerLoginConnectionEvents.QUERY_START.register { handler, _, _, synchronizer ->
            val decide = Runnable {
                val (id, name, address) = LoginDetails.of(handler) ?: return@Runnable
                val player = PlayerRef.of(id.toKotlinUuid())
                if (xbans.backend == null) {
                    handler.disconnect(MinecraftRenderer.draw(xbans.denied(player, name, null)))
                    return@Runnable
                }
                val ban = xbans.banOnJoin(player, address) ?: return@Runnable
                handler.disconnect(MinecraftRenderer.draw(xbans.denied(player, name, ban)))
            }
            if (xbans.backend != null) {
                decide.run()
            } else {
                // Just after a start: the login waits for the bans, at most 10 seconds, without the server thread.
                val waited = CompletableFuture<Unit>()
                xbans.ready.whenComplete { _, _ -> waited.complete(Unit) }
                platform.scheduler.later(Where.Async, XBansPlugin.WAIT_SECONDS.seconds) { _ -> waited.complete(Unit) }
                synchronizer.waitFor(waited.thenRunAsync({ platform.server?.execute(decide) ?: decide.run() }, Runnable::run))
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            val ban = xbans.joined(player.ref, player.scoreboardName, player.ipAddress)
            if (ban != null || xbans.backend == null) {
                xbans.sessions.quietQuit(player.ref)
                handler.disconnect(MinecraftRenderer.draw(xbans.denied(player.ref, player.scoreboardName, ban)))
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> xbans.quit(handler.player.ref) }
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { _, sender, _ -> allowed(xbans, server, sender) }
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register { _, source, _ -> source.player?.let { allowed(xbans, server, it) } ?: true }
    }

    private fun allowed(xbans: XBansPlugin, server: FabricBansServer, player: ServerPlayer): Boolean {
        val mute = xbans.muteOf(player.ref) ?: return true
        xbans.mutedMessage(player.ref, mute)?.let { server.send(player.ref, it) }
        return false
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XBansPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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

/**
 * Who's logging in, at the login's query stage: the profile and the connection Minecraft keeps on
 * its login listener, found once by their types (their fields are private, and their names differ
 * between lines), and the profile's uuid and name by either accessor (a record from 1.21.9).
 */
internal object LoginDetails {
    @Volatile
    private var profile: Field? = null

    @Volatile
    private var connection: Field? = null

    fun of(handler: Any): Triple<java.util.UUID, String, String?>? {
        val type = handler.javaClass
        val profileField = profile ?: find(type, "com.mojang.authlib.GameProfile")?.also { profile = it } ?: return null
        val game = profileField.get(handler) ?: return null
        val id = call(game, "getId", "id") as? java.util.UUID ?: return null
        val name = call(game, "getName", "name") as? String ?: id.toString()
        val connectionField = connection ?: find(type, "net.minecraft.network.Connection")?.also { connection = it }
        val address = (connectionField?.get(handler)?.let { call(it, "getRemoteAddress") } as? InetSocketAddress)?.address?.hostAddress
        return Triple(id, name, address)
    }

    private fun find(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.type.name == name }?.let { it.isAccessible = true; return it }
            current = current.superclass
        }
        return null
    }

    private fun call(target: Any, vararg names: String): Any? {
        for (name in names) {
            val method = try {
                target.javaClass.getMethod(name)
            } catch (e: NoSuchMethodException) {
                continue
            }
            return method.invoke(target)
        }
        return null
    }
}

/** xBans' [BansServer] on Fabric: Minecraft's players, on the server thread where Minecraft wants them. */
internal class FabricBansServer(private val platform: FabricPlatform) : BansServer {
    private val zone = ZoneId.systemDefault()

    private fun server(): MinecraftServer? = platform.server

    private fun player(ref: PlayerRef): ServerPlayer? = server()?.player(ref)

    private fun onServer(work: () -> Unit) {
        val server = server() ?: return
        if (server.isSameThread) work() else server.execute(work)
    }

    override fun online(): List<PlayerRef> = server()?.playerList?.players?.map { it.ref }.orEmpty()

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun address(player: PlayerRef): String? = player(player)?.ipAddress

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        onServer { player(player)?.sendSystemMessage(text) }
    }

    override fun console(message: Component) {
        platform.log.info(message.plain())
    }

    override fun kick(player: PlayerRef, screen: Component, quiet: Boolean) {
        val text = MinecraftRenderer.draw(screen)
        onServer { player(player)?.connection?.disconnect(text) }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } ?: false

    // Fabric's permissions API answers one node at a time: 1 to 100, then the round numbers to 1,000.
    override fun weight(player: PlayerRef): Int {
        val p = player(player) ?: return 0
        for (n in WEIGHTS) if (platform.permissions.has(p, Power.WEIGHT_PREFIX + n)) return n
        return 0
    }

    override fun dispatch(line: String) {
        onServer {
            val server = server() ?: return@onServer
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), line)
        }
    }

    // Minecraft has no event before other commands run, so mutes don't look commands up here.
    override fun commandNames(label: String): List<String>? = null

    // An offline-mode server's uuid for a name; an online one's players have joined, which the family's cache knows.
    override fun lookup(name: String): Pending<Pair<PlayerRef, String>?> {
        val server = server() ?: return pendingOf(null)
        if (server.usesAuthentication()) return pendingOf(null)
        val id = java.util.UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
        return pendingOf(PlayerRef.of(id.toKotlinUuid()) to name)
    }

    override fun utcOffsetSeconds(epochMillis: Long): Int = zone.rules.getOffset(java.time.Instant.ofEpochMilli(epochMillis)).totalSeconds

    companion object {
        val WEIGHTS: IntArray = ((1000 downTo 101 step 50) + (100 downTo 1)).toIntArray()
    }
}
