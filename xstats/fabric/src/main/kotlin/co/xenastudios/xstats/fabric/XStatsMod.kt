package co.xenastudios.xstats.fabric

import co.xenastudios.core.api.PlayerRef
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
import co.xenastudios.xstats.FamilyHooks
import co.xenastudios.xstats.JvmDatabase
import co.xenastudios.xstats.JvmExtras
import co.xenastudios.xstats.JvmFiles
import co.xenastudios.xstats.JvmZones
import co.xenastudios.xstats.StatsServer
import co.xenastudios.xstats.XStatsPlugin
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.stat.Vanilla
import co.xenastudios.xstats.stat.VanillaReader
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stat
import net.minecraft.stats.Stats
import net.minecraft.world.level.storage.LevelResource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier

/**
 * xStats as a server-side Fabric mod: the shared plugin on platform-fabric. `config.yml` (in
 * `config/xstats/`) is read as the mod starts, so its commands are registered when Minecraft builds
 * its command tree; the database is opened (its driver downloaded the first time: Fabric servers have
 * none) and every player read off the server thread. Minecraft's own counters are read from each
 * player's stat counter at each save, on the server thread; joins and quits are the only events.
 * Placeholders join Text Placeholder API when it's installed (`%xstats:stat kills%`). There are no
 * boards in the world (Bukkit's text displays) and no PlaceholderAPI stats here.
 */
