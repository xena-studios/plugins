package co.xenastudios.xbackup

import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Hashes
import co.xenastudios.xbackup.store.Maintenance
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * xBackup 2.0 and 1.0.1 share one repository format (decisions/2026-11-27-xbackup-2.md): the
 * released 1.0.1 jar, run as it ships, reads and restores what 2.0 wrote, and 2.0 reads and
 * restores what 1.0.1 wrote. Going back to 1.0.1 keeps every backup.
 */
class CompatTest {
    private val folder: Path = Files.createTempDirectory("xbackup-compat")

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun javaVersion(): Int = System.getProperty("java.specification.version").removePrefix("1.").substringBefore('.').toInt()

    @Test
    fun oneZeroOneReadsAndRestoresWhatTwoZeroWrote() {
        val jar = oldJar()
        if (javaVersion() < 21) return // 1.0.1 needs Java 21; CI's test JVM is newer.
        val root = Fixtures.server(folder.resolve("server"))
        val repo = folder.resolve("repository")
        Fixtures.repository(repo).use { repository ->
            repository.lock(true).use { Backup.run(repository, Fixtures.plan(root, repo), Backup.Pause.None, Fixtures.sqlite, { false }) {} }
        }
        val verify = cli(jar, folder, "verify", "--full", "--repo", repo.toString(), "--server", root.toString())
        assertEquals(0, verify.first, verify.second)
        assertTrue("All good." in verify.second, verify.second)
        val out = folder.resolve("restored-by-1.x")
        val restore = cli(jar, folder, "restore", "latest", "--to", out.toString(), "--repo", repo.toString(), "--server", root.toString(), "--yes")
        assertEquals(0, restore.first, restore.second)
        for ((name, bytes) in Fixtures.files(root).filterKeys { !it.startsWith("plugins/Some/data.db") }) {
            assertContentEquals(bytes, Files.readAllBytes(out.resolve(name)), name)
        }
        assertEquals(50, Fixtures.rows(out.resolve("plugins/Some/data.db")))
    }

    @Test
    fun twoZeroReadsAndRestoresWhatOneZeroOneWrote() {
        val jar = oldJar()
        if (javaVersion() < 21) return
        val root = Fixtures.server(folder.resolve("server"))
        val repo = folder.resolve("repository")
        oldBackup(jar, root, repo)
        Fixtures.repository(repo).use { repository ->
            val snapshot = repository.snapshots(ArrayList()).single()
            assertEquals("made by 1.0.1", snapshot.reason)
            assertTrue(Maintenance(repository) { false }.check(1.0, null, Random(1)).ok)
            val out = folder.resolve("restored-by-2.0")
            Restore.write(repository, Restore.expand(repository, snapshot, listOf(""), emptySet()), out)
            for ((name, bytes) in Fixtures.files(root).filterKeys { !it.startsWith("plugins/Some/data.db") }) {
                assertContentEquals(bytes, Files.readAllBytes(out.resolve(name)), name)
            }
        }
        // And 2.0 adds its backup beside 1.x's, reusing what 1.x stored.
        val second = Fixtures.repository(repo).use { repository ->
            repository.lock(true).use { Backup.run(repository, Fixtures.plan(root, repo, "made by 2.0"), Backup.Pause.None, Fixtures.sqlite, { false }) {} }
        }
        assertTrue(second.snapshot.added < 256 * 1024, "2.0 reuses 1.x's blobs: ${second.snapshot.added} bytes added")
        val verify = cli(jar, folder, "verify", "--full", "--repo", repo.toString(), "--server", root.toString())
        assertEquals(0, verify.first, verify.second)
    }

