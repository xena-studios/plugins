package co.xenastudios.xbackup.restore

import co.xenastudios.xbackup.store.BlobType
import co.xenastudios.xbackup.store.Hashes
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.Snapshot
import co.xenastudios.xbackup.store.Tree
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A repository someone tampered with can't make a restore write outside its folder, as 1.x's
 * RestoreSafetyTest: links out are refused unless the owner allows them, nothing is written
 * through a link, names that mean another file on Windows are refused, and xBackup's own folder
 * is never restored over.
 */
class RestoreSafetyTest {
    private val dir: Path = Files.createTempDirectory("xbackup-safety")
    private val repository: Repository = Repository.open(LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true)
    private val session: Session = repository.session(Session.Options.DEFAULTS)

    @AfterTest
    fun clean() {
        session.close()
        repository.close()
        dir.toFile().deleteRecursively()
    }

    private fun file(name: String, text: String): Tree.Node {
        val bytes = text.toByteArray()
        val blob = session.store(BlobType.DATA, bytes, true)
        return Tree.Node.file(name, 1_700_000_000_000L, bytes.size.toLong(), -1, listOf(blob), Hashes.sha256(bytes))
    }

    private fun folder(name: String, vararg nodes: Tree.Node): Tree.Node = Tree.Node.dir(name, 1_700_000_000_000L, -1, session.storeTree(Tree(nodes.toList())))

    private fun snapshot(vararg top: Tree.Node): Snapshot {
        val tree = session.storeTree(Tree(top.toList()))
        val now = Clock.System.now()
        return session.commit(Snapshot(null, now, now, tree, null, "test", 1, 1, 1, 0, 0, "test", emptyList()))
    }

    private fun restore(snapshot: Snapshot, into: Path, allowLinksOutside: Boolean) =
        Restore.write(repository, Restore.expand(repository, snapshot, listOf(""), emptySet()), into, into, allowLinksOutside)

    @Test
    fun aLinkPointingOutsideIsRefused() {
        val up = snapshot(folder("plugins", Tree.Node.link("evil", 0, "../../outside")), file("a.txt", "a"))
        val into = dir.resolve("server")
        val refused = assertFailsWith<Restore.UnsafeException> { restore(up, into, false) }
        assertTrue("outside" in refused.message!!, refused.message)
        assertFalse(Files.exists(into.resolve("plugins/evil"), LinkOption.NOFOLLOW_LINKS))
        val absolute = snapshot(Tree.Node.link("passwd", 0, dir.resolve("secret").toString()))
        assertFailsWith<Restore.UnsafeException> { restore(absolute, dir.resolve("server2"), false) }
        // Links that stay inside come back, and the owner can allow the rest on purpose.
        val inside = snapshot(folder("plugins", file("real.yml", "x"), Tree.Node.link("alias.yml", 0, "real.yml")), Tree.Node.link("world", 0, "worlds/main"))
        restore(inside, dir.resolve("server3"), false)
        assertEquals("real.yml", Files.readSymbolicLink(dir.resolve("server3/plugins/alias.yml")).toString())
        restore(up, dir.resolve("server4"), true)
        assertTrue(Files.isSymbolicLink(dir.resolve("server4/plugins/evil")))
    }

    @Test
    fun nothingIsWrittenThroughALink() {
        // On a disk that ignores case, the link "A" and the folder "a" are one name: writing a/x
        // would follow the link. It must be refused there, and both kept apart elsewhere.
        Files.createDirectories(dir.resolve("probe"))
        Files.write(dir.resolve("probe/Q"), ByteArray(0))
        val ignoresCase = Files.exists(dir.resolve("probe/q"))
        val clash = snapshot(Tree.Node.link("A", 0, "target"), folder("a", file("x", "through the link")), folder("target"))
        val into = dir.resolve("server")
        if (ignoresCase) {
            val refused = assertFailsWith<Restore.UnsafeException> { restore(clash, into, false) }
            assertTrue("case" in refused.message!!, refused.message)
        } else {
            restore(clash, into, false)
            assertTrue(Files.isRegularFile(into.resolve("a/x")))
        }
        assertFalse(Files.exists(into.resolve("target/x")), "a file was written through the link")
    }

    @Test
    fun namesThatArentPlainAreRefused() {
        assertFailsWith<Restore.UnsafeException> { Restore.checkName("..\\..\\evil", "plugins", true) }
        assertFailsWith<Restore.UnsafeException> { Restore.checkName("C:evil", "", true) }
        assertFailsWith<Restore.UnsafeException> { Restore.checkName("file:stream", "", true) }
        assertFailsWith<Restore.UnsafeException> { Restore.checkName("bell\u0007", "", false) }
        assertFailsWith<Restore.UnsafeException> { Restore.checkName("new\nline", "", false) }
        // Ordinary characters in a name on Linux and macOS.
        Restore.checkName("minecraft:stone.yml", "", false)
        Restore.checkName("back\\slash", "", false)
        Restore.checkName("ünïcode name.yml", "", true)
        // Windows' other ways for a name to mean another file.
        for (name in listOf("world.", "world ", "NUL", "nul.txt", "Com1", "LPT9.log", "CONIN$", "PROGRA~1", "a<b", "what?")) {
            assertFailsWith<Restore.UnsafeException>(name) { Restore.checkName(name, "", true) }
            Restore.checkName(name, "", false)
        }
        Restore.checkName("console.yml", "", true)
        Restore.checkName("null.txt", "", true)
    }

    @Test
    fun protectedPathsAreLeftOutWhateverTheirCase() {
        val snapshot = snapshot(folder("Plugins", folder("XBACKUP", file("secrets.yml", "old")), file("other.yml", "o")))
        val items = Restore.expand(repository, snapshot, listOf(""), setOf("plugins/xBackup"))
        assertEquals(listOf("Plugins/other.yml"), items.map { it.path })
    }

    @Test
    fun aLinkThroughALinkOutsideIsRefused() {
        // The server folder already has a link "shared" leading out of it: a restored link into
        // shared/... reads as inside, but leads outside.
        val into = dir.resolve("server")
        Files.createDirectories(dir.resolve("elsewhere"))
        Files.createDirectories(into)
        Files.createSymbolicLink(into.resolve("shared"), dir.resolve("elsewhere"))
        val through = snapshot(Tree.Node.link("sneaky", 0, "shared/passwd"))
        val items = Restore.expand(repository, through, listOf("sneaky"), emptySet())
        val refused = assertFailsWith<Restore.UnsafeException> { Restore.write(repository, items, dir.resolve("staged"), into, false) }
        assertTrue("outside" in refused.message!!, refused.message)
    }
}
