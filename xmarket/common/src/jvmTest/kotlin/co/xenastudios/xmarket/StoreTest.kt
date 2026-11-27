package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xmarket.store.Store
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trades and limits tables on a real SQLite database. */
class StoreTest {
    private val folder = Files.createTempDirectory("xmarket-store")
    private val database = (JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, "xmarket_"), folder.resolve("xmarket.db"), Drivers.serverOnly(javaClass.classLoader), RecordingLog(), "Test") as Outcome.Ok).value
    private val store = Store(database)
    private val steve = PlayerRef.parseOrNull("8667ba71-b85a-4004-af54-457a9734eed7")!!

    init {
        database.migrate(Store.MIGRATIONS).await()
    }

    @AfterTest
    fun clean() {
        database.close().await()
        folder.toFile().deleteRecursively()
    }

    private fun <T> CompletableFuture<T>.await(): T = get(15, TimeUnit.SECONDS)

    private fun <T, E> CompletableFuture<Outcome<T, E>>.ok(): T = (await() as Outcome.Ok).value

    @Test
    fun tradesPageNewestFirstAndAddUp() {
        for (i in 1..12) store.record(Store.Trade(1_000L * i, steve, i % 3 == 0, "ores", "diamond", "diamond", i, "money", 10_000L * i)).ok()
        val (page, count) = store.history(steve, 1, 10).ok()
        assertEquals(12, count)
        assertEquals(listOf(12, 11, 10), page.take(3).map { it.amount })
        assertEquals(2, store.history(steve, 2, 10).ok().first.size)
        val (totals, top) = store.stats(5_000, 5).ok()
        // Bought: 6, 9, 12; sold: 5, 7, 8, 10, 11 (in ones).
        assertEquals(270_000L, totals.single().bought)
        assertEquals(410_000L, totals.single().sold)
        assertEquals(41L, top.single().amount)
    }

    @Test
    fun aLimitsCountIsKeptAndForgotten() {
        store.setUsed(Store.Used(steve.toString(), "ores/rare:buy:day", 20_000, 3)).ok()
        store.setUsed(Store.Used(steve.toString(), "ores/rare:buy:day", 20_000, 4)).ok()
        assertEquals(listOf(4), store.used(steve.toString()).ok().map { it.amount })
        store.record(Store.Trade(1, steve, true, "ores", "x", "x", 1, "money", 1)).ok()
        assertEquals(2, store.forget(steve).ok())
        assertEquals(emptyList(), store.used(steve.toString()).ok())
    }
}
