package co.xenastudios.core.sponge

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
import org.spongepowered.api.Sponge
import org.spongepowered.api.entity.living.player.server.ServerPlayer
import org.spongepowered.api.event.network.ServerSideConnectionEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The family on Sponge (DECISIONS.md, "The family"): `config/xena/`, beside each plugin's own
 * folder in `config/`, the board every Xena plugin on the server shares, and the player cache on it.
 *
 * **The board is found on the other Xena plugins' main objects** ([SpongeBootstrap]), from
 * Sponge's plugin manager: the first Xena plugin to start makes it, the others take it, and each
 * hands it out from then on.
 *
 * **Joins, quits and language changes go into the player cache.** Sponge's event for a client's
 * settings changed shape between API lines, so, as on Fabric, the languages of the players online
 * are compared every [LANGUAGE_CHECK].
 *
 * Made by [SpongePlatform.family] as the plugin is constructed, which reads
 * `config/xena/config.yml` there; [reload] reads it again on a reload, from an async task.
 */
public class SpongeFamily internal constructor(private val platform: SpongePlatform) {

    /** The family's folder, `config/xena/`, and its settings. */
    public val family: Family = Family(FolderStorage(platform.configRoot.resolve(FOLDER)), board(platform), JvmYaml)

    /** What loading `config/xena/config.yml` found as the plugin started. */
    public val loaded: FamilyLoad = family.load()

    /** The family's player cache: names, last seen, languages, by UUID, kept in `config/xena/`. */
    public val players: PlayerCache = PlayerCache(
        family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log,
        platform.id, family.cacheSize,
    )

    init {
        for (problem in loaded.problems) platform.log.warn("config/xena/config.yml: $problem")
        loaded.error?.let { platform.log.warn("config/xena/config.yml couldn't be read, so the family's defaults are used: $it") }
        players.load()
        platform.listen(ServerSideConnectionEvent.Join::class.java) { event ->
            val player = event.player()
            players.joined(player.ref, player.name(), SpongeLanguages.clientLocale(player))
        }
        SpongeQuits.listen(platform) { player -> players.left(player.ref) }
        platform.scheduler.every(Where.Global, LANGUAGE_CHECK) {
            val server = runningServer() ?: return@every
            for (player in server.onlinePlayers()) recordLanguage(player)
        }
    }

    /** Reads `config/xena/config.yml` again: on a plugin's reload, off the server's threads. */
    public fun reload(): FamilyLoad = family.load()

    private fun recordLanguage(player: ServerPlayer) {
        val locale = SpongeLanguages.clientLocale(player)
        val ref: PlayerRef = player.ref
        if (players.clientLocale(ref) != locale) players.languageChanged(ref, locale)
    }

    public companion object {
        /** The family's folder in `config/`. */
        public const val FOLDER: String = "xena"

        /** How often the players' languages are compared with what the cache has. */
        public val LANGUAGE_CHECK: Duration = 5.seconds

        private fun board(platform: SpongePlatform): JvmFamilyBoard {
            val others = Sponge.pluginManager().plugins().filter { it !== platform.bootstrap.container }.map { runCatching { it.instance() }.getOrNull() }
            val board = JvmFamilyBoard.fromShims(others, SpongeBootstrap.SHIM)
            platform.bootstrap.share(board.root)
            // Where the plugin's database finds the board for the family's backups.
            JvmFamilyBoard.server = board
            return board
        }
    }
}
