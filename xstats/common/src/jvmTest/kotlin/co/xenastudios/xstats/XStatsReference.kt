package co.xenastudios.xstats

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.xstats.api.event.LeaderboardChangeEvent
import co.xenastudios.xstats.api.event.PrivacyChangeEvent
import co.xenastudios.xstats.api.event.StatChangeEvent
import co.xenastudios.xstats.api.event.StatsSavedEvent
import co.xenastudios.xstats.config.XStatsConfig

/**
 * xStats' reference, for `./gradlew :xstats:common:xenaManifest`: the plugin with its default config
 * on the fake platform, as its tests build it, on a server (the proxy's commands are in its docs page).
 */
object XStatsReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = TestPlatform()
            val server = FakeStatsServer()
            val loaded = Loaded(XStatsConfig.defaults, Translations.of(XStatsMessages.catalog, emptyMap()))
            val plugin = XStatsPlugin(platform, server, Files { loaded }, server, server, null, { Outcome.Err("the reference has no database") })
            plugin.start(loaded)
            return PluginReference(
                XStatsPlugin.PLUGIN_ID, XStatsPlugin.NAME, "Player stats, leaderboards and a website.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XStatsConfig,
                catalog = XStatsMessages.catalog,
                events = listOf(
                    EventInfo.of(StatChangeEvent.TYPE, "A plugin is about to change one of its stats for a player. Cancel it, or change the amount.", since = "2.0.0"),
                    EventInfo.of(PrivacyChangeEvent.TYPE, "A player is about to hide their stats from the website, or show them again. Cancel it to leave them.", since = "2.0.0"),
                    EventInfo.of(StatsSavedEvent.TYPE, "A save finished: what changed is in the database and every leaderboard was worked out again.", since = "1.0.0"),
                    EventInfo.of(LeaderboardChangeEvent.TYPE, "A leaderboard has a new leader, worked out at a save.", since = "1.0.0"),
                ),
            ).also { plugin.stop() }
        }
}
