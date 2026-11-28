package co.xenastudios.xvote

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.xvote.api.event.VoteCountedEvent
import co.xenastudios.xvote.api.event.VotePartyEvent
import co.xenastudios.xvote.api.event.VoteReceivedEvent
import co.xenastudios.xvote.api.event.VoteRewardEvent
import co.xenastudios.xvote.api.event.VoteRewardedEvent
import co.xenastudios.xvote.config.XVoteConfig

/**
 * xVote's reference, for `./gradlew :xvote:common:xenaManifest`: the plugin with its default config
 * on the fake platform, as its tests build it, with the importers a Bukkit server offers.
 */
object XVoteReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = TestPlatform()
            val server = FakeVoteServer()
            val loaded = Loaded(XVoteConfig.defaults, Translations.of(XVoteMessages.catalog, emptyMap()))
            val imports = object : VoteImports {
                override fun nuVotifier(): Outcome<String, String>? = null
                override fun totals(source: String): Outcome<Pair<String, List<Imported>>, String> = Outcome.Err("none")
            }
            val files = object : Files {
                override fun load(): Loaded = loaded
            }
            val plugin = XVotePlugin(platform, server, files, server, server, null, { Outcome.Err("the reference has no database") }, imports = imports)
            plugin.start(loaded)
            return PluginReference(
                XVotePlugin.PLUGIN_ID, XVotePlugin.NAME, "Votes in. Rewards out.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XVoteConfig,
                catalog = XVoteMessages.catalog,
                events = listOf(
                    EventInfo.of(VoteReceivedEvent.TYPE, "A vote arrived and is about to be stored, where it was received. Cancel it to drop the vote.", since = "1.0.0"),
                    EventInfo.of(VoteCountedEvent.TYPE, "A vote was counted for a player: once per vote on the whole network. For stats plugins.", since = "2.0.0"),
                    EventInfo.of(VoteRewardEvent.TYPE, "A player is about to be rewarded for a vote, on their server. Change the rewards, or cancel it.", since = "1.0.0"),
                    EventInfo.of(VoteRewardedEvent.TYPE, "A player was given a vote's rewards.", since = "2.0.0"),
                    EventInfo.of(VotePartyEvent.TYPE, "The vote party is about to reward this server's players. Change who, or cancel it.", since = "1.0.0"),
                ),
            ).also { plugin.stop() }
        }
}
