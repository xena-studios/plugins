package co.xenastudios.core.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerRefsTest {

    @Test
    fun javaUuidsGoBothWays() {
        val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        val ref = uuid.toPlayerRef()
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", ref.uuid.toString())
        assertEquals(uuid, ref.javaUuid)
    }

    @Test
    fun aPendingIsACompletableFuture() {
        val pending: Pending<String> = newPending()
        val future: CompletableFuture<String> = pending
        thread { pending.resolve("done") }
        assertEquals("done", future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun aFailureAfterAStageIsUnwrapped() {
        val boom = IllegalStateException("boom")
        val failed = CompletableFuture.supplyAsync<Int> { throw boom }.thenApply { it + 1 }
        var error: Throwable? = null
        val done = CompletableFuture<Unit>()
        failed.onSettled { _, e ->
            error = e
            done.complete(Unit)
        }
        done.get(5, TimeUnit.SECONDS)
        assertSame(boom, error)
        assertTrue(failed.isSettled)
    }
}
