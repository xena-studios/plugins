package co.xenastudios.xlimbo.bukkit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules that keep xLimbo from deleting anything it didn't make, on real folders (2.x's, kept). */
class WorldFoldersTest {
    private val container: Path = Files.createTempDirectory("xlimbo-worlds")

    @AfterTest
    fun clean() {
        container.toFile().deleteRecursively()
    }

    private fun world(name: String, marked: Boolean): Path {
        val folder = Files.createDirectories(container.resolve(name))
        Files.writeString(folder.resolve("level.dat"), "data")
        Files.createDirectories(folder.resolve("region"))
        Files.writeString(folder.resolve("region/r.0.0.mca"), "chunks")
        if (marked) WorldFolders.mark(folder, "minecraft:purple_stained_glass@64")
        return folder
    }

    @Test
    fun namesAreOnlyEverOneFolder() {
        for (bad in listOf("..", ".", "...", "../world", "a/b", "a\\b", "", " ", "lim bo", "/abs", "x\u0000")) {
            assertFalse(WorldFolders.isPlainName(bad), bad)
            assertNull(WorldFolders.folder(bad, container, "world"), bad)
        }
        for (good in listOf("xlimbo", "x.limbo-1", "Limbo_2", ".hidden")) assertTrue(WorldFolders.isPlainName(good), good)
        assertFalse(WorldFolders.isPlainName(null))
    }

    @Test
    fun findsTheFolderInEitherLayout() {
        // 1.21: beside the main world.
        assertEquals(container.resolve("XLimbo"), WorldFolders.folder("XLimbo", container, "world"))
        // 26.1 and later: inside the main world, lowercased.
        Files.createDirectories(container.resolve("world/dimensions"))
        assertEquals(container.resolve("world/dimensions/minecraft/xlimbo"), WorldFolders.folder("XLimbo", container, "world"))
    }

    @Test
    fun neverDeletesAWorldItDidntMake() {
        val theirs = world("survival", false)
        assertFalse(WorldFolders.delete(theirs) { false })
        assertTrue(Files.exists(theirs.resolve("region/r.0.0.mca")))
    }

    @Test
    fun neverDeletesALoadedWorld() {
        val ours = world("xlimbo", true)
        assertFalse(WorldFolders.delete(ours) { it == "xlimbo" })
        assertTrue(Files.exists(ours.resolve("level.dat")))
    }

    @Test
    fun deletesItsOwnWorldCompletely() {
        val ours = world("xlimbo", true)
        assertTrue(WorldFolders.delete(ours) { false })
        assertFalse(Files.exists(ours))
        assertTrue(Files.exists(container), "only the world's own folder goes")
    }

    @Test
    fun aLinkInsideIsRemovedNeverFollowed() {
        val precious = world("survival", false)
        val ours = world("xlimbo", true)
        Files.createSymbolicLink(ours.resolve("escape"), precious)
        assertTrue(WorldFolders.delete(ours) { false })
        assertFalse(Files.exists(ours))
        assertTrue(Files.exists(precious.resolve("region/r.0.0.mca")), "the link's target is untouched")
    }

    @Test
    fun aLinkedWorldFolderIsEmptiedAndKept() {
        val ramdisk = world("ramdisk-limbo", true)
        val link = Files.createSymbolicLink(container.resolve("xlimbo"), ramdisk)
        assertTrue(WorldFolders.delete(link) { false })
        assertTrue(Files.isSymbolicLink(link), "the owner's link stays")
        assertTrue(Files.isDirectory(ramdisk), "and the folder it points to")
        Files.list(ramdisk).use { assertEquals(0, it.count()) }
    }

    @Test
    fun anUnmarkedLinkedFolderIsLeftAlone() {
        val elsewhere = world("elsewhere", false)
        val link = Files.createSymbolicLink(container.resolve("xlimbo"), elsewhere)
        assertFalse(WorldFolders.delete(link) { false })
        assertTrue(Files.exists(elsewhere.resolve("level.dat")))
    }

    @Test
    fun aMarkerIsAFileNotAFolder() {
        val sneaky = world("survival", false)
        Files.createDirectories(sneaky.resolve(WorldFolders.MARKER))
        assertFalse(WorldFolders.isOurs(sneaky))
        assertFalse(WorldFolders.delete(sneaky) { false })
    }

    @Test
    fun emptyAndMissingFoldersArentWorlds() {
        assertFalse(WorldFolders.hasWorld(container.resolve("nothing")))
        assertFalse(WorldFolders.hasWorld(Files.createDirectories(container.resolve("empty"))))
        assertTrue(WorldFolders.hasWorld(world("xlimbo", false)))
    }

    @Test
    fun theMarkerRemembersTheFloorIn2xsForm() {
        val ours = world("xlimbo", true)
        assertEquals("minecraft:purple_stained_glass@64", WorldFolders.readShape(ours))
        assertEquals("", WorldFolders.readShape(world("survival", false)))
    }
}
