package co.xenastudios.core.jvm.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigError
import co.xenastudios.core.common.config.ConfigNotice
import co.xenastudios.core.common.config.ConfigProblem
import co.xenastudios.core.common.config.Configs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigFileTest {

    private val dir: Path = Files.createTempDirectory("config-file-test")
    private val path: Path = dir.resolve("config.yml")
    private val file = ConfigFile(ShopConfig, path)

    @AfterTest
    fun cleanUp() {
        dir.toFile().setWritable(true)
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach {
            it.toFile().setWritable(true)
            Files.deleteIfExists(it)
        }
    }

    private fun load(): ConfigFile.Loaded = assertIs<Outcome.Ok<ConfigFile.Loaded>>(file.load()).value

    private fun text(p: Path = path) = String(Files.readAllBytes(p), Charsets.UTF_8)

    private fun write(text: String, p: Path = path) {
        Files.write(p, text.toByteArray(Charsets.UTF_8))
    }

    private fun files() = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toArray().toList() }

    @Test
    fun theFirstLoadWritesTheDefaults() {
        val loaded = load()
        assertEquals(Configs.defaultText(ShopConfig), text())
        assertEquals(listOf(ConfigNotice.Kind.WROTE_DEFAULTS), loaded.notices.map { it.kind })
        assertEquals(listOf("config.yml"), files())
        // And a second leaves it alone.
        assertEquals(emptyList(), load().notices)
    }

    @Test
    fun theFolderIsMadeIfItIsntThere() {
        val nested = ConfigFile(ShopConfig, dir.resolve("plugins/Shop/config.yml"))
        assertIs<Outcome.Ok<ConfigFile.Loaded>>(nested.load())
        assertTrue(Files.isRegularFile(dir.resolve("plugins/Shop/config.yml")))
    }

    @Test
    fun anOldFileIsCopiedAsideThenCarriedForward() {
        val old = "config-version: 1\nshop-name: Bob's\n"
        write(old)
        val loaded = load()
        assertEquals("Bob's", loaded.config[ShopConfig.name])
        assertEquals(1, loaded.carriedFrom)
        assertEquals(old, text(dir.resolve("config.yml.bak-v1")))
        assertTrue("name: \"Bob's\"" in text() && "config-version: 2" in text(), text())
        assertEquals("The old config.yml is kept as config.yml.bak-v1.", loaded.notices.single { it.kind == ConfigNotice.Kind.BACKED_UP }.message)

        // Another old file later doesn't overwrite the first copy.
        write(old)
        load()
        assertEquals(listOf("config.yml", "config.yml.bak-v1", "config.yml.bak-v1-2"), files())
    }

    @Test
    fun peekingWritesNothing() {
        assertEquals(ShopConfig.defaults, assertIs<Outcome.Ok<ConfigFile.Loaded>>(file.read()).value.config)
        assertEquals(emptyList(), files())
        write("config-version: 1\nshop-name: Bob's\n")
        val peek = assertIs<Outcome.Ok<ConfigFile.Loaded>>(file.read()).value
        assertEquals("Bob's", peek.config[ShopConfig.name])
        assertEquals(emptyList(), peek.notices)
        assertEquals(listOf("config.yml"), files())
        assertEquals("config-version: 1\nshop-name: Bob's\n", text())
    }

    @Test
    fun aFileThatIsntYamlIsLeftAloneAndSaysWhere() {
        write("name: [oops\nmode: loud\n")
        val error = assertIs<Outcome.Err<ConfigError>>(file.load()).error
        assertTrue(error.message.startsWith("config.yml isn't valid YAML"), error.message)
        assertTrue(error.line > 0)
        assertEquals("name: [oops\nmode: loud\n", text())
        assertEquals(listOf("config.yml"), files())
    }

    @Test
    fun aConfigThatCantBeSavedIsStillUsed() {
        write("name: Bob's\n")
        // The temporary file's place is taken, so the write fails.
        Files.createDirectory(dir.resolve("config.yml.tmp"))
        Files.write(dir.resolve("config.yml.tmp/keep"), byteArrayOf())
        val loaded = load()
        assertEquals("Bob's", loaded.config[ShopConfig.name])
        assertEquals(ConfigProblem.Kind.SAVE_FAILED, loaded.problems.single().kind)
        assertEquals(emptyList(), loaded.notices)
        assertEquals("name: Bob's\n", text())
    }

    @Test
    fun aCarryThatCantBeSavedLeavesNoBackupBehind() {
        write("config-version: 1\nshop-name: Bob's\n")
        Files.createDirectory(dir.resolve("config.yml.tmp"))
        Files.write(dir.resolve("config.yml.tmp/keep"), byteArrayOf())
        val loaded = load()
        assertEquals("Bob's", loaded.config[ShopConfig.name])
        assertEquals(ConfigProblem.Kind.SAVE_FAILED, loaded.problems.single().kind)
        assertEquals(listOf("config.yml", "config.yml.tmp"), files())
        assertEquals("config-version: 1\nshop-name: Bob's\n", text())
    }

    @Test
    fun aReadOnlyFolderStillGivesTheSettings() {
        write("config-version: 1\nshop-name: Bob's\n")
        if (!readOnly(dir)) return
        val carried = load()
        assertEquals("Bob's", carried.config[ShopConfig.name])
        assertEquals(ConfigProblem.Kind.BACKUP_FAILED, carried.problems.single().kind)
        assertEquals(emptyList(), carried.notices)
        assertEquals(listOf("config.yml"), files())
    }

    @Test
    fun anEditWritesTheChangeAndKeepsTheRest() {
        write("config-version: 2\nname: Bob's\nextra: 1\n")
        assertEquals(Outcome.Ok(true), file.edit { this[ShopConfig.words] = this[ShopConfig.words].orEmpty() + "bye" })
        assertTrue("words: [\"bye\"]" in text(), text())
        assertTrue("name: \"Bob's\"" in text() && "extra: 1" in text(), text())
        val before = text()
        assertEquals(Outcome.Ok(false), file.edit { this[ShopConfig.name] = "Bob's" })
        assertEquals(before, text())
        assertEquals(listOf("config.yml"), files())
    }

    @Test
    fun anEditNeverTouchesAFileThatIsntYaml() {
        write("name: [oops\n")
        assertIs<Outcome.Err<ConfigError>>(file.edit { this[ShopConfig.name] = "x" })
        assertEquals("name: [oops\n", text())
    }

    @Test
    fun aWriteReplacesTheFileWhole() {
        write("old")
        ConfigFile.writeAtomically(path, "new\n")
        assertEquals("new\n", text())
        assertFalse(Files.exists(dir.resolve("config.yml.tmp")))
    }

    // Makes the folder read-only, or says it can't (Windows, or running as root).
    private fun readOnly(folder: Path): Boolean {
        try {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"))
        } catch (e: UnsupportedOperationException) {
            return false
        }
        return !Files.isWritable(folder)
    }
}
