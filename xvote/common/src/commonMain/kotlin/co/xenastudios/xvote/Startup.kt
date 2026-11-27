package co.xenastudios.xvote

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.proxy.ProxyListener
import co.xenastudios.core.common.proxy.ProxyMessage
import co.xenastudios.core.common.proxy.ProxyPayload
import co.xenastudios.xvote.config.Legacy
import co.xenastudios.xvote.store.Tables

/**
 * The functions xVote hands the core as it starts (the tables' two steps, the config's 1.x
 * migration): one class for them all rather than a lambda each, each lambda being a class of its
 * own in metaspace (decisions/2026-11-25-a-lighter-core.md).
 */
internal class Startup private constructor(private val kind: Int) : (Any?) -> Any? {
    override fun invoke(value: Any?): Any? = when (kind) {
        TABLES -> Tables.first(value as Sql)
        ADDRESSES -> Tables.second(value as Sql)
        else -> Legacy.toTwo(value as MigrationScope)
    }

    @Suppress("UNCHECKED_CAST")
    internal companion object {
        private const val TABLES = 0
        private const val ADDRESSES = 1
        private const val CONFIG_ONE = 2

        val tables: (Sql) -> List<Statement> get() = Startup(TABLES) as (Sql) -> List<Statement>
        val addresses: (Sql) -> List<Statement> get() = Startup(ADDRESSES) as (Sql) -> List<Statement>
        val configOne: MigrationScope.() -> Unit get() = Startup(CONFIG_ONE) as MigrationScope.() -> Unit
    }
}

/**
 * The proxy layer's end on a server behind a proxy (decisions/2026-11-25-the-proxy-layer.md): the
 * proxy, which received a vote and stored it in the database the servers share, tells the server
 * its player is on (`vote`, with the vote's id), which reads it and pays it at once. The message
 * only hurries what the change feed and the player's next join do anyway.
 */
internal class Network private constructor(private val plugin: XVotePlugin) : ProxyListener {
    override fun received(message: ProxyMessage) {
        val id = ProxyPayload.read(message.payload) {
            if (int() != 1) null else long()
        } ?: return
        plugin.votes?.heard(id)
    }

    companion object {
        fun listen(plugin: XVotePlugin, channel: ProxyChannel) {
            if (plugin.server.proxy) return
            channel.listen(Votes.TOPIC_VOTE, Network(plugin))
        }
    }
}
