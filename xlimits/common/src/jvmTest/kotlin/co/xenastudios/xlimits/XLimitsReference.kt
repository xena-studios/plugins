package co.xenastudios.xlimits

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xlimits.api.event.FarmRestoredEvent
import co.xenastudios.xlimits.api.event.FarmSlowedEvent
import co.xenastudios.xlimits.api.event.LimitReachedEvent
import co.xenastudios.xlimits.api.event.ProfileChangedEvent
import co.xenastudios.xlimits.config.XLimitsConfig

/** xLimits' reference, for `./gradlew :xlimits:common:xenaManifest`: the plugin with its default config on the fake platform. */
object XLimitsReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = FakePlatform()
            val server = FakeLimitsServer(platform.scheduler)
            val loaded = Loaded(XLimitsConfig.defaults, Translations.of(XLimitsMessages.catalog, emptyMap()))
            val plugin = XLimitsPlugin(platform, server, { loaded }, server, server, importers = { LimitImporter.all(java.nio.file.Paths.get("plugins"), it) })
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XLimitsPlugin.PLUGIN_ID, XLimitsPlugin.NAME, "Farm, entity and redstone limits.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XLimitsConfig,
                catalog = XLimitsMessages.catalog,
                events = listOf(
                    EventInfo.of(LimitReachedEvent.TYPE, "Something is about to be refused at a limit. Cancel it to allow it after all.", since = "1.0.0"),
                    EventInfo.of(FarmSlowedEvent.TYPE, "A crammed farm's mobs are about to be slowed. Cancel it to leave them be.", since = "1.0.0"),
                    EventInfo.of(FarmRestoredEvent.TYPE, "A farm's mobs went back to normal as their count fell.", since = "1.0.0"),
                    EventInfo.of(ProfileChangedEvent.TYPE, "A load profile turned on or off.", since = "1.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
