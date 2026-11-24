package co.xenastudios.core.bukkit

import co.xenastudios.core.bukkit.modern.FoliaScheduler
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.jvm.platform.FolderStorage
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

/**
 * The core's [Platform] on a Bukkit-family server, from Spigot 1.8.8 to the newest Paper and
 * Folia: made once in the plugin's `enable` with [create], which looks at the server once
 * ([BukkitServer]) and picks every path from that, never per call (ROADMAP.md, Architecture).
 *
 * | | Folia | Paper | Spigot | CraftBukkit |
 * |---|---|---|---|---|
 * | [scheduler] | Folia's region, entity, global and async schedulers | Bukkit's, async on threads of its own | the same | the same |
 * | [text] | Adventure | Adventure from 1.16.5, then Spigot's | `BaseComponent[]` | `§` strings |
 * | [languages] | the client's locale, and its changes from 1.12 | the same | the same; before 1.12, `spigot().getLocale()` | from 1.12 |
 *
 * Besides the [Platform], what a Bukkit plugin registers through: [commands] on Bukkit's
 * command map, [permissions] in its plugin manager, [events] bridged to Bukkit's, and [players]
 * for the `player` argument. [close] undoes all of it as the plugin is disabled.
 */
public class BukkitPlatform private constructor(
    /** The plugin it's for. */
    public val plugin: Plugin,
    /** What the server is and has, found at startup. */
    public val server: BukkitServer,
    override val development: Boolean,
) : Platform {

    override val name: String = server.toString()

    override val threading: Threading = if (server.flavor == BukkitServer.Flavor.FOLIA) Threading.REGIONS else Threading.MAIN_THREAD

    override val capabilities: Set<Capability> = setOf(Capability.NATIVE_EVENTS, Capability.SQL, Capability.SHARED_API)

    override val log: Log = JulLog(plugin.logger)

    override val clock: Clock = SystemClock

    /** `plugins/<Plugin>/`. */
    override val storage: Storage = FolderStorage(plugin.dataFolder.toPath())

    private val main: MainThreadScheduler? =
        if (threading == Threading.REGIONS) null else MainThreadScheduler(plugin, BukkitPlayers.entities(plugin.server, server), AsyncExecutor(plugin.name, log))

    override val scheduler: Scheduler = main ?: folia()

    /** How messages reach players and the console here. */
    public val text: BukkitText = BukkitText.pick(server)

    /** Each player's language, from their client; the console has none, and reads in `default-language`. */
    public val languages: LanguageSource<CommandSender> = BukkitLanguages.pick(server, plugin, scheduler)

    /** The online players each command source can see, for the `player` argument. */
    public val players: OnlinePlayers = BukkitPlayers(plugin.server)

    /** The plugin's commands, on Bukkit's command map. */
    public val commands: BukkitCommands = BukkitCommands.create(plugin, plugin.server, text, log, refreshes = server.atLeast(1, 13))

    /** The plugin's permissions, in Bukkit's plugin manager. */
    public val permissions: PermissionRegistrar = BukkitPermissions(plugin.server.pluginManager)

    /** The plugin's events, fired as Bukkit events too. */
    public val events: BukkitEvents = BukkitEvents(plugin.server)

    // Its own function, so only Folia loads the class that calls Folia's API.
    private fun folia(): Scheduler = FoliaScheduler(plugin, log)

    /**
     * Undoes what was registered here, as the plugin is disabled: its commands, permissions
     * and event bridges, its waiting tasks, and its async threads, which get five seconds to
     * finish what's running. Disable the plugin's modules first, which may still schedule work.
     */
    public fun close() {
        events.close()
        commands.clear()
        permissions.sync(emptyList())
        (languages as? AutoCloseable)?.close()
        main?.close()
        (scheduler as? AutoCloseable)?.close()
    }

    public companion object {
        /**
         * The platform for [plugin], from what its server is. A [development] build checks
         * threads (`checkThread`) and throws where code would break on Folia; release builds
         * don't (DECISIONS.md, "The platform layer").
         */
        @JvmStatic
        @JvmOverloads
        public fun create(plugin: Plugin, development: Boolean = false): BukkitPlatform =
            BukkitPlatform(plugin, BukkitServer.detect(plugin.server), development)
    }
}
