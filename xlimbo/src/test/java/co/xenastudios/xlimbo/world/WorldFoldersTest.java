package co.xenastudios.xlimbo.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rules that keep xLimbo from deleting anything it didn't make, on real folders. */
class WorldFoldersTest {

    @TempDir
    Path container;

    Path world(String name, boolean marked) throws IOException {
        Path folder = Files.createDirectories(container.resolve(name));
        Files.writeString(folder.resolve("level.dat"), "data");
        Files.createDirectories(folder.resolve("region"));
        Files.writeString(folder.resolve("region/r.0.0.mca"), "chunks");
        if (marked) {
            WorldFolders.mark(folder, "purple_stained_glass@64");
        }
        return folder;
    }

    @Test
    void namesAreOnlyEverOneFolder() {
        for (String bad : new String[] {"..", ".", "...", "../world", "a/b", "a\\b", "", " ", "lim bo", "/abs", "x\u0000"}) {
            assertFalse(WorldFolders.isPlainName(bad), bad);
            assertNull(WorldFolders.folder(bad, container, "world"), bad);
        }
        for (String good : new String[] {"xlimbo", "x.limbo-1", "Limbo_2", ".hidden"}) {
            assertTrue(WorldFolders.isPlainName(good), good);
        }
        assertFalse(WorldFolders.isPlainName(null));
    }

    @Test
    void findsTheFolderInEitherLayout() throws IOException {
        // 1.21: beside the main world.
        assertEquals(container.resolve("XLimbo"), WorldFolders.folder("XLimbo", container, "world"));
        // 26.1 and later: inside the main world, lowercased.
        Files.createDirectories(container.resolve("world/dimensions"));
        assertEquals(container.resolve("world/dimensions/minecraft/xlimbo"), WorldFolders.folder("XLimbo", container, "world"));
    }

    @Test
    void neverDeletesAWorldItDidntMake() throws IOException {
        Path theirs = world("survival", false);
        assertFalse(WorldFolders.delete(theirs, name -> false));
        assertTrue(Files.exists(theirs.resolve("region/r.0.0.mca")));
    }

    @Test
    void neverDeletesALoadedWorld() throws IOException {
        Path ours = world("xlimbo", true);
        assertFalse(WorldFolders.delete(ours, name -> name.equals("xlimbo")));
        assertTrue(Files.exists(ours.resolve("level.dat")));
    }

    @Test
    void deletesItsOwnWorldCompletely() throws IOException {
        Path ours = world("xlimbo", true);
        assertTrue(WorldFolders.delete(ours, name -> false));
        assertFalse(Files.exists(ours));
        assertTrue(Files.exists(container), "only the world's own folder goes");
    }

    @Test
    void aLinkInsideIsRemovedNeverFollowed() throws IOException {
        Path precious = world("survival", false);
        Path ours = world("xlimbo", true);
        Files.createSymbolicLink(ours.resolve("escape"), precious);
        assertTrue(WorldFolders.delete(ours, name -> false));
        assertFalse(Files.exists(ours));
        assertTrue(Files.exists(precious.resolve("region/r.0.0.mca")), "the link's target is untouched");
    }

    @Test
    void aLinkedWorldFolderIsEmptiedAndKept() throws IOException {
        Path ramdisk = world("ramdisk-limbo", true);
        Path link = Files.createSymbolicLink(container.resolve("xlimbo"), ramdisk);
        assertTrue(WorldFolders.delete(link, name -> false));
        assertTrue(Files.isSymbolicLink(link), "the owner's link stays");
        assertTrue(Files.isDirectory(ramdisk), "and the folder it points to");
        try (var left = Files.list(ramdisk)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void anUnmarkedLinkedFolderIsLeftAlone() throws IOException {
        Path elsewhere = world("elsewhere", false);
        Path link = Files.createSymbolicLink(container.resolve("xlimbo"), elsewhere);
        assertFalse(WorldFolders.delete(link, name -> false));
        assertTrue(Files.exists(elsewhere.resolve("level.dat")));
    }

    @Test
    void aMarkerIsAFileNotAFolder() throws IOException {
        Path sneaky = world("survival", false);
        Files.createDirectories(sneaky.resolve(WorldFolders.MARKER));
        assertFalse(WorldFolders.isOurs(sneaky));
        assertFalse(WorldFolders.delete(sneaky, name -> false));
    }

    @Test
    void emptyAndMissingFoldersArentWorlds() throws IOException {
        assertFalse(WorldFolders.hasWorld(container.resolve("nothing")));
        assertFalse(WorldFolders.hasWorld(Files.createDirectories(container.resolve("empty"))));
        assertTrue(WorldFolders.hasWorld(world("xlimbo", false)));
    }

    @Test
    void theMarkerRemembersTheFloor() throws IOException {
        Path ours = world("xlimbo", true);
        assertEquals("purple_stained_glass@64", WorldFolders.readShape(ours));
        assertEquals("", WorldFolders.readShape(world("survival", false)));
    }
}
