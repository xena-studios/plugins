package co.xenastudios.xplots

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.xplots.api.event.MemberAddEvent
import co.xenastudios.xplots.api.event.MemberAddedEvent
import co.xenastudios.xplots.api.event.MemberRemoveEvent
import co.xenastudios.xplots.api.event.MemberRemovedEvent
import co.xenastudios.xplots.api.event.PlotClaimEvent
import co.xenastudios.xplots.api.event.PlotClaimedEvent
import co.xenastudios.xplots.api.event.PlotUnclaimEvent
import co.xenastudios.xplots.api.event.PlotUnclaimedEvent
import co.xenastudios.xplots.api.event.RentPaidEvent
import co.xenastudios.xplots.api.event.RentPayEvent
import co.xenastudios.xplots.config.XPlotsConfig

/** xPlots' reference, for `./gradlew :xplots:common:xenaManifest`: the plugin with its default config on the fake platform. */
object XPlotsReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = TestPlatform()
            val server = FakePlotServer()
            val loaded = Loaded(XPlotsConfig.defaults, Translations.of(XPlotsMessages.catalog, emptyMap()))
            val none = object : Opener {
                override fun describe(config: Config): String = ""
                override fun open(config: Config): Outcome<Database, String> = Outcome.Err("the reference has no database")
            }
            val plugin = XPlotsPlugin(platform, server, { loaded }, server, server, null, none, FakeHooks(), JvmDeflate)
            plugin.start(loaded)
            return PluginReference(
                XPlotsPlugin.PLUGIN_ID, XPlotsPlugin.NAME, "Rentable plots, set up in a minute.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XPlotsConfig,
                placeholders = plugin.placeholders.set(),
                catalog = XPlotsMessages.catalog,
                events = listOf(
                    EventInfo.of(PlotClaimEvent.TYPE, "A player is about to rent a free plot (or staff give one). Cancel it to refuse: nothing is charged.", since = "2.0.0"),
                    EventInfo.of(PlotClaimedEvent.TYPE, "A player rented a plot, with what they paid.", since = "2.0.0"),
                    EventInfo.of(PlotUnclaimEvent.TYPE, "A plot's rent is about to end: abandoned, run out or evicted. Cancel it to keep it rented.", since = "2.0.0"),
                    EventInfo.of(PlotUnclaimedEvent.TYPE, "A plot's rent ended, with its old owner and members.", since = "2.0.0"),
                    EventInfo.of(MemberAddEvent.TYPE, "A member is about to be added to a plot. Cancel it to refuse.", since = "2.0.0"),
                    EventInfo.of(MemberAddedEvent.TYPE, "A member was added.", since = "2.0.0"),
                    EventInfo.of(MemberRemoveEvent.TYPE, "A member is about to be removed. Cancel it to keep them.", since = "2.0.0"),
                    EventInfo.of(MemberRemovedEvent.TYPE, "A member was removed.", since = "2.0.0"),
                    EventInfo.of(RentPayEvent.TYPE, "Rent is about to be paid ahead, renewed (owner online or not) or a slot bought. Cancel it and nothing is charged.", since = "2.0.0"),
                    EventInfo.of(RentPaidEvent.TYPE, "Rent was paid, renewed or a slot bought, with the plot's new time or slots.", since = "2.0.0"),
                ),
            ).also { plugin.stop() }
        }
}
