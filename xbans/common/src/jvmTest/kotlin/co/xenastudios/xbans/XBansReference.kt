package co.xenastudios.xbans

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.xbans.api.event.LoginDeniedEvent
import co.xenastudios.xbans.api.event.PardonEvent
import co.xenastudios.xbans.api.event.PardonedEvent
import co.xenastudios.xbans.api.event.PunishEvent
import co.xenastudios.xbans.api.event.PunishedEvent
import co.xenastudios.xbans.api.event.StaffNotifyEvent
import co.xenastudios.xbans.config.XBansConfig

/**
 * xBans' reference, for `./gradlew :xbans:common:xenaManifest`: the plugin with its default config
 * on the fake platform, as its tests build it, with the importers a Bukkit server offers.
 */
object XBansReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = TestPlatform()
            val server = FakeBansServer()
            val loaded = Loaded(XBansConfig.defaults, Translations.of(XBansMessages.catalog, emptyMap()))
            val none = object : Opener {
                override fun describe(config: Config): String = ""
                override fun open(config: Config): Outcome<Database, String> = Outcome.Err("the reference has no database")
            }
            val imports = object : Imports {
                override val ids: List<String> = listOf("vanilla", "essentials", "litebans", "advancedban")
                override fun make(id: String): Importer? = null
            }
            val plugin = XBansPlugin(platform, server, { loaded }, server, server, null, none, imports = imports)
            plugin.start(loaded)
            return PluginReference(
                XBansPlugin.PLUGIN_ID, XBansPlugin.NAME, "Bans, mutes and warnings. Nothing slips through.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XBansConfig,
                catalog = XBansMessages.catalog,
                events = listOf(
                    EventInfo.of(PunishEvent.TYPE, "A punishment is about to be made, after every check. Cancel it to stop it.", since = "1.0.0"),
                    EventInfo.of(PunishedEvent.TYPE, "A punishment was made and saved, here or on another server of the network.", since = "1.0.0"),
                    EventInfo.of(PardonEvent.TYPE, "A punishment is about to be lifted. Cancel it to keep it in force.", since = "2.0.0"),
                    EventInfo.of(PardonedEvent.TYPE, "A punishment was lifted, here or on another server (1.x's RevokedEvent).", since = "1.0.0"),
                    EventInfo.of(LoginDeniedEvent.TYPE, "A player was turned away as they joined, by a ban or because the bans aren't read yet. Not cancellable.", since = "2.0.0"),
                    EventInfo.of(StaffNotifyEvent.TYPE, "Staff are about to be told something. Change who hears it, or cancel it.", since = "2.0.0"),
                ),
            ).also { plugin.stop() }
        }
}
