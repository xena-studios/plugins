package co.xenastudios.core.testing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The fake log takes lines from any thread while a test reads them, as schedulers' workers and database callbacks write. */
class RecordingLogTest {
    @Test
    fun linesFromManyThreadsAreAllKeptWhileATestReads() {
        val log = RecordingLog()
        val go = CountDownLatch(1)
        val writers = (0 until 4).map { w ->
            thread {
                go.await()
                repeat(2_000) { i -> if (i % 2 == 0) log.info("$w-$i") else log.error("$w-$i", null) }
            }
        }
        val failed = AtomicReference<Throwable?>()
        val reader = thread {
            go.await()
            try {
                while (writers.any { it.isAlive }) {
                    log.lines.size
                    log.errors.size
                }
            } catch (e: Throwable) {
                failed.set(e)
            }
        }
        go.countDown()
        writers.forEach { it.join() }
        reader.join()
        assertNull(failed.get())
        assertEquals(8_000, log.lines.size)
        assertEquals(4_000, log.errors.size)
    }
}
