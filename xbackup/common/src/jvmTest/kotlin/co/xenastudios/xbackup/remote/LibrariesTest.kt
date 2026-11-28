package co.xenastudios.xbackup.remote

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** SFTP's and FTP's libraries: pinned, checked as they're downloaded, loaded apart (1.x's LibrariesTest). */
class LibrariesTest {
    private val dir: Path = Files.createTempDirectory("xbackup-libraries")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun jarOf(type: Class<*>): Path = Paths.get(type.protectionDomain.codeSource.location.toURI())

    @Test
    fun pinnedHashesMatchTheCatalogVersions() {
        for ((library, type) in listOf(Libraries.JSCH to com.jcraft.jsch.JSch::class.java, Libraries.COMMONS_NET to org.apache.commons.net.ftp.FTPClient::class.java)) {
            val jar = jarOf(type)
            assertEquals(library.fileName, jar.fileName.toString(), "gradle/libs.versions.toml and Libraries disagree on the version")
            assertEquals(library.sha256, Libraries.sha256(jar), "update the SHA-256 in Libraries for ${library.fileName}")
        }
    }

    @Test
    fun theDownloadIsCheckedAgainstItsHash() {
        val real = Files.readAllBytes(jarOf(com.jcraft.jsch.JSch::class.java))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/good/") { exchange ->
            exchange.sendResponseHeaders(200, real.size.toLong())
            exchange.responseBody.write(real)
            exchange.close()
        }
        server.createContext("/tampered/") { exchange ->
            val bad = real.clone().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            exchange.sendResponseHeaders(200, bad.size.toLong())
            exchange.responseBody.write(bad)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val refused = assertFailsWith<IOException> { Libraries.fetch(Libraries.JSCH, dir.resolve("a"), "offsite", listOf("$base/tampered/")) }
            assertTrue("offsite" in refused.message!!, refused.message)
            assertTrue(Files.notExists(dir.resolve("a").resolve(Libraries.JSCH.fileName)), "a bad download is never kept")
            // The first mirror's copy is bad: the next one's is used.
            val jar = Libraries.fetch(Libraries.JSCH, dir.resolve("b"), "offsite", listOf("$base/tampered/", "$base/good/"))
            assertEquals(Libraries.JSCH.sha256, Libraries.sha256(jar))
            // Already there and right: no download at all.
            assertEquals(jar, Libraries.fetch(Libraries.JSCH, dir.resolve("b"), "offsite", emptyList()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theLibraryAndXBackupsCodeLoadFromTheirOwnJars() {
        val own = Libraries::class.java.protectionDomain.codeSource.location
        val jsch = jarOf(com.jcraft.jsch.JSch::class.java).toUri().toURL()
        val loader = Libraries.isolated(own, jsch, Libraries.JSCH)
        val backend = Class.forName("co.xenastudios.xbackup.remote.lib.SftpBackend", true, loader)
        assertSame(loader, backend.classLoader)
        val library = Class.forName("com.jcraft.jsch.JSch", false, loader)
        assertSame(loader, library.classLoader)
        assertNotSame(com.jcraft.jsch.JSch::class.java, library, "the library's classes come from its own jar")
        // Everything else is shared, so the backend is a Backend to the rest of xBackup.
        assertSame(co.xenastudios.xbackup.store.Backend::class.java, Class.forName("co.xenastudios.xbackup.store.Backend", false, loader))
        // A library jar can't stand in for xBackup's classes, even if it had one by that name.
        val swapped = Libraries.isolated(jsch, own, Libraries.JSCH)
        assertFailsWith<ClassNotFoundException> { Class.forName("co.xenastudios.xbackup.remote.lib.SftpBackend", true, swapped) }
    }
}
