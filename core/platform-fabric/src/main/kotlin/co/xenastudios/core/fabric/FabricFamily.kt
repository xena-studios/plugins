package co.xenastudios.core.fabric

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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.seconds

/**
 * The family on Fabric (DECISIONS.md, "The family"): `config/xena/`, beside each mod's own folder
 * in `config/`, the board every Xena mod on the server shares, and the player cache on it.
 *
 * **The board is found through Fabric Loader's `ObjectShare`,** under `xena:board`: the first Xena
 * mod to start puts a new one there and the others get it back from `putIfAbsent`. The share is a
 * map of JDK objects, so each mod's relocated core, with its own classes, reads the same maps.
 * Unlike Bukkit's services, nothing there goes away while the server runs, so one registration is
 * enough.
 *
 * **Joins, quits and language changes go into the player cache,** which every plugin's messages
 * read the language from. Fabric API has no event for a client changing language, so the
 * languages of the players online are compared every [LANGUAGE_CHECK]: a few field reads, and
 * nothing else on the ticks between. Each Xena mod records them; the first changes the cache and
 * the rest find nothing to change.
 *
 * Made by [FabricPlatform.family] as the mod starts. It reads `config/xena/config.yml` there,
 * while mods load and before the server's thread exists, and [reload] reads it again on a reload,
 * from an async task.
 */
public class FabricFamily internal constructor(private val platform: FabricPlatform) {

    /** The family's folder, `config/xena/`, and its settings. */
    public val family: Family = Family(FolderStorage(FabricLoader.getInstance().configDir.resolve(FOLDER)), board(platform), JvmYaml)

    /** What loading `config/xena/config.yml` found as the mod started. */
    public val loaded: FamilyLoad = family.load()

    /**
     * The family's player cache: names, last seen, languages, by UUID. Kept in `config/xena/`
     * (`players.records`); a plugin with the family's shared database passes its own cache
     * instead, for now (the shared database's store needs the plugin's open database).
     */
    public val players: PlayerCache = PlayerCache(
        family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log,
        platform.modId, family.cacheSize,
    )

    init {
        for (problem in loaded.problems) platform.log.warn("config/xena/config.yml: $problem")
        loaded.error?.let { platform.log.warn("config/xena/config.yml couldn't be read, so the family's defaults are used: $it") }
        players.load()
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            players.joined(player.ref, player.scoreboardName, FabricLanguages.clientLocale(player))
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> players.left(handler.player.ref) }
        platform.scheduler.every(Where.Global, LANGUAGE_CHECK) {
            val server = platform.server ?: return@every
            for (player in server.playerList.players) recordLanguage(player)
        }
    }

    /** Reads `config/xena/config.yml` again: on a plugin's reload, off the server's threads. */
    public fun reload(): FamilyLoad = family.load()

    /** The client's language for a player who just joined or changed it, as the cache keeps it. */
    private fun recordLanguage(player: ServerPlayer) {
        val locale = FabricLanguages.clientLocale(player) ?: return
        val ref: PlayerRef = player.ref
        if (players.clientLocale(ref) != locale) players.languageChanged(ref, locale)
    }

    public companion object {
        /** The family's folder in `config/`. */
        public const val FOLDER: String = "xena"

        /** How often the players' languages are compared with what the cache has. */
        public val LANGUAGE_CHECK: kotlin.time.Duration = 5.seconds

        private fun board(platform: FabricPlatform): JvmFamilyBoard {
            val share = FabricLoader.getInstance().objectShare
            val made = JvmFamilyBoard.newRoot()
            val found = share.putIfAbsent(JvmFamilyBoard.NAME, made) ?: made
            val board = if (!JvmFamilyBoard.isRoot(found)) {
                // Another mod took the name: the family still works within this mod, alone.
                platform.log.warn("${JvmFamilyBoard.NAME} in Fabric's object share isn't the family's board, so this mod keeps its own")
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
