package co.xenastudios.xbackup.store

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The repository's formats and rules that need no disk, the same on the JVM and on Wasm. */
class StoreTest {
    private fun hash(n: Int) = Hash(n.toLong(), n * 31L, n * 17L, n.toLong() shl 7)

    private object Utc : Calendar {
        override fun hour(epochMillis: Long): Long = epochMillis / 3_600_000
        override fun day(epochMillis: Long): Long = epochMillis / 86_400_000
        override fun week(epochMillis: Long): Long = (epochMillis / 86_400_000 + 3) / 7
        override fun month(epochMillis: Long): Long = epochMillis / 86_400_000 / 30
    }

    private fun snapshot(id: Int, hoursAgo: Long, now: Long = 400L * 86_400_000) = Snapshot(
        hash(id), Instant.fromEpochMilliseconds(now - hoursAgo * 3_600_000), Instant.fromEpochMilliseconds(now - hoursAgo * 3_600_000 + 1000),
        hash(1000 + id), null, "test", 1, 1, 1, 0, 0, "test", emptyList(),
    )

    @Test
    fun hashesPrintAndParse() {
        val h = hash(7)
        assertEquals(h, Hash.parse(h.toString()))
        assertEquals(64, h.toString().length)
    }

    @Test
    fun sizesReadAndPrint() {
        assertEquals(2L shl 30, Sizes.parse("2GB"))
        assertEquals(1536L shl 10, Sizes.parse("1.5 MB"))
        assertEquals("1.50 MB", Sizes.format(1536L shl 10))
    }

    @Test
    fun retentionKeepsTheNewestThePinnedAndOnePerBucket() {
        // One an hour for three days.
        val snapshots = (0 until 72).map { snapshot(it, it.toLong()) }
        val keep = Retention(last = 3, hourly = 24, daily = 7, weekly = 0, monthly = 0).keep(snapshots, setOf(hash(71)), Utc)
        assertTrue(hash(0) in keep && hash(1) in keep && hash(2) in keep)
        assertTrue(hash(71) in keep, "pinned")
        // 24 hourly from the newest; then the newest of each of the 3 days.
        assertTrue((0 until 24).all { hash(it) in keep })
        assertEquals(24 + 2 + 1, keep.size, keep.toString())
        // Nothing but the newest, with every count at zero.
        assertEquals(setOf(hash(0)), Retention(0, 0, 0, 0, 0).keep(snapshots, emptySet(), Utc))
    }

    @Test
    fun globs() {
        val logs = Globs.regex("plugins/*/logs/**")
        assertTrue(logs.matches("plugins/Some/logs/latest.log"))
        assertFalse(logs.matches("plugins/Some/data/logs.txt"))
        assertTrue(Globs.regex("**/session.lock").matches("session.lock"))
        assertTrue(Globs.regex("**/session.lock").matches("world/session.lock"))
        assertTrue(Globs.regex("*.{tmp,lck}").matches("a.lck"))
        assertFailsWith<IllegalArgumentException> { Globs.check("{a,b") }
        assertEquals(listOf("plugins/X/cache/**", "plugins/X/**/*.log"), Globs.scoped("plugins/X", listOf("plugins/X/cache/**", "/**/*.log")))
    }

    @Test
    fun restorePaths() {
        assertEquals("world", RestorePaths.normalise("./world/"))
        assertEquals("", RestorePaths.normalise("."))
        assertEquals("plugins/X", RestorePaths.normalise("\\plugins\\X"))
        assertFailsWith<IllegalArgumentException> { RestorePaths.normalise("world/../../etc") }
    }

    @Test
    fun secretsReadBackAsWritten() {
        val values = mapOf("offsite" to mapOf("password" to "a \"quoted\"\\ value\twith: everything # here"), "drive" to mapOf("client-secret" to "x"))
        val text = SecretText.write("xBackup's secrets.", values)
        assertEquals(values, SecretText.read(text, "secrets.yml"))
        assertTrue(text.startsWith("# xBackup's secrets."))
        assertFailsWith<IOException> { SecretText.read("offsite: nope\n", "secrets.yml") }
    }

    @Test
    fun snapshotsAndIndexesRoundTrip() {
        val snapshot = snapshot(3, 5).copy(warnings = listOf("one"), external = listOf("xMoney: MySQL at db:3306/mc (xmoney_ tables)"))
        val decoded = Snapshot.decode(hash(3), snapshot.encode())
        assertEquals(snapshot, decoded)
        val entries = mapOf(hash(9) to listOf(PackEntry(hash(10), BlobType.DATA, true, 0, 100, 300), PackEntry(hash(11), BlobType.TREE, false, 100, 50, 50)))
        val index = IndexFormat.decode(IndexFormat.encode(entries), "index")
        assertEquals(entries, index)
    }

    @Test
    fun theChunkerCutsTheSameWayEveryTimeAndPiecesFollowTheData() {
        val data = Random(42).nextBytes(5 shl 20)
        fun pieces(bytes: ByteArray): List<ByteArray> {
            var at = 0
            val chunker = Chunker { buffer, offset, length ->
                if (at >= bytes.size) -1 else minOf(length, bytes.size - at).also { bytes.copyInto(buffer, offset, at, at + it); at += it }
            }
            return generateSequence { chunker.next() }.toList()
        }
        val first = pieces(data)
        assertContentEquals(data, first.fold(ByteArray(0)) { all, piece -> all + piece })
        assertTrue(first.all { it.size <= Chunker.MAX } && first.dropLast(1).all { it.size >= Chunker.MIN })
        // A byte inserted near the start moves only the first piece or two: the rest still match.
        val shifted = pieces(byteArrayOf(1) + data)
        val same = shifted.map { it.contentHashCode() }.toSet().intersect(first.map { it.contentHashCode() }.toSet())
        assertTrue(same.size >= first.size - 2, "${same.size} of ${first.size} pieces still match")
    }
}
