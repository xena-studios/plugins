package co.xenastudios.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StoreChecksTest {
    private fun resource(name: String) = javaClass.getResource("/release/$name")!!.readText()

    private val jarFile = kotlin.io.path.createTempFile("xbans-2.0.0-bukkit", ".jar").toFile().apply { writeText("jar") }

    private fun jar(name: String, platform: ReleasePlatform, versions: List<String>) = ReleaseFile(
        name, "xbans-2.0.0-$name.jar", platform, ReleaseAssemble.defaultLabel(name), versions,
        platform.modrinthLoaders(folia = true), 21, "ab".repeat(32), 1000,
    )

    private fun manifest(vararg files: ReleaseFile, version: String = "2.0.0") = ReleaseManifest(
        "xbans", version, "xbans-v$version", VersionType.of(version),
        Listing("xBans", null, null, null), "### Fixed\n\n- **Bans** stick.", files.toList(),
    )

    // Recorded from https://api.modrinth.com/v2/tag/loader and /v2/tag/game_version.
    private val loaders = StoreReference.modrinthLoaders(resource("modrinth-loaders.json"))
    private val gameVersions = StoreReference.modrinthGameVersions(resource("modrinth-game-versions.json"))

    @Test
    fun `reads Modrinth's plugin and mod loaders`() {
        assertTrue(listOf("paper", "folia", "spigot", "bukkit", "purpur", "velocity", "bungeecord", "waterfall", "fabric").all { it in loaders })
        assertTrue("datapack" in loaders, "datapack takes mods too")
        assertTrue("minecraft" !in loaders)
    }

    @Test
    fun `passes every Modrinth payload the release sends`() {
        val bukkit = jar("bukkit", ReleasePlatform.BUKKIT, Release.minecraftReleases(resource("modrinth-game-versions.json"), "1.8.8", "26.3"))
        val velocity = jar("velocity", ReleasePlatform.VELOCITY, listOf("1.21.11"))
        val bungee = jar("bungeecord", ReleasePlatform.BUNGEECORD, listOf("1.21.11"))
        val fabric = jar("fabric-1.21.11", ReleasePlatform.FABRIC, listOf("1.21.11"))
        val release = manifest(bukkit, velocity, bungee, fabric)
        for (file in release.files) {
            assertEquals(emptyList<String>(), StoreChecks.modrinth(Modrinth.data(release, file, "AbCd1234"), jarFile, loaders, gameVersions), file.name)
        }
    }

    @Test
    fun `refuses what Modrinth would refuse`() {
        val fabric = jar("fabric-1.21.11", ReleasePlatform.FABRIC, listOf("1.21.11"))
        val data = JsonSlurper().parseText(Modrinth.data(manifest(fabric), fabric, "AbCd1234")) as MutableMap<String, Any?>
        fun problems(change: (MutableMap<String, Any?>) -> Unit) =
            StoreChecks.modrinth(JsonOutput.toJson(LinkedHashMap(data).also(change)), jarFile, loaders, gameVersions)

        assertTrue(problems { it["version_number"] = "2.0.0+fabric 1.21.11" }.single().contains("doesn't match"))
        assertTrue(problems { it["version_number"] = "2.0.0+" + "x".repeat(40) }.single().contains("at most 32"))
        assertTrue(problems { it["name"] = "x".repeat(65) }.single().contains("at most 64"))
        assertTrue(problems { it["version_type"] = "snapshot" }.single().contains("must be one of"))
        assertTrue(problems { it["loaders"] = listOf("quilted") }.single().contains("no loader \"quilted\""))
        assertTrue(problems { it["game_versions"] = listOf("1.21.12") }.single().contains("doesn't list Minecraft 1.21.12"))
        assertTrue(problems { it["game_versions"] = emptyList<String>() }.single().contains("at least 1"))
        assertTrue(problems { it.remove("featured") }.single().contains("missing \"featured\""))
        assertTrue(problems { it["changelog"] = "x".repeat(65537) }.single().contains("at most 65536"))
        assertTrue(problems { it["extra"] = 1 }.single().contains("doesn't take"))
    }

    @Test
    fun `passes Hangar's upload for Paper, Velocity and Waterfall`() {
        val known = mapOf(
            "PAPER" to Hangar.platformVersions(resource("hangar-paper-versions.json")),
            "VELOCITY" to Hangar.platformVersions(resource("hangar-velocity-versions.json")),
            "WATERFALL" to Hangar.platformVersions(resource("hangar-waterfall-versions.json")),
        )
        val bukkit = jar("bukkit", ReleasePlatform.BUKKIT, Release.minecraftReleases(resource("modrinth-game-versions.json"), "1.8.8", "26.3"))
        val velocity = jar("velocity", ReleasePlatform.VELOCITY, listOf("1.8.8", "26.3")).copy(hangarFrom = "3.4")
        val bungee = jar("bungeecord", ReleasePlatform.BUNGEECORD, listOf("1.8.8", "26.3")).copy(hangarVersions = listOf("1.21"))
        val jars = listOf(bukkit, velocity, bungee)
        val upload = Hangar.versionUpload(manifest(*jars.toTypedArray()), jars, known)
        assertEquals(emptyList<String>(), StoreChecks.hangar(upload, jars.map { jarFile }, known))
        val platforms = (JsonSlurper().parseText(upload) as Map<*, *>)["platformDependencies"] as Map<*, *>
        assertEquals(listOf("3.4", "3.4.0", "3.5", "3.5.0", "3.5.1", "4.0.0", "4.1.0", "4.1.1", "4.2.0", "4.2.1", "4.3.0", "4.3.1"), platforms["VELOCITY"])
        assertEquals(listOf("1.21"), platforms["WATERFALL"])
        assertEquals("26.3", (platforms["PAPER"] as List<*>).last())
    }

    @Test
    fun `never guesses Waterfall's versions`() {
        val bungee = jar("bungeecord", ReleasePlatform.BUNGEECORD, listOf("1.8.8", "1.21"))
        val e = assertThrows<GradleException> { Hangar.versionUpload(manifest(bungee), listOf(bungee), emptyMap()) }
        assertTrue("Waterfall" in e.message!!)
    }

    @Test
    fun `refuses what Hangar would refuse`() {
        val bukkit = jar("bukkit", ReleasePlatform.BUKKIT, listOf("26.3"))
        val known = mapOf("PAPER" to setOf("26.3"))
        val upload = JsonSlurper().parseText(Hangar.versionUpload(manifest(bukkit), listOf(bukkit), known)) as Map<*, *>
        fun problems(jars: Int = 1, change: (MutableMap<String, Any?>) -> Unit) =
            StoreChecks.hangar(JsonOutput.toJson(LinkedHashMap(upload as Map<String, Any?>).also(change)), List(jars) { jarFile }, known)

        assertTrue(problems { it["version"] = "2.0.0 beta" }.single().contains("doesn't match"))
        assertTrue(problems { it["channel"] = "Pre-release" }.single().contains("doesn't match"))
        assertTrue(problems { it["description"] = "x".repeat(32001) }.single().contains("at most 32000"))
        assertTrue(problems { it["platformDependencies"] = mapOf("PAPER" to listOf("1.7.10")) }.single().contains("doesn't list PAPER 1.7.10"))
        assertTrue(problems(jars = 2) {}.single().contains("2 jars for 1 files"))
        assertTrue(problems { it["files"] = listOf(mapOf("platforms" to listOf("FOLIA"))) }.any { "must be one of" in it })
        val big = kotlin.io.path.createTempFile(suffix = ".jar").toFile().apply { writeBytes(ByteArray(StoreChecks.HANGAR_MAX_FILE.toInt() + 1)) }
        assertTrue(StoreChecks.hangar(JsonOutput.toJson(upload), listOf(big), known).single().contains("up to 7000000 bytes"))
    }

    @Test
    fun `checks CurseForge's metadata and voxel shop's fields`() {
        val metadata = CurseForge.metadata("xBans 2.0.0", "- A fix.", VersionType.RELEASE, listOf(1, 2))
        assertEquals(emptyList<String>(), StoreChecks.curseForge(metadata, jarFile, resolved = true))
        assertEquals(emptyList<String>(), StoreChecks.curseForge(CurseForge.metadata("xBans", "-", VersionType.BETA, null), jarFile, resolved = false))
        assertTrue(StoreChecks.curseForge(CurseForge.metadata("xBans", "-", VersionType.BETA, null), jarFile, resolved = true).single().contains("gameVersions"))
        assertTrue(StoreChecks.curseForge(CurseForge.metadata("xBans", "-", VersionType.BETA, emptyList()), jarFile, resolved = true).any { "at least 1" in it })

        val upload = Voxel.upload(10714, "2.0.0", "xBans 2.0.0", "- A fix.", true, "<key>", jarFile)
        assertEquals(emptyList<String>(), StoreChecks.voxel(upload.fields, jarFile))
        assertTrue(StoreChecks.voxel(upload.fields - "title", jarFile).single().contains("missing \"title\""))
    }

    @Test
    fun `checks a store page's length`() {
        assertEquals(emptyList<String>(), StorePage.problems(StorePage.modrinthBody("# xBans\n\nBans."), StorePage.hangarBody("# xBans\n\nBans for Paper.")))
        val page = "# xBans\n\n<!-- modrinth_exclude.start -->\nModrinth only\n<!-- modrinth_exclude.end -->\nShort"
        assertEquals("# xBans\n\nShort", (JsonSlurper().parseText(StorePage.hangarBody(page)) as Map<*, *>)["content"])
        assertTrue(StorePage.problems(StorePage.modrinthBody(page), StorePage.hangarBody(page)).single().startsWith("Hangar: "))
    }

    @Test
    fun `fails with every problem at once`() {
        val e = assertThrows<GradleException> { StoreChecks.require("xBans 2.0.0 on Modrinth", listOf("a", "b")) }
        assertTrue("nothing was sent" in e.message!! && "\n  a\n  b" in e.message!!)
    }
}
