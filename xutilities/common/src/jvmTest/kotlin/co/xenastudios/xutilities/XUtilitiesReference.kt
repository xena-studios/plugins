package co.xenastudios.xutilities

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xutilities.api.event.BackRecordEvent
import co.xenastudios.xutilities.api.event.BackTeleportEvent
import co.xenastudios.xutilities.config.XUtilitiesConfig

/**
 * xUtilities' reference, for `./gradlew :xutilities:common:xenaManifest`: the plugin with its
 * default config on the fake platform, as its tests build it, with no one online.
 */
object XUtilitiesReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val server = FakeServer()
            val platform = FakePlatform()
            val loaded = Loaded(XUtilitiesConfig.defaults, Translations.of(XUtilitiesMessages.catalog, emptyMap()))
            val plugin = XUtilitiesPlugin(platform, server, server, { loaded }, server)
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XUtilitiesPlugin.PLUGIN_ID, XUtilitiesPlugin.NAME, "Every command you need. None you don't.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XUtilitiesConfig,
                catalog = XUtilitiesMessages.catalog,
                events = listOf(
                    EventInfo.of(BackRecordEvent.TYPE, "A spot is about to be saved as where /back takes a player. Cancel it to keep the old one.", since = "3.0.0"),
                    EventInfo.of(BackTeleportEvent.TYPE, "A player used /back and is about to be teleported. Cancel it to keep them where they are.", since = "3.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