public object XStatsMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XStatsPlugin.PLUGIN_ID, XStatsPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = object : JvmFiles(folder, family.family, null, platform.log) {
            override fun shipped(): Map<LanguageTag, String> = this@XStatsMod.shipped()
        }
        val version = FabricLoader.getInstance().getModContainer(XStatsPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XStatsPlugin.VERSION).substringBefore('+')
        val server = FabricStatsServer(platform, family.players)
        val config = files.config()
        val drivers = Drivers(XStatsMod::class.java.classLoader, platform.drivers(XStatsMod::class.java.classLoader))
        val gameDir = FabricLoader.getInstance().gameDir
        val xstats = XStatsPlugin(
            platform, server, files, family.players, platform.players, family.players,
            opener = JvmDatabase(family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            makeExtras = { JvmExtras(folder, World(platform), gameDir, NoNames, XStatsMod::class.java.classLoader) },
            zones = JvmZones,
            version = version,
        )
        platform.commands.feedback = xstats.texts.feedback
        xstats.start(config)
        xstats.open()
        platform.scheduler.run(Where.Async) { _ ->
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xstats.apply(loaded) }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            xstats.joined(player.ref, player.scoreboardName, 0, player.yRot, player.xRot)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> xstats.quit(handler.player.ref) }
        var hooks: FamilyHooks? = null
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            // Every mod shares one class loader, so the family's APIs are found by name once mods
            // are up, and their stats registered once the database is open.
            xstats.ready.thenAccept { open ->
                if (open == true) platform.scheduler.run(Where.Global) { _ -> hooks = FamilyHooks(xstats, Shared).also { it.hookAll() } }
            }
            if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
                try {
                    TextPlaceholders.register(xstats, Identifier.tryParse("xstats:stat")!!, Identifier.tryParse("xstats:kd")!!)
                } catch (e: Exception) {
                    xstats.log.warn("Couldn't add xStats' placeholders to Text Placeholder API: $e")
                } catch (e: LinkageError) {
                    xstats.log.warn("Couldn't add xStats' placeholders to Text Placeholder API: $e")
                }
            }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xstats.updates = platform.checkUpdates(XStatsPlugin.PLUGIN_ID, xstats.admin.updates, XStatsConfig.updates.settings(config.config)) {
            xstats.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { minecraft ->
            hooks?.close()
            // Players still online: one last reading each, on the server thread, without placeholders.
            val readings = minecraft.playerList.players.mapNotNull { server.read(it.ref, xstats.catalog.snapshot, quit = true, placeholders = false) }
            try {
                xstats.stop(readings).get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xStats' last save didn't finish in time: $e")
            }
        }
        platform.log.info("xStats ${xstats.version} on ${platform.name}")
    }

    /** The main world's folder, once the server is up: where Minecraft keeps every player's stats file. */
    private class World(private val platform: FabricPlatform) : () -> Path? {
        override fun invoke(): Path? = platform.server?.getWorldPath(LevelResource.ROOT)
    }

    private object NoNames : (PlayerRef) -> String? {
        override fun invoke(player: PlayerRef): String? = null
    }

    /** Every Fabric mod shares one class loader: the family's APIs are in it, when their mods are. */
    private object Shared : (String) -> ClassLoader? {
        override fun invoke(name: String): ClassLoader? = if (FabricLoader.getInstance().isModLoaded(name.lowercase())) XStatsMod::class.java.classLoader else null
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XStatsPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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
 * xStats' [StatsServer] on Fabric: Minecraft's players, read on the server thread from their own
 * stat counters (`ServerPlayer.getStats()`), a map lookup each, with Minecraft's registries for the
 * blocks, items and mobs a `mined:*` adds up.
 */
internal class FabricStatsServer(private val platform: FabricPlatform, private val cache: PlayerCache) : StatsServer, VanillaReader {
    private fun server(): MinecraftServer? = platform.server

    private fun player(ref: PlayerRef): ServerPlayer? = server()?.player(ref)

    override val vanilla: VanillaReader get() = this

    override fun about(): Map<String, String> = platform.about()

    override fun online(): List<PlayerRef> = server()?.playerList?.players?.map { it.ref }.orEmpty()

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        val server = server() ?: return
        if (server.isSameThread) player(player)?.sendSystemMessage(text) else server.execute { player(player)?.sendSystemMessage(text) }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } ?: false

    /** The server's ban list, by name (Minecraft keeps names there), matched to players through the family's cache. */
    override fun banned(): Set<PlayerRef> = server()?.playerList?.bans?.userList?.mapNotNullTo(HashSet()) { cache.find(it)?.player }.orEmpty()

    override fun customStats(): Int = BuiltInRegistries.CUSTOM_STAT.size()

    private class Prepared(val stats: List<Pair<Vanilla.Term, List<Stat<*>>>>, override val missing: List<Vanilla.Term>) : VanillaReader.Prepared

    override fun prepare(terms: List<Vanilla.Term>): VanillaReader.Prepared {
        val ready = ArrayList<Pair<Vanilla.Term, List<Stat<*>>>>()
        val missing = ArrayList<Vanilla.Term>()
        for (term in terms) {
            val stats = stats(term)
            if (stats.isEmpty()) missing += term
            ready += term to stats
        }
        return Prepared(ready, missing)
    }

    private fun stats(term: Vanilla.Term): List<Stat<*>> {
        if (term.category == Vanilla.Category.CUSTOM) {
            for (key in listOf(term.name) + Vanilla.olderKeys(term.name)) {
                val id = Identifier.tryParse("minecraft:$key") ?: continue
                if (!BuiltInRegistries.CUSTOM_STAT.containsKey(id)) continue
                // The registry's own instance: a stat's name is looked up by it.
                //? if registry_lookup {
                val registered = BuiltInRegistries.CUSTOM_STAT.getValue(id)
                //?} else
                //val registered = BuiltInRegistries.CUSTOM_STAT.get(id)
                if (registered != null) return listOf(Stats.CUSTOM.get(registered))
            }
            return emptyList()
        }
        val out = ArrayList<Stat<*>>()
        when (term.category) {
            Vanilla.Category.MINED -> for (block in BuiltInRegistries.BLOCK) {
                if (term.all || BuiltInRegistries.BLOCK.getKey(block).path == term.name) out += Stats.BLOCK_MINED.get(block)
            }
            Vanilla.Category.KILLED, Vanilla.Category.KILLED_BY -> for (type in BuiltInRegistries.ENTITY_TYPE) {
                if (term.all || BuiltInRegistries.ENTITY_TYPE.getKey(type).path == term.name) {
                    out += if (term.category == Vanilla.Category.KILLED) Stats.ENTITY_KILLED.get(type) else Stats.ENTITY_KILLED_BY.get(type)
                }
            }
            else -> {
                val statType = when (term.category) {
                    Vanilla.Category.CRAFTED -> Stats.ITEM_CRAFTED
                    Vanilla.Category.USED -> Stats.ITEM_USED
                    Vanilla.Category.BROKEN -> Stats.ITEM_BROKEN
                    Vanilla.Category.PICKED_UP -> Stats.ITEM_PICKED_UP
                    else -> Stats.ITEM_DROPPED
                }
                for (item in BuiltInRegistries.ITEM) {
                    if (term.all || BuiltInRegistries.ITEM.getKey(item).path == term.name) out += statType.get(item)
                }
            }
        }
        return out
    }

    override fun read(player: PlayerRef, stats: Catalog.Snapshot, quit: Boolean, placeholders: Boolean): Event.Sample? {
        val online = player(player) ?: return null
        val counter = online.stats
        val raw = LongArray(stats.width) { Event.Sample.NOT_READ }
        for (stat in stats.stats) {
            val source = stat.source as? StatDef.Vanillas ?: continue
            val prepared = source.prepared as? Prepared ?: continue
            var sum = 0L
            for ((term, each) in prepared.stats) {
                var value = 0L
                for (one in each) value += counter.getValue(one)
                sum += Vanilla.stored(term, value)
            }
            raw[stat.index] = sum
        }
        return Event.Sample(player, online.scoreboardName, System.currentTimeMillis(), raw, online.yRot, online.xRot,
            platform.permissions.has(online, "xstats.leaderboard.exclude"), quit)
    }
}
