package co.xenastudios.xvote

import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xvote.receiver.Keys
import co.xenastudios.xvote.receiver.Tokens
import co.xenastudios.xvote.receiver.Fixtures
import java.nio.file.Files as NioFiles
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/xvote import`: VotingPlugin's SQLite `Users`, SuperbVote's JSON, and NuVotifier's keys and tokens, through the command. */
class ImportTest {
    private val root = NioFiles.createTempDirectory("xvote-import")
    private val plugins = root.resolve("plugins").also { NioFiles.createDirectories(it) }
    private val xvote = plugins.resolve("xVote")
    private val ports = ArrayList<Int>()
    private val files = object : Files {
        override fun load(): Loaded = throw UnsupportedOperationException()
        override fun useReceiver(port: Int, host: String?, v1: Boolean): String? = null.also { ports += port }
    }
    private val imports = DeferredImports(plugins, xvote, Drivers.serverOnly(javaClass.classLoader), RecordingLog(), files)
    private val steve = FakeVoteServer.ref("Steve")
    private val alex = FakeVoteServer.ref("Alex")

    @AfterTest
    fun clean() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun votingPluginsUsersTable() {
        val dir = NioFiles.createDirectories(plugins.resolve("VotingPlugin"))
        val file = dir.resolve("Users.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { s ->
                // VotingPlugin keeps every column as text.
                s.execute("CREATE TABLE Users (uuid VARCHAR(37), PlayerName TEXT, AllTimeTotal TEXT, LastVotes TEXT, Points TEXT)")
                s.execute("INSERT INTO Users VALUES ('$steve', 'Steve', '42', 'PlanetMinecraft//1767427200000%line%Minecraft-MP//1767513600000', '0')")
                s.execute("INSERT INTO Users VALUES ('$alex', 'Alex', '0', '', '0')")
                s.execute("INSERT INTO Users VALUES ('not-a-uuid', 'Bad', '5', '', '0')")
            }
        }
        val before = NioFiles.readAllBytes(file)
        val read = imports.totals("votingplugin") as co.xenastudios.core.common.Outcome.Ok
        val (where, players) = read.value
        assertEquals("plugins/VotingPlugin/Users.db", where)
        assertEquals(1, players.size)
        assertEquals(42, players.single().total)
        assertEquals(1767513600000, players.single().lastVote)
        assertTrue(before.contentEquals(NioFiles.readAllBytes(file)), "VotingPlugin's file is left as it was")
        // Through the command, twice: the second needs `again`.
        val h = Harness(root.resolve("db").also { NioFiles.createDirectories(it) }, imports = imports).start()
        try {
            h.run("xvote import votingplugin") { h.console.replies.any { "Imported" in it } }
            assertEquals(42, h.plugin.stats(steve).total)
            h.run("xvote import votingplugin") { h.console.replies.any { "imported before" in it } }
            h.run("xvote import votingplugin again") { h.console.replies.count { "Imported" in it } == 2 }
            assertEquals(84, h.plugin.stats(steve).total)
        } finally {
            h.stop()
        }
    }

    @Test
    fun superbVotesJson() {
        val players = FileImports.superbVoteJson(
            """{"version": 2, "records": {"$steve": {"lastKnownUsername": "Steve", "votes": 7, "lastVoted": 1767427200000}, "$alex": {"votes": 0}}}""",
        )
        assertEquals(1, players.size)
        assertEquals(7, players.single().total)
        assertEquals("Steve", players.single().name)
        // SuperbVote 0.x kept a number per player.
        assertEquals(3, FileImports.superbVoteJson("""{"$alex": 3}""").single().total)
        val dir = NioFiles.createDirectories(plugins.resolve("SuperbVote"))
        NioFiles.write(dir.resolve("votes.json"), """{"records": {"$steve": {"votes": 2}}}""".toByteArray())
        assertEquals("plugins/SuperbVote/votes.json", (imports.totals("superbvote") as co.xenastudios.core.common.Outcome.Ok).value.first)
    }

    @Test
    fun missingFilesSaySo() {
        assertTrue((imports.totals("votingplugin") as co.xenastudios.core.common.Outcome.Err).error.contains("there's no plugins/VotingPlugin/Users.db"))
        assertTrue((imports.totals("superbvote") as co.xenastudios.core.common.Outcome.Err).error.contains("there's no plugins/SuperbVote/votes.json"))
        assertEquals(null, imports.nuVotifier())
    }

    @Test
    fun nuVotifiersSetupIsTakenOverWithXVotesOwnKeptAside() {
        val nuvotifier = plugins.resolve("Votifier")
        NioFiles.createDirectories(nuvotifier.resolve("rsa"))
        for (name in listOf("config.yml", "rsa/public.key", "rsa/private.key")) NioFiles.copy(Fixtures.FOLDER.resolve(name), nuvotifier.resolve(name))
        NioFiles.createDirectories(xvote)
        Tokens.of(mapOf("default" to "xvotes-own")).save(xvote)
        Keys.save(xvote.resolve("rsa"), Keys.generate())
        val taken = (imports.nuVotifier() as co.xenastudios.core.common.Outcome.Ok).value
        assertEquals("its keys, 2 tokens, port 8193", taken)
        assertEquals(listOf(8193), ports)
        assertEquals(Keys.publicText(Fixtures.keys()), Keys.publicText(Keys.load(xvote.resolve("rsa"))))
        assertEquals("r1kpn4qcl9m3c0k4tbm2ffbu3e", Tokens.loadOrCreate(xvote).first.forService("default"))
        assertTrue(NioFiles.list(xvote).use { files -> files.anyMatch { it.fileName.toString().startsWith("tokens.yml.before-import-") } })
        assertTrue(NioFiles.list(xvote).use { files -> files.anyMatch { it.fileName.toString().startsWith("rsa.before-import-") } })
    }
}