    // 1.0.1's own Backup.run, from its jar in a class loader of its own (its classes share names with 2.0's).
    private fun oldBackup(jar: Path, root: Path, repo: Path) {
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getSystemClassLoader().parent).use { loader ->
            fun type(name: String) = Class.forName(name, true, loader)
            val backend = type("co.xenastudios.xbackup.store.LocalBackend").getConstructor(Path::class.java).newInstance(repo)
            val backendType = type("co.xenastudios.xbackup.store.Backend")
            val repositoryType = type("co.xenastudios.xbackup.store.Repository")
            val repository = repositoryType.getMethod("open", backendType, Path::class.java, Boolean::class.javaPrimitiveType).invoke(null, backend, repo.resolve("tmp"), true)
            val throttle = type("co.xenastudios.xbackup.store.Throttle").getConstructor(Long::class.javaPrimitiveType).newInstance(0L)
            val options = type("co.xenastudios.xbackup.store.Session\$Options").getConstructor(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .newInstance(6, 4L shl 20, 0L)
            val planType = type("co.xenastudios.xbackup.backup.Backup\$Plan")
            val plan = planType.constructors.single().newInstance(
                root, listOf("world"), listOf("plugins", "server.properties"), emptyList<String>(), setOf(repo, root.resolve("plugins/xBackup")), emptyMap<String, Any>(),
                "made by 1.0.1", "xBackup 1.0.1", root.resolve("plugins/xBackup/temp"), throttle, options,
            )
            val pauseType = type("co.xenastudios.xbackup.backup.Backup\$Pause")
            val none = pauseType.getField("NONE").get(null)
            val databases = type("co.xenastudios.xbackup.backup.Databases")
            val run = type("co.xenastudios.xbackup.backup.Backup").getMethod("run", repositoryType, planType, pauseType, databases, BooleanSupplier::class.java, Consumer::class.java)
            val lock = repositoryType.getMethod("lock", Boolean::class.javaPrimitiveType).invoke(repository, true) as AutoCloseable
            try {
                // No database copier: 1.0.1's needs the server's SQLite driver; the database is left out, with a warning.
                run.invoke(null, repository, plan, none, null, BooleanSupplier { false }, Consumer<String> { })
            } finally {
                lock.close()
                (repository as AutoCloseable).close()
            }
        }
    }

    // Runs 1.0.1's command line, as an owner does: its exit code and what it printed.
    private fun cli(jar: Path, dir: Path, vararg args: String): Pair<Int, String> {
        val java = Paths.get(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java").toString()
        val log = dir.resolve("cli-${System.nanoTime()}.log")
        val process = ProcessBuilder(listOf(java, "-jar", jar.toString()) + args).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        process.outputStream.close()
        check(process.waitFor(3, TimeUnit.MINUTES)) { "1.0.1's command line didn't finish" }
        return process.exitValue() to String(Files.readAllBytes(log))
    }

    private companion object {
        const val URL_1_0_1 = "https://github.com/xena-studios/plugins/releases/download/xbackup-v1.0.1/xbackup-1.0.1.jar"
        const val SHA256_1_0_1 = "b852f9c31c08ec5f6f5298c50b72eefaaa414cd6860f9dc6019f2a5f2179670f"

        /** The released 1.0.1 jar, downloaded once and checked against its SHA-256. */
        fun oldJar(): Path {
            val cache = Paths.get(System.getProperty("java.io.tmpdir"), "xbackup-test-cache")
            Files.createDirectories(cache)
            val jar = cache.resolve("xbackup-1.0.1.jar")
            if (Files.exists(jar) && Hashes.sha256(Files.readAllBytes(jar)).hex() == SHA256_1_0_1) return jar
            var url = URL(URL_1_0_1)
            repeat(5) {
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location"))
                    return@repeat
                }
                check(status == 200) { "downloading 1.0.1 got HTTP $status" }
                val part = cache.resolve("xbackup-1.0.1.jar.part")
                connection.inputStream.use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
                check(Hashes.sha256(Files.readAllBytes(part)).hex() == SHA256_1_0_1) { "1.0.1's jar didn't match its SHA-256" }
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING)
                return jar
            }
            error("too many redirects downloading 1.0.1")
        }
    }
}
