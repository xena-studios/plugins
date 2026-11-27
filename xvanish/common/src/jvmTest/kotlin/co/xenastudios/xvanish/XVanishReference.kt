package co.xenastudios.xvanish

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xvanish.api.event.UnvanishedEvent
import co.xenastudios.xvanish.api.event.VanishEvent
import co.xenastudios.xvanish.api.event.VanishedEvent
import co.xenastudios.xvanish.config.XVanishConfig

/** xVanish's reference, for `./gradlew :xvanish:common:xenaManifest`: the plugin with its default config on the fake platform. */
object XVanishReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = FakePlatform()
            val server = FakeVanishServer(platform.scheduler)
            val loaded = Loaded(XVanishConfig.defaults, Translations.of(XVanishMessages.catalog, emptyMap()))
            val plugin = XVanishPlugin(platform, server, { loaded }, server, server, importers = { VanishImporter.all(java.nio.file.Paths.get("plugins"), it) })
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XVanishPlugin.PLUGIN_ID, XVanishPlugin.NAME, "Vanish for staff.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XVanishConfig,
                placeholders = plugin.placeholders.set(),
                catalog = XVanishMessages.catalog,
                events = listOf(
                    EventInfo.of(VanishEvent.TYPE, "A player is about to vanish or show again. Cancel it to refuse.", since = "1.0.0"),
                    EventInfo.of(VanishedEvent.TYPE, "A player vanished.", since = "1.0.0"),
                    EventInfo.of(UnvanishedEvent.TYPE, "A player showed again.", since = "1.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
