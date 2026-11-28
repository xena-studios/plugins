package co.xenastudios.xlimbo

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xlimbo.api.event.EnteredLimboEvent
import co.xenastudios.xlimbo.api.event.MainServerStatusEvent
import co.xenastudios.xlimbo.api.event.PreEnterLimboEvent
import co.xenastudios.xlimbo.api.event.PreReturnEvent
import co.xenastudios.xlimbo.api.event.ReturnedEvent
import co.xenastudios.xlimbo.config.XLimboConfig

/** xLimbo's reference, for `./gradlew :xlimbo:common:xenaManifest`: the plugin with its default config on the fake platform. */
object XLimboReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = FakePlatform(Threading.MAIN_THREAD)
            val server = FakeLimboServer(platform.scheduler)
            val loaded = Loaded(XLimboConfig.defaults, Translations.of(XLimboMessages.catalog, emptyMap()))
            val plugin = XLimboPlugin(platform, server, { loaded }, server, server, { _, _ -> false })
            server.plugin = plugin
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XLimboPlugin.PLUGIN_ID, XLimboPlugin.NAME, "Never drop a player again.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XLimboConfig,
                catalog = XLimboMessages.catalog,
                events = listOf(
                    EventInfo.of(PreEnterLimboEvent.TYPE, "A player on this server is about to be sent into limbo. Cancel it to keep them where they are.", since = "3.0.0"),
                    EventInfo.of(EnteredLimboEvent.TYPE, "A player arrived in limbo, with why as far as xLimbo can tell.", since = "3.0.0"),
                    EventInfo.of(PreReturnEvent.TYPE, "A player is about to be sent back. Cancel it to keep them in limbo.", since = "3.0.0"),
                    EventInfo.of(ReturnedEvent.TYPE, "A player was sent back, through the proxy or by a teleport.", since = "3.0.0"),
                    EventInfo.of(MainServerStatusEvent.TYPE, "Server-check saw the main server go up or down.", since = "3.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
