package co.xenastudios.core.neoforge

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.FamilyLoad
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.jvm.platform.FolderStorage
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The family on NeoForge (DECISIONS.md, "The family"): `config/xena/`, beside each mod's own
 * folder in `config/`, the board every Xena mod on the server shares, and the player cache on it,
 * as on Fabric.
 *
 * **The board is found in the JVM's system properties,** under `xena:board`: NeoForge has nothing
 * like Fabric Loader's object share, and its mods are made at the same time on several threads,
 * so the first Xena mod's board must be taken atomically: `putIfAbsent` on the properties is. The
 * value is a map of JDK objects, so each mod's relocated core, with its own classes, reads the
 * same maps. It's never a string, so `System.getProperty` and the property listings, which only
 * read strings, never see it.
 *
 * **Joins, quits and language changes go into the player cache,** which every plugin's messages
 * read the language from: the languages of the players online are compared every
 * [LANGUAGE_CHECK], as on Fabric, and on Forge 1.20.1 a joining player's more often for its
 * first seconds, until its client's options have come.
 *
 * Made by [NeoForgePlatform.family] as the mod starts. It reads `config/xena/config.yml` there,
 * while mods load and before the server's thread exists, and [reload] reads it again on a reload,
 * from an async task.
 */
public class NeoForgeFamily internal constructor(private val platform: NeoForgePlatform) {

    /** The family's folder, `config/xena/`, and its settings. */
    public val family: Family = Family(FolderStorage(FMLPaths.CONFIGDIR.get().resolve(FOLDER)), board(platform), JvmYaml)

    /** What loading `config/xena/config.yml` found as the mod started. */
    public val loaded: FamilyLoad = family.load()

    /**
     * The family's player cache: names, last seen, languages, by UUID. Kept in `config/xena/`
     * (`players.records`), as on Fabric.
     */
    public val players: PlayerCache = PlayerCache(
        family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log,
        platform.modId, family.cacheSize,
    )

    init {
        for (problem in loaded.problems) platform.log.warn("config/xena/config.yml: $problem")
        loaded.error?.let { platform.log.warn("config/xena/config.yml couldn't be read, so the family's defaults are used: $it") }
        players.load()
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            players.joined(player.ref, player.scoreboardName, NeoForgeLanguages.clientLocale(player))
            //? if forge {
            /*// Forge 1.20.1 hears the client's language from its options packet, which comes just
            // after the login: it's read again every quarter second for the first two seconds.
            var left = 8
            platform.scheduler.run(Where.Global, period = 250.milliseconds) { task ->
                if (player.hasDisconnected() || left-- <= 0) task.cancel() else recordLanguage(player)
            }
            *///?}
        }
        //? if forge {
        /*NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.Clone::class.java) { event ->
            val original = event.original as? ServerPlayer
            val respawned = event.entity as? ServerPlayer
            if (original != null && respawned != null) NeoForgeLanguages.respawned(original, respawned)
        }
        *///?}
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            players.left(player.ref)
        }
        platform.scheduler.every(Where.Global, LANGUAGE_CHECK) {
            val server = platform.server ?: return@every
            for (player in server.playerList.players) recordLanguage(player)
        }
    }

    /** Reads `config/xena/config.yml` again: on a plugin's reload, off the server's threads. */
    public fun reload(): FamilyLoad = family.load()

    /** The client's language for a player who just joined or changed it, as the cache keeps it. */
    private fun recordLanguage(player: ServerPlayer) {
        val locale = NeoForgeLanguages.clientLocale(player) ?: return
        val ref: PlayerRef = player.ref
        if (players.clientLocale(ref) != locale) players.languageChanged(ref, locale)
    }

    public companion object {
        /** The family's folder in `config/`. */
        public const val FOLDER: String = "xena"

        /** How often the players' languages are compared with what the cache has. */
        public val LANGUAGE_CHECK: kotlin.time.Duration = 5.seconds

        private fun board(platform: NeoForgePlatform): JvmFamilyBoard {
            val made = JvmFamilyBoard.newRoot()
            val found = System.getProperties().putIfAbsent(JvmFamilyBoard.NAME, made) ?: made
            val board = if (!JvmFamilyBoard.isRoot(found)) {
                // Something else took the name: the family still works within this mod, alone.
                platform.log.warn("${JvmFamilyBoard.NAME} in the system properties isn't the family's board, so this mod keeps its own")
                JvmFamilyBoard(made)
            } else {
                @Suppress("UNCHECKED_CAST")
                JvmFamilyBoard(found as ConcurrentMap<String, Any>)
            }
            // Where the mod's database finds the board for the family's backups.
            JvmFamilyBoard.server = board
            return board
        }
    }
}
