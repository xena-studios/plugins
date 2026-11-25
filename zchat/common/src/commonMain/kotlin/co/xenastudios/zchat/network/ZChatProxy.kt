package co.xenastudios.zchat.network

import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ReloadReport
import co.xenastudios.core.common.command.Reloader
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.zchat.Texts
import co.xenastudios.zchat.ZChatMessages
import co.xenastudios.zchat.ZChatPlugin
import kotlin.concurrent.Volatile

/**
 * zChat on Velocity or BungeeCord, the same on both: the [Relay] between the network's servers,
 * and `/zchatproxy` (alias `/zcp`) with `reload` and `debug`. It has no config of its own: every
 * server decides what crosses ([co.xenastudios.zchat.config.ZChatConfig.network]), and the proxy
 * carries it. Its command isn't `/zchat`, which the proxy would take from every server's own.
 */
public class ZChatProxy(
    private val platform: Platform,
    channel: ProxyChannel,
    events: EventBus,
    languages: LanguageSource,
    /** Reads `lang/` and the family's settings, off the proxy's threads. */
    private val translations: () -> Translations,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    about: () -> Map<String, String> = { emptyMap() },
    paste: PasteService? = null,
    public val version: String = ZChatPlugin.VERSION,
) {
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)
    public val texts: Texts = Texts(languages)
    public val relay: Relay = Relay(channel, events, log)

    @Volatile
    private var problems: List<String> = emptyList()

    // The admin subcommands and the command tree are made on first use, by [start] on the
    // plugin's own thread, not as the plugin is made on the proxy's.
    public val admin: AdminCommands by lazy {
        AdminCommands(
            ZChatPlugin.PLUGIN_ID, ZChatPlugin.NAME, platform,
            Reloader { ReloadReport(reload(), emptyList(), emptyMap()) },
            debug = Debug(platform, "${ZChatPlugin.NAME} $version", listOf(HealthChecks.threading(platform), HealthChecks.config { problems }), about = about, errors = log, files = emptyList(), paste = paste),
            text = texts.admin,
        )
    }

    public val dispatcher: CommandDispatcher by lazy {
        CommandDispatcher(
            commands {
                root(COMMAND, "Shows zChat's version on the proxy, and holds its admin subcommands.", aliases = listOf("zcp")) {
                    executes { ctx -> texts.reply(ctx.source, ZChatMessages.proxyInfo, "version" to version, "platform" to platform.name, "players" to relay.players) }
                    mount(admin)
                }
            },
            platform.clock, log,
        )
    }

    /** Makes and registers the command and its permissions. On the plugin's own thread. */
    public fun start() {
        permissionRegistrar?.sync(admin.permissions.permissions)
        registrar?.sync(dispatcher)?.forEach(log::warn)
    }

    /** Uses the lang files read since [start]. */
    public fun apply(loaded: Translations) {
        texts.translations = loaded
        problems = loaded.problems.map { it.message }
    }

    private fun reload(): List<String> {
        apply(translations())
        return problems
    }

    public fun stop() {
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
    }

    public companion object {
        /** The proxy's command. */
        public const val COMMAND: String = "zchatproxy"
    }
}
