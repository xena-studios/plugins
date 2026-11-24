package co.xenastudios.gradle

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.InetSocketAddress

/**
 * CurseForgePublish against a local server answering with the game-version lists in
 * src/test/resources/release. Those follow the shape of CurseForge's `/api/game/versions` and
 * `/api/game/version-types` (fields `id`, `gameVersionTypeID`, `name`, `slug`), with ids from
 * public release scripts (14417 is Bukkit's 1.21.11; 7499 Fabric). Swap in a real recording
 * once the release environment has CURSEFORGE_TOKEN.
 */
class CurseForgeTest {
    private fun resource(name: String) = javaClass.getResource("/release/$name")!!.readText()
    private val types = CurseForge.parseTypes(resource("curseforge-version-types.json"))
    private val versions = CurseForge.parseVersions(resource("curseforge-versions.json"))

    private val requests = mutableListOf<Triple<String, Map<String, String>, ByteArray>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/") { exchange ->
            val body = exchange.requestBody.readBytes()
            requests += Triple(exchange.requestURI.path, exchange.requestHeaders.mapValues { it.value.first() }, body)
            val (status, answer) = when (exchange.requestURI.path) {
                "/api/game/version-types" -> 200 to resource("curseforge-version-types.json")
                "/api/game/versions" -> 200 to resource("curseforge-versions.json")
                "/api/projects/123456/upload-file" -> 200 to """{"id": 4567}"""
                else -> 404 to """{"errorMessage": "not found"}"""
            }
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `resolves a Fabric mod's versions, loader, environment and Java`() {
        val resolved = CurseForge.resolve(CurseForgeClass.MODS, types, versions, listOf("1.21.11", "1.21.10"), "Fabric", 21)
        // 1.21.11 from the Minecraft 1.21 type, not Bukkit's 14417.
        assertEquals(listOf(14406, 14380, 7499, 9639, 11135), resolved.ids)
        assertEquals(emptyList<String>(), resolved.missing)
    }

    @Test
    fun `resolves a Bukkit range, leaving out what dev bukkit doesn't list`() {
        val resolved = CurseForge.resolve(CurseForgeClass.BUKKIT, types, versions, listOf("1.8.8", "1.9", "1.21.11"), null, 8)
        assertEquals(listOf(4545, 14417), resolved.ids)
        assertEquals(listOf("1.9"), resolved.missing)
    }

    @Test
    fun `fails without a known version or loader`() {
        assertThrows<GradleException> { CurseForge.resolve(CurseForgeClass.BUKKIT, types, versions, listOf("0.1"), null, null) }
        assertThrows<GradleException> { CurseForge.resolve(CurseForgeClass.MODS, types, versions, listOf("26.3"), "Quilt", 25) }
    }

    private fun release(dir: File, vararg jars: Pair<String, ReleasePlatform>) {
        val files = jars.map { (name, platform) ->
            val file = "xsample-1.0.0-$name.jar"
            dir.resolve(file).writeText("jar $name")
            ReleaseFile(name, file, platform, ReleaseAssemble.defaultLabel(name), listOf("1.21.11"),
                platform.modrinthLoaders(false), 21, Release.sha256(dir.resolve(file)), 7)
        }
        dir.resolve(ReleaseManifest.FILE).writeText(ReleaseManifest("xsample", "1.0.0-beta.1", "xsample-v1.0.0-beta.1",
            VersionType.BETA, Listing("xSample", null, null, null), "- First.", files).toJson())
    }

    private fun task(dir: File, dryRun: Boolean, token: String?): CurseForgePublish {
        val project = ProjectBuilder.builder().withProjectDir(dir.resolve("project").apply { mkdirs() }).build()
        return project.tasks.register("cf", CurseForgePublish::class.java) {
            projectClass.set(CurseForgeClass.MODS)
            projectId.set(123456)
            releaseDir.set(dir)
            this.dryRun.set(dryRun)
            host.set("http://127.0.0.1:${server.address.port}/api/")
            if (token != null) this.token.set(token)
        }.get()
    }

    @Test
    fun `uploads each mod jar with its ids, and nothing else`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        release(dir, "bukkit" to ReleasePlatform.BUKKIT, "fabric-1.21.11" to ReleasePlatform.FABRIC, "neoforge-1.21.11" to ReleasePlatform.NEOFORGE)
        task(dir, dryRun = false, token = "t0ken").publish()

        val uploads = requests.filter { it.first.endsWith("/upload-file") }
        assertEquals(2, uploads.size)
        assertTrue(requests.all { it.second["X-api-token"] == "t0ken" })
        val body = String(uploads[0].third)
        assertTrue("jar fabric-1.21.11" in body && "filename=\"xsample-1.0.0-fabric-1.21.11.jar\"" in body)
        val fabric = metadata(uploads[0].third)
        assertEquals("xSample 1.0.0-beta.1 (Fabric 1.21.11)", fabric["displayName"])
        assertEquals("beta", fabric["releaseType"])
        assertEquals("markdown", fabric["changelogType"])
        assertEquals(listOf(14406, 7499, 9639, 11135), fabric["gameVersions"])
        assertEquals(listOf(14406, 10150, 9639, 11135), metadata(uploads[1].third)["gameVersions"])
    }

    private fun metadata(body: ByteArray) = JsonSlurper().parseText(
        String(body).substringAfter("name=\"metadata\"\r\n\r\n").substringBefore("\r\n--"),
    ) as Map<*, *>

    @Test
    fun `a dry run sends nothing and says what it would send`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        release(dir, "fabric-1.21.11" to ReleasePlatform.FABRIC)
        task(dir, dryRun = true, token = null).publish()
        assertEquals(emptyList<Any>(), requests)
        val report = dir.resolve("dry-run/curseforge-mods.txt").readText()
        assertTrue("POST http://127.0.0.1:${server.address.port}/api/projects/123456/upload-file" in report)
        assertTrue("1.21.11, Fabric, Server" in report)
        assertFalse("t0ken" in report)
    }
}
