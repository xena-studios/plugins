package co.xenastudios.xbackup.restore

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restore's swap, which runs as the server loads xBackup, before any world: all or nothing,
 * finished on the next start if a crash cuts it off, undone if a step fails, and never started
 * when a staged copy is missing.
 */
class SwapTest {
    private val root: Path = Files.createTempDirectory("xbackup-swap")
    private val restore: Path = root.resolve("plugins/xBackup/restore")
    private val staged: Path = restore.resolve(Swap.STAGED)
    private val said = ArrayList<String>()

    @AfterTest
    fun clean() {
        root.toFile().setWritable(true)
        root.toFile().walkTopDown().forEach { it.setWritable(true) }
        root.toFile().deleteRecursively()
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(path: Path) = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    // The live server: a world and server.properties; the restore's copies of both, staged.
    private fun stage(): List<Swap.Entry> {
        write(root.resolve("world/level.dat"), "live world")
        write(root.resolve("world/region/r.0.0.mca"), "live region")
        write(root.resolve("server.properties"), "live properties")
        write(staged.resolve("world/level.dat"), "restored world")
        write(staged.resolve("world/region/r.0.0.mca"), "restored region")
        write(staged.resolve("server.properties"), "restored properties")
        val entries = listOf(Swap.Entry("world", false), Swap.Entry("server.properties", false))
        Swap.schedule(restore, "0123456789abcdef", entries)
        return entries
    }

    private fun assertRestored() {
        assertEquals("restored world", read(root.resolve("world/level.dat")))
        assertEquals("restored region", read(root.resolve("world/region/r.0.0.mca")))
        assertEquals("restored properties", read(root.resolve("server.properties")))
        assertFalse(Files.exists(restore.resolve(Swap.PENDING)))
        assertFalse(Files.exists(staged))
    }

    private fun assertAsBefore() {
        assertEquals("live world", read(root.resolve("world/level.dat")))
        assertEquals("live region", read(root.resolve("world/region/r.0.0.mca")))
        assertEquals("live properties", read(root.resolve("server.properties")))
    }

    @Test
    fun aStagedRestoreSwapsInAndKeepsWhatWasThere() {
        stage()
        val aside = Swap.apply(restore, root) { said += it }!!
        assertRestored()
        assertEquals("live world", read(aside.resolve("world/level.dat")))
        assertEquals("live properties", read(aside.resolve("server.properties")))
        assertNull(Swap.apply(restore, root) { said += it }, "nothing left to do")
    }

    @Test
    fun aCrashAfterSettingTheFirstAsideIsFinishedOnTheNextStart() {
        stage()
        val created = Swap.pending(restore)!!.created
        // What a crash leaves: the world moved aside and journaled, nothing put in place yet.
        val aside = restore.resolve(Swap.REPLACED).resolve(created)
        Files.createDirectories(aside)
        Files.move(root.resolve("world"), aside.resolve("world"))
        Files.write(restore.resolve(Swap.PENDING), "aside 0\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(0, Swap.pending(restore)!!.done)
        Swap.apply(restore, root) { said += it }
        assertRestored()
        assertEquals("live world", read(aside.resolve("world/level.dat")))
    }

    @Test
    fun aCrashBetweenEntriesIsFinishedOnTheNextStart() {
        stage()
        val created = Swap.pending(restore)!!.created
        val aside = restore.resolve(Swap.REPLACED).resolve(created)
        Files.createDirectories(aside)
        Files.move(root.resolve("world"), aside.resolve("world"))
        Files.move(staged.resolve("world"), root.resolve("world"))
        Files.write(restore.resolve(Swap.PENDING), "aside 0\ndone 0\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(1, Swap.pending(restore)!!.done)
        Swap.apply(restore, root) { said += it }
        assertRestored()
    }

    @Test
    fun aMissingStagedCopyChangesNothing() {
        stage()
        Files.delete(staged.resolve("server.properties"))
        val e = assertFailsWith<java.io.IOException> { Swap.apply(restore, root) { said += it } }
        assertTrue("Nothing was changed" in e.message!!, e.message)
        assertAsBefore()
        assertTrue(Files.exists(restore.resolve(Swap.PENDING)), "kept, to stage again or cancel")
    }

    @Test
    fun aFailedStepUndoesEverything() {
        stage()
        // server.properties can't be moved: its folder (the server folder) is read-only.
        root.toFile().setWritable(false)
        // Root ignores it (a CI container's user can be root): nothing to fail, so nothing to test.
        if (Files.isWritable(root)) {
            root.toFile().setWritable(true)
            return
        }
        try {
            assertFailsWith<Swap.FailedException> { Swap.apply(restore, root) { said += it } }
        } finally {
            root.toFile().setWritable(true)
        }
        assertAsBefore()
        assertTrue(Files.exists(restore.resolve(Swap.FAILED)))
        assertFalse(Files.exists(restore.resolve(Swap.PENDING)))
    }

    @Test
    fun aRestoredDatabaseLeavesItsOldJournalBehind() {
        write(root.resolve("plugins/Some/data.db"), "live db")
        write(root.resolve("plugins/Some/data.db-wal"), "live wal")
        write(staged.resolve("plugins/Some/data.db"), "restored db")
        Swap.schedule(restore, "0123456789abcdef", listOf(Swap.Entry("plugins/Some/data.db", true)))
        val aside = Swap.apply(restore, root) { said += it }!!
        assertEquals("restored db", read(root.resolve("plugins/Some/data.db")))
        // SQLite would replay the old log over the restored copy.
        assertFalse(Files.exists(root.resolve("plugins/Some/data.db-wal")))
        assertEquals("live wal", read(aside.resolve("plugins/Some/data.db-wal")))
    }

    @Test
    fun cancelling() {
        stage()
        assertTrue(Swap.cancel(restore))
        assertAsBefore()
        assertNull(Swap.pending(restore))
    }
}
