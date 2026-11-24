package co.xenastudios.xtwin.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.twin.XTwin
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentMap

/**
 * xTwin on Bukkit, for the family test beside xSample: its own core, relocated to
 * `co.xenastudios.xtwin.core`, on the board and in `plugins/Xena/` with xSample's.
 */
public class XTwinBukkit(private val plugin: JavaPlugin) : PluginEntry {

    private var platform: BukkitPlatform? = null
    private var twin: XTwin? = null

    override fun enable() {
        val platform = BukkitPlatform.create(plugin, development = true)
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, "xtwin")
        platform.record(cache)
        val twin = XTwin(platform, family, cache, platform.commands, about = platform::about) { servicesShared(platform) }
        this.platform = platform
        this.twin = twin
        platform.permissions.sync(twin.admin.permissions.permissions)
        platform.scheduler.now(Where.Global) { twin.enable() }
        // bStats beside xSample's, each relocated into its plugin (boot.sh turns it off).
        Integer.getInteger("xtwin.bstats")?.let(platform::metrics)
        platform.scheduler.run(Where.Async) {
            family.load().error?.let { platform.log.warn(it.message) }
            cache.load()
        }
    }

    // Every Xena plugin registers the server's one board in Bukkit's services: xSample's
    // registration and this one's must be the same object as the board this plugin uses.
    private fun servicesShared(platform: BukkitPlatform): List<String> {
        val root = (platform.family.board as JvmFamilyBoard).root
        val registrations = plugin.server.servicesManager.getRegistrations(ConcurrentMap::class.java)
        val by = registrations.groupBy { it.plugin.name }
        return listOf("xSample", "xTwin").mapNotNull { name ->
            val theirs = by[name].orEmpty()
            when {
                theirs.isEmpty() -> "$name registered no board"
                theirs.any { it.provider !== root } -> "$name registered a board that isn't this plugin's"
                else -> null
            }
        }
    }

    override fun disable() {
        val platform = platform ?: return
        twin?.disable()
        platform.close()
        this.platform = null
        twin = null
    }
}
