package co.xenastudios.xteleport.rtp

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.permission.Permissible
import co.xenastudios.xteleport.TeleportServer
import co.xenastudios.xteleport.WorldInfo
import co.xenastudios.xteleport.api.RtpSettings
import co.xenastudios.xteleport.config.RtpWorld
import co.xenastudios.xteleport.config.XTeleportConfig
import co.xenastudios.core.common.scheduler.Scheduler
import kotlin.concurrent.Volatile

/** `/rtp`'s settings as the search wants them, read once per config, and the search itself. */
public class Rtp(private val server: TeleportServer, scheduler: Scheduler, enabled: () -> Boolean) {
    private val search = Search(server, scheduler, enabled)

    @Volatile
    private var config: Config = XTeleportConfig.defaults

    @Volatile
    private var blockedBiomes: Set<String> = emptySet()

    /** Takes the config's settings, on startup and each reload. */
    public fun configure(config: Config) {
        this.config = config
        blockedBiomes = config[XTeleportConfig.rtp.blockedBiomes].map { it.trim().lowercase().substringAfter(':') }.filter { it.isNotEmpty() }.toSet()
    }

    /** The world's settings, or `null` if `/rtp` can't send anyone there: not listed, or off. */
    public fun rules(world: WorldInfo): RtpWorld? {
        val worlds = config[XTeleportConfig.rtp.worlds]
        val rules = worlds[world.ref.name] ?: worlds.entries.firstOrNull { server.world(it.key)?.ref == world.ref }?.value
        return rules?.takeIf { it.enabled }
    }

    /** Where `/rtp` may send [who] in [world], or `null` if it may not (not listed, off, or a permission they lack). */
    public fun allowed(who: Permissible, world: WorldInfo): RtpWorld? {
        val rules = rules(world) ?: return null
        return if (rules.permission.isNotBlank() && !who.hasPermission(rules.permission)) null else rules
    }

    public fun begin(player: PlayerRef?): Search.Start = search.begin(player, config[XTeleportConfig.rtp.maxSearches])

    public fun end(player: PlayerRef?) {
        search.end(player)
    }

    public fun searching(player: PlayerRef): Boolean = search.searching(player)

    internal val running: Int get() = search.runningNow

    /** Searches [world] by its settings. Call [begin] first and [end] once it settles. Any thread. */
    public fun find(world: WorldInfo, rules: RtpWorld): Pending<Search.Found?> {
        val centerX = if (rules.centerOnSpawn) world.spawn.blockX else rules.centerX
        val centerZ = if (rules.centerOnSpawn) world.spawn.blockZ else rules.centerZ
        return search.find(world, Search.Area(
            centerX, centerZ, rules.minRadius, rules.maxRadius, config[XTeleportConfig.rtp.attempts],
            config[XTeleportConfig.rtp.onlyGenerated], rules.allowMainIsland, blockedBiomes,
        ))
    }

    /** A search for the API, by the caller's settings, with the config's blocked biomes. */
    public fun find(world: WorldInfo, asked: RtpSettings): Pending<Search.Found?> = search.find(
        world, Search.Area(asked.centerX, asked.centerZ, asked.minRadius, asked.maxRadius, asked.attempts, asked.onlyGenerated, false, blockedBiomes),
    )
}
