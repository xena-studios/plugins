package co.xenastudios.xbackup

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xbackup.config.Legacy
import co.xenastudios.xbackup.config.XBackupConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1.x's config carried into 2.0, every setting meaning what it meant: its messages into
 * `lang/en.yml` where edited, the theme into `messages.theme`, the remotes' passwords into
 * `secrets.yml`, and a copy of the old file aside for the 1.x jar put back
 * (`config.yml.bak-v1`). The fixtures are the config 1.0.0 and 1.0.1 wrote (the same file), and
 * one with an owner's edits.
 */
class MigrationTest {
    private val folder: Path = Files.createTempDirectory("xbackup-migration")
    private val home: Path = folder.resolve("xBackup")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files() = JvmFiles(home, Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml), emptyMap(), log)

    private fun write(name: String, text: String) {
        Files.createDirectories(home)
        Files.write(home.resolve(name), text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(home.resolve(name)), StandardCharsets.UTF_8)

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.0.x.yml")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a message 1.x shipped would be carried as if edited")
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.x.yml"))
        val loaded = files().load()
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(home.resolve("config.yml.bak-v1")))
        assertFalse("\ntheme:" in text, "1.x's theme is still at the top")
        assertFalse("update-checker" in text)
        assertFalse(Files.exists(home.resolve("lang/en.yml")) && "Nope" in read("lang/en.yml"))
        val config = loaded.config
        assertEquals(3, config[XBackupConfig.retention.last])
        assertEquals(false, config[XBackupConfig.broadcasts.start])
        assertEquals(true, config[XBackupConfig.updates.check])
        assertEquals(listOf("backups"), config[XBackupConfig.command.aliases])
        assertFalse(Files.exists(home.resolve("secrets.yml")))
    }

    @Test
    fun anEdited1xConfigKeepsEveryEdit() {
        write("config.yml", resource("config-1.0.x-edited.yml.txt"))
        val loaded = files().load()
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        val config = loaded.config
        assertEquals(5, config[XBackupConfig.retention.last])
        assertEquals(false, config[XBackupConfig.updates.check])
        // A start message 1.x was given text for: the broadcast is on, and says it.
        assertEquals(true, config[XBackupConfig.broadcasts.start])
        assertEquals(false, config[XBackupConfig.broadcasts.done])
        val lang = read("lang/en.yml")
        assertTrue("Nope." in lang && "Backing up..." in lang, lang)
        val text = read("config.yml")
        assertTrue("highlight: \"<gold>\"" in text || "highlight: <gold>" in text, text)
        assertTrue("every: \"30m\"" in text || "every: 30m" in text)
        // The remote's password moved to the owner-only secrets.yml; config.yml keeps the rest.
        assertFalse("fixture-secret" in text)
        assertTrue("backup.example.com" in text)
        assertTrue("fixture-secret" in read("secrets.yml"))
        assertEquals("fixture-secret", Secrets.read(home) {}["offsite"]?.get("password"))
        // The copy aside is the file as 1.x left it, for the 1.x jar put back.
        // The copy aside, for the 1.x jar put back (which reads secrets.yml too), holds no password either.
        assertFalse("fixture-secret" in read("config.yml.bak-v1"))
        assertTrue("config-version: 1" in read("config.yml.bak-v1"))
    }

    @Test
    fun aCarriedConfigLoadsAgainUnchanged() {
        write("config.yml", resource("config-1.0.x-edited.yml.txt"))
        files().load()
        val once = read("config.yml")
        val again = files().load()
        assertEquals(emptyList(), again.problems)
        assertEquals(once, read("config.yml"))
    }
}
