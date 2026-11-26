package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.NetworkSettings
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.platform.Platform

/**
 * What the ledger uses of the core's change feed, on a network. An interface, so the ledger and
 * the plugin never name [ChangeFeed] itself: a single server never loads the feed's classes.
 */
public interface Feed {
    /** The statements that tell the other servers about [payload] on [topic], in the same write. */
    public fun publishing(topic: String, payload: String): List<Statement>

    /** Calls [subscriber] with each payload another server publishes on [topic]. */
    public fun subscribe(topic: String, subscriber: (String) -> Unit)

    /** Calls [resync] when changes may have been missed. */
    public fun onResync(resync: () -> Unit)

    public fun start()

    public fun close()

    public companion object {
        /** The feed [settings] ask for on [database], or `null` if it's off or couldn't start (the console is told). */
        public fun open(database: Database, settings: NetworkSettings, platform: Platform): Pending<Feed?> {
            val done = newPending<Feed?>()
            ChangeFeed.open(database, settings, platform.scheduler, platform.clock, platform.log).onSettled { started, error ->
                val feed = (started as? Outcome.Ok)?.value?.takeIf { it.isOn }
                if (feed == null && started !is Outcome.Ok) {
                    platform.log.warn("Network mode is off: the change feed couldn't start (${(started as? Outcome.Err)?.error ?: error}).")
                }
                done.resolve(feed?.let(::FeedLink))
            }
            return done
        }

        /** [feed] as the ledger uses it: for tests that open their own. */
        public fun of(feed: ChangeFeed): Feed = FeedLink(feed)
    }
}

private class FeedLink(private val feed: ChangeFeed) : Feed {
    override fun publishing(topic: String, payload: String): List<Statement> = feed.publishing(topic, payload)

    override fun subscribe(topic: String, subscriber: (String) -> Unit) = feed.subscribe(topic) { subscriber(it.payload) }

    override fun onResync(resync: () -> Unit) = feed.onResync(resync)

    override fun start() = feed.start()

    override fun close() = feed.close()
}
