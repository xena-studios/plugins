package co.xenastudios.xrewind

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.xrewind.api.event.LookupEvent
import co.xenastudios.xrewind.api.event.RollbackEvent
import co.xenastudios.xrewind.api.event.RollbackFinishedEvent
import co.xenastudios.xrewind.config.XRewindConfig

/** xRewind's reference, for `./gradlew :xrewind:common:xenaManifest`: the plugin with its default config on the fake platform. */
object XRewindReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = TestPlatform()
            val server = FakeRewindServer(platform.log)
            val loaded = Loaded(XRewindConfig.defaults, Translations.of(XRewindMessages.catalog, emptyMap()))
            val none = object : Opener {
                override fun describe(config: Config): String = ""
                override fun open(config: Config): Outcome<Database, String> = Outcome.Err("the reference has no database")
            }
            val plugin = XRewindPlugin(platform, server, { loaded }, server, server, null, none)
            plugin.start(loaded)
            return PluginReference(
                XRewindPlugin.PLUGIN_ID, XRewindPlugin.NAME, "Every change, logged. Any of it, undone.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XRewindConfig,
                catalog = XRewindMessages.catalog,
                events = listOf(
                    EventInfo.of(LookupEvent.TYPE, "Someone looked the history up: a command, the inspector or a plugin. For an audit log; not cancellable.", since = "1.1.0"),
                    EventInfo.of(RollbackEvent.TYPE, "A rollback, restore or undo is about to start. Cancel it to stop it.", since = "1.1.0"),
                    EventInfo.of(RollbackFinishedEvent.TYPE, "A rollback, restore or undo finished, with what it did and its undo.", since = "1.1.0"),
                ),
            ).also { plugin.stop() }
        }
}
