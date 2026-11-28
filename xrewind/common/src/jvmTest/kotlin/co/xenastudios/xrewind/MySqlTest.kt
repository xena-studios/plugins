package co.xenastudios.xrewind

import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.api.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The writer, a lookup, a rollback and a purge on a real MySQL or MariaDB, 1.x's SQL on MySQL's
 * dialect (its `DELETE ... LIMIT`, `MEDIUMBLOB`): when `XR_MYSQL` is `host:port:database:user:password`,
 * as CI's MariaDB service sets it. Skipped without it.
 */
class MySqlTest {
    private val settings: DatabaseSettings? = System.getenv("XR_MYSQL")?.takeIf { it.isNotBlank() }?.split(':', limit = 5)?.let { (host, port, database, user, password) ->
        val prefix = "xr" + (System.nanoTime() % 1_000_000) + "_"
        DatabaseSettings(DatabaseSettings.Type.MYSQL, prefix, DatabaseSettings.MySql(host, port.toInt(), database, user, password))
    }

    @Test
    fun theLogLookupsRollbacksAndPurgesWorkOnMySql() {
        val settings = settings ?: return println("XR_MYSQL isn't set: skipped")
        val h = Harness(mysql = settings).start()
        try {
            val world = WorldRef.of("world")
            h.plugin.logRemoval(Actor.source("old"), world, 1, 64, 1, "minecraft:chest[facing=north]")
            h.plugin.logContainerChange(Actor.source("old"), world, 2, 64, 1, "minecraft:diamond", -2)
            h.flush()
            h.platform.advance((3 * 86_400).seconds)
            h.plugin.logRemoval(Actor.source("new"), world, 3, 64, 1, "minecraft:stone")
            h.flush()
            assertEquals(3, h.platform.await(h.plugin.lookup(Query.builder().build())).size)
            val plan = h.platform.await(h.plugin.planRollback(Query.builder().users("#new").time(3600).build()))
            h.platform.await(h.plugin.apply(plan))
            assertEquals("minecraft:stone", h.server.state("world", 3, 64, 1))
            h.run("xrewind purge t:2d") { h.console.replies.any { "Purged" in it } }
            assertTrue(h.console.replies.any { "Purged 2 changes" in it }, h.console.replies.toString())
            assertEquals(listOf("#new"), h.platform.await(h.plugin.lookup(Query.builder().build())).map { it.actor })
        } finally {
            h.stop()
        }
    }
}
