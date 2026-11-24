package co.xenastudios.core.common.platform

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PlatformTest {

    @Test
    fun theFakeClockMovesOnlyWithTheScheduler() {
        val platform = FakePlatform(start = Instant.parse("2026-11-24T12:00:00Z"))
        assertEquals(Instant.parse("2026-11-24T12:00:00Z"), platform.clock.now())
        platform.scheduler.advance(90.minutes)
        assertEquals(Instant.parse("2026-11-24T13:30:00Z"), platform.clock.now())
        assertEquals(90.minutes, platform.clock.elapsed())
    }

    @Test
    fun theSystemClockRuns() {
        val first = SystemClock.elapsed()
        assertTrue(SystemClock.elapsed() >= first)
        assertTrue(SystemClock.now() > Instant.parse("2026-01-01T00:00:00Z"))
    }

    @Test
    fun capabilitiesAreAskedOnce() {
        val platform = FakePlatform(capabilities = setOf(Capability.SQL))
        assertTrue(platform.has(Capability.SQL))
        assertFalse(platform.has(Capability.NATIVE_EVENTS))
    }

    @Test
    fun pathsStayInsideThePluginsFolder() {
        assertEquals(listOf("lang", "en.yml"), StoragePath.of("lang/en.yml").names)
        assertEquals(StoragePath.ROOT, StoragePath.of(""))
        assertEquals("lang/en.yml", StoragePath.of("lang").resolve("en.yml").value)
        assertEquals("config.yml", StoragePath.ROOT.resolve("config.yml").value)
        for (bad in listOf("/etc/passwd", "../other/config.yml", "lang/../../x", "a//b", "a/", "./a", "C:/x", "a\\b", "a\u0000b")) {
            val parsed = StoragePath.parse(bad)
            assertTrue(parsed is Outcome.Err && parsed.error.kind == StorageError.Kind.BAD_PATH, bad)
        }
        assertFailsWith<IllegalArgumentException> { StoragePath.of("lang").resolve("../x") }
    }

    @Test
    fun storageInMemoryReadsWritesListsAndDeletes() {
        val storage = FakePlatform().storage
        val config = StoragePath.of("config.yml")
        assertEquals(Outcome.Ok(null), storage.readText(config))
        assertEquals(Outcome.Ok(Unit), storage.writeText(config, "a: 1"))
        assertEquals(Outcome.Ok("a: 1"), storage.readText(config))
        storage.write(StoragePath.of("lang/en.yml"), byteArrayOf(1, 2))
        storage.write(StoragePath.of("lang/de.yml"), byteArrayOf(3))
        assertContentEquals(byteArrayOf(1, 2), storage.read(StoragePath.of("lang/en.yml")).getOrNull())
        assertEquals(Outcome.Ok(listOf("config.yml", "lang")), storage.list(StoragePath.ROOT))
        assertEquals(Outcome.Ok(listOf("de.yml", "en.yml")), storage.list(StoragePath.of("lang")))
        assertEquals(Outcome.Ok(emptyList()), storage.list(StoragePath.of("nothing")))
        assertTrue(storage.write(StoragePath.of("lang"), byteArrayOf()).isErr)
        assertEquals(Outcome.Ok(true), storage.delete(config))
        assertEquals(Outcome.Ok(false), storage.delete(config))
        assertEquals(listOf("lang/de.yml", "lang/en.yml"), storage.paths)
    }

    @Test
    fun storageCanFailOnPurpose() {
        val storage = FakePlatform().storage
        storage.failure = StorageError.Kind.DENIED
        val read = storage.read(StoragePath.of("config.yml"))
        assertTrue(read is Outcome.Err && read.error.kind == StorageError.Kind.DENIED)
        assertNull(read.getOrNull())
    }
}
