package co.xenastudios.xteleport

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xteleport.api.event.HomeSetEvent
import co.xenastudios.xteleport.api.event.PreTeleportEvent
import co.xenastudios.xteleport.api.event.TeleportEvent
import co.xenastudios.xteleport.api.event.TpaRequestEvent
import co.xenastudios.xteleport.config.XTeleportConfig

/**
 * xTeleport's reference, for `./gradlew :xteleport:common:xenaManifest`: the plugin with its default
 * config on the fake platform, as its tests build it.
 */
object XTeleportReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = FakePlatform()
            val server = FakeTeleportServer(platform.scheduler)
            val loaded = Loaded(XTeleportConfig.defaults, Translations.of(XTeleportMessages.catalog, emptyMap()))
            val plugin = XTeleportPlugin(platform, server, { loaded }, server, server, essentials = EssentialsFiles(java.nio.file.Paths.get("Essentials")))
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XTeleportPlugin.PLUGIN_ID, XTeleportPlugin.NAME, "Every teleport your server needs.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XTeleportConfig,
                placeholders = plugin.placeholders.set(),
                catalog = XTeleportMessages.catalog,
                events = listOf(
                    EventInfo.of(PreTeleportEvent.TYPE, "A player is about to be teleported, after the warmup and the safety check. Cancel it to stop the teleport.", since = "1.0.0"),
                    EventInfo.of(TeleportEvent.TYPE, "A player arrived, by one of xTeleport's teleports or one asked for through the API.", since = "1.0.0"),
                    EventInfo.of(HomeSetEvent.TYPE, "A player is setting a home with /sethome. Cancel it to refuse.", since = "1.0.0"),
                    EventInfo.of(TpaRequestEvent.TYPE, "A player is sending a teleport request. Cancel it to refuse.", since = "1.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
