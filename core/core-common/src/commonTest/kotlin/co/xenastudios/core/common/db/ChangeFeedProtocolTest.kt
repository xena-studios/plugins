package co.xenastudios.core.common.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The feed's protocol, without a database: which ids it delivers, waits for, and prunes below. */
class ChangeFeedProtocolTest {
    private fun found(id: Long, origin: String = "other") =
        FeedCursor.Found(Change(id, "ban", "p$id", Instant.fromEpochMilliseconds(0)), origin)

    private val t0 = Duration.ZERO

    @Test
    fun newRowsAreDeliveredInOrderAndItsOwnAreSkipped() {
        val cursor = FeedCursor(10)
        val delivered = cursor.accept(listOf(found(11), found(12, "me"), found(13)), "me", t0)
        assertEquals(listOf(11L, 13L), delivered.map { it.id })
        assertEquals(13, cursor.high)
        assertEquals(0, cursor.waitingFor)
    }

    @Test
    fun aSkippedIdIsWaitedForAndDeliveredWhenItsCommitLands() {
        val cursor = FeedCursor(0)
        assertEquals(listOf(1L, 3L), cursor.accept(listOf(found(1), found(3)), "me", t0).map { it.id })
        assertEquals(listOf(2L), cursor.waiting(10))
        assertEquals(2, cursor.pruneBelow(), "a row still waited for isn't pruned")
        // The next check asks for 2 again, and it's there now.
        assertEquals(listOf(2L), cursor.accept(listOf(found(2)), "me", 1.seconds).map { it.id })
        assertEquals(0, cursor.waitingFor)
        // Read twice, it's delivered once.
        assertEquals(emptyList(), cursor.accept(listOf(found(2)), "me", 2.seconds))
        assertEquals(3, cursor.pruneBelow())
    }

    @Test
    fun aGapIsGivenUpAfterTheWait() {
        val cursor = FeedCursor(0)
        cursor.accept(listOf(found(2)), "me", t0)
        cursor.accept(emptyList(), "me", ChangeFeed.GAP_WAIT)
        assertEquals(1, cursor.waitingFor)
        cursor.accept(emptyList(), "me", ChangeFeed.GAP_WAIT + 1.seconds)
        assertEquals(0, cursor.waitingFor)
    }

    @Test
    fun aJumpTooBigToWaitForAsksForAResync() {
        val cursor = FeedCursor(0)
        cursor.accept(listOf(found(ChangeFeed.MAX_GAP + 1L)), "me", t0)
        assertFalse(cursor.takeBehind(), "a gap of exactly MAX_GAP is waited for")
        assertEquals(ChangeFeed.MAX_GAP, cursor.waitingFor)
        val far = FeedCursor(0)
        far.accept(listOf(found(ChangeFeed.MAX_GAP + 2L)), "me", t0)
        assertTrue(far.takeBehind())
        assertFalse(far.takeBehind(), "once")
        assertEquals(0, far.waitingFor)
    }

    @Test
    fun theOldestGapsAreDroppedPastTheLimit() {
        val cursor = FeedCursor(0)
        var id = 0L
        repeat(6) {
            id += ChangeFeed.MAX_GAP + 1
            cursor.accept(listOf(found(id)), "me", t0)
        }
        assertEquals(ChangeFeed.MAX_WAITING, cursor.waitingFor)
        assertEquals(ChangeFeed.MAX_GAP + 2L, cursor.waiting(1).single(), "the first gap's ids went first")
    }

    @Test
    fun anOffFeedPublishesNothing() {
        val feed = ChangeFeed.off()
        assertFalse(feed.isOn)
        assertEquals(emptyList(), feed.publishing("ban", "x"))
        assertTrue(feed.check().settled() == Unit)
    }

    @Test
    fun networkModeOnSqliteIsOffAndSaysSo() {
        val platform = FakePlatform()
        val feed = ChangeFeed.open(FakeDatabase(), NetworkSettings(enabled = true), platform.scheduler, platform.clock, platform.log)
            .settled().ok()
        assertFalse(feed.isOn)
        assertTrue(platform.log.lines.single().message.startsWith("network.enabled is on, but the database is SQLite"))
    }

    @Test
    fun aChangeIsPublishedInTheCallersBatch() {
        val platform = FakePlatform()
        val database = FakeDatabase(FakeSession(Dialect.MySql))
        database.session.answer = { if (it.sql.startsWith("SELECT MAX")) Outcome.Ok(listOf(row("m" to SqlValue.Integer(7)))) else Outcome.Ok(emptyList()) }
        val feed = ChangeFeed.open(database, NetworkSettings(enabled = true), platform.scheduler, platform.clock, platform.log, origin = "abc")
            .settled().ok()
        assertTrue(feed.isOn)
        val publish = feed.publishing("ban", "steve").single()
        assertEquals("INSERT INTO test_changes (origin, topic, payload, created) VALUES (?, ?, ?, ?)", publish.sql)
        assertEquals(SqlValue.Text("abc"), publish.args[0])
        assertEquals(SqlValue.Integer(platform.clock.now().toEpochMilliseconds()), publish.args[3])
        assertFailsWith<IllegalArgumentException> { feed.publishing("", "x") }
        assertFailsWith<IllegalArgumentException> { feed.publishing("ban", "x".repeat(ChangeFeed.MAX_PAYLOAD + 1)) }
    }
}
