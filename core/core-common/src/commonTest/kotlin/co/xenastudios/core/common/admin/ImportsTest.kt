package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ImportsTest {

    private val platform = FakePlatform()

    /** Another plugin's balances: `name=amount`, read [limit] at a time by index. */
    private inner class Balances(val rows: List<String>) : Importer<String>("oldeco", "OldEco") {
        var present = true
        var failReadAt: Int? = null
        val written = LinkedHashMap<String, Int>()
        var writes = 0

        override fun detect(): Pending<Boolean> = pendingOf(present)

        override fun count(): Pending<Outcome<Long, String>> = pendingOf(Outcome.Ok(rows.size.toLong()))

        override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<String>, String>> {
            val from = cursor?.toInt() ?: 0
            if (from == failReadAt) return pendingOf(Outcome.Err("OldEco's database went away"))
            val to = minOf(rows.size, from + limit)
            return pendingOf(Outcome.Ok(ImportPage(rows.subList(from, to), if (to == rows.size) null else to.toString())))
        }

        override fun describe(record: String): String = "OldEco balance of ${record.substringBefore('=')}"

        override fun skip(record: String): String? = if (record.endsWith("=0")) "an empty balance" else null

        override fun write(record: String): Pending<Outcome<Unit, String>> {
            writes++
            val amount = record.substringAfter('=').toIntOrNull() ?: return pendingOf(Outcome.Err("\"${record.substringAfter('=')}\" isn't a number"))
            // Replaces, never adds: a page written twice changes nothing.
            written[record.substringBefore('=')] = amount
            return pendingOf(Outcome.Ok(Unit))
        }
    }

    private class Heard : ImportListener {
        val lines = mutableListOf<String>()
        override fun notFound() { lines += "not found" }
        override fun started(total: Long, done: Long, resumed: Boolean) { lines += "started $total from $done${if (resumed) " (resumed)" else ""}" }
        override fun progress(done: Long, total: Long) { lines += "progress $done/$total" }
        override fun recordFailed(record: String, reason: String) { lines += "failed $record: $reason" }
        override fun finished(counts: ImportCounts, dryRun: Boolean) { lines += "${if (dryRun) "dry run" else "finished"} $counts" }
        override fun stopped(reason: String) { lines += "stopped: $reason" }
    }

    private val rows = (1..10).map { "p$it=${it * 10}" } + listOf("empty=0", "broken=lots")

    @Test
    fun aDryRunCountsAndChangesNothing() {
        val source = Balances(rows)
        val heard = Heard()
        assertTrue(Imports(platform, listOf(source), pageSize = 5).start("oldeco", dryRun = true, heard))
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("dry run ImportCounts(read=12, imported=11, skipped=1, failed=0)"), heard.lines)
        assertEquals(0, source.writes)
        assertEquals(emptyList(), platform.storage.paths)
    }

    @Test
    fun recordsFailOneByOneAndTheRestAreImported() {
        val source = Balances(rows)
        val heard = Heard()
        Imports(platform, listOf(source), pageSize = 5).start("oldeco", dryRun = false, heard)
        platform.scheduler.advance(1.seconds)
        assertEquals(
            listOf(
                "started 12 from 0",
                "failed OldEco balance of broken: \"lots\" isn't a number",
                "finished ImportCounts(read=12, imported=10, skipped=1, failed=1)",
            ),
            heard.lines,
        )
        assertEquals(10, source.written.size)
        assertEquals(listOf("Couldn't import OldEco balance of broken from OldEco: \"lots\" isn't a number"), platform.log.lines.filter { it.level.name == "WARN" }.map { it.message })
        // Finished, so there's nothing to carry on from.
        assertEquals(emptyList(), platform.storage.paths)
    }

    @Test
    fun aRunThatStopsCarriesOnWhereItLeftOff() {
        val source = Balances(rows).apply { failReadAt = 10 }
        val imports = Imports(platform, listOf(source), pageSize = 5)
        val first = Heard()
        imports.start("oldeco", dryRun = false, first)
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("started 12 from 0", "stopped: OldEco's database went away"), first.lines)
        assertEquals(10, source.written.size)
        assertEquals(
            "cursor: 10\nread: 10\nimported: 10\nskipped: 0\nfailed: 0\n",
            platform.storage.readText(StoragePath.of("imports/oldeco.txt")).getOrNull(),
        )

        source.failReadAt = null
        val writesBefore = source.writes
        val second = Heard()
        imports.start("oldeco", dryRun = false, second)
        platform.scheduler.advance(1.seconds)
        assertEquals(
            listOf(
                "started 12 from 10 (resumed)",
                "failed OldEco balance of broken: \"lots\" isn't a number",
                "finished ImportCounts(read=12, imported=10, skipped=1, failed=1)",
            ),
            second.lines,
        )
        assertEquals(1, source.writes - writesBefore, "only the last page was read again")

        // Run again from the start: the same data, no copies.
        imports.start("oldeco", dryRun = false, Heard())
        platform.scheduler.advance(1.seconds)
        assertEquals(10, source.written.size)
        assertEquals(100, source.written["p10"])
    }

    @Test
    fun progressIsToldNowAndThen() {
        val source = object : Importer<Int>("slow", "Slow") {
            override fun detect(): Pending<Boolean> = pendingOf(true)
            override fun count(): Pending<Outcome<Long, String>> = pendingOf(Outcome.Ok(30L))
            override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<Int>, String>> {
                val from = cursor?.toInt() ?: 0
                return pendingOf(Outcome.Ok(ImportPage((from until from + limit).toList(), if (from + limit >= 30) null else (from + limit).toString())))
            }
            override fun describe(record: Int): String = "#$record"
            override fun write(record: Int): Pending<Outcome<Unit, String>> = pendingOf(Outcome.Ok(Unit))
        }
        val heard = Heard()
        Imports(platform, listOf(source), pageSize = 10, progressEvery = 0.milliseconds).start("slow", dryRun = false, heard)
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("started 30 from 0", "progress 10/30", "progress 20/30", "finished ImportCounts(read=30, imported=30, skipped=0, failed=0)"), heard.lines)
    }

    @Test
    fun oneAtATimeAndOnlyWhenTheDataIsThere() {
        val source = Balances(rows).apply { present = false }
        val imports = Imports(platform, listOf(source))
        val heard = Heard()
        assertTrue(imports.start("oldeco", dryRun = false, heard))
        assertFalse(imports.start("oldeco", dryRun = true, Heard()), "one is already running")
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("not found"), heard.lines)
        assertFalse(imports.busy)
    }
}
