package co.xenastudios.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReleaseTest {
    private fun resource(name: String) = javaClass.getResource("/release/$name")!!.readText()

    private fun jar(name: String, platform: ReleasePlatform, versions: List<String>) = ReleaseFile(
        name, "xbans-2.0.0-$name.jar", platform, ReleaseAssemble.defaultLabel(name), versions,
        platform.modrinthLoaders(folia = true), 21, "ab".repeat(32), 1000,
    )

    private fun manifest(vararg files: ReleaseFile, version: String = "2.0.0") = ReleaseManifest(
        "xbans", version, "xbans-v$version", VersionType.of(version),
        Listing("xBans", null, null, null), "### Fixed\n\n- **Bans** stick.", files.toList(),
    )

    @Test
    fun `reads the version type from the version`() {
        assertEquals(VersionType.RELEASE, VersionType.of("2.0.0"))
        assertEquals(VersionType.RELEASE, VersionType.of("2.0.0+build.5"))
        assertEquals(VersionType.ALPHA, VersionType.of("2.0.0-alpha.1"))
        assertEquals(VersionType.BETA, VersionType.of("2.0.0-beta.2"))
        assertEquals(VersionType.BETA, VersionType.of("2.0.0-rc.1"))
    }

    @Test
    fun `takes one version's changelog section`() {
        val changelog = """
            # Changelog

            ## 2.0.0 (2026-12-01)

            ### Added

            - Everything.

            ## 1.0.1

            - A fix.
        """.trimIndent()
        assertEquals("### Added\n\n- Everything.", Release.changelogSection(changelog, "2.0.0"))
        assertEquals("- A fix.", Release.changelogSection(changelog, "1.0.1"))
        assertNull(Release.changelogSection(changelog, "1.0"))
    }

    @Test
    fun `lists every Minecraft release in a range from Modrinth's versions`() {
        // Recorded from https://api.modrinth.com/v2/tag/game_version (every release, three snapshots).
        val versions = Release.minecraftReleases(resource("modrinth-game-versions.json"), "1.8.8", "26.3")
        assertEquals("1.8.8", versions.first())
        assertEquals("26.3", versions.last())
        assertTrue("1.12.2" in versions && "1.21.11" in versions && "26.1.2" in versions)
        assertTrue(versions.none { "snapshot" in it || "pre" in it })
        assertEquals(listOf("1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"),
            Release.minecraftReleases(resource("modrinth-game-versions.json"), "1.21.11", "26.3"))
        assertThrows<GradleException> { Release.minecraftReleases(resource("modrinth-game-versions.json"), "1.8.9.9", "26.3") }
    }

    @Test
    fun `takes the store listing from the brand catalog`() {
        val listing = Release.listing(resource("plugins-catalog.json"), "xbans")!!
        assertEquals("xBans", listing.name)
        assertTrue(listing.summary!!.isNotBlank())
        assertEquals("https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xbans/icon.svg", listing.icon)
        assertNull(Release.listing(resource("plugins-catalog.json"), "loadersandbox"))
    }

    @Test
    fun `round-trips release json`() {
        val manifest = manifest(jar("bukkit", ReleasePlatform.BUKKIT, listOf("1.8.8", "26.3")))
        val dir = kotlin.io.path.createTempDirectory().toFile()
        dir.resolve(ReleaseManifest.FILE).writeText(manifest.toJson())
        assertEquals(manifest, ReleaseManifest.read(dir))
    }

    @Test
    fun `writes the update post to paste`() {
        val post = Release.updatePost("SpigotMC", manifest(
            jar("bukkit", ReleasePlatform.BUKKIT, listOf("26.3")),
            jar("fabric-1.21.11", ReleasePlatform.FABRIC, listOf("1.21.11")),
        ))
        assertTrue("https://github.com/xena-studios/plugins/releases/download/xbans-v2.0.0/xbans-2.0.0-bukkit.jar" in post)
        assertTrue("fabric" !in post)
        assertTrue("[b]Fixed[/b]" in post && "[*][b]Bans[/b] stick." in post)
    }

    @Test
    fun `gives each Modrinth jar its own version when there are several`() {
        val bukkit = jar("bukkit", ReleasePlatform.BUKKIT, listOf("1.8.8", "26.3"))
        val fabric = jar("fabric-1.21.11", ReleasePlatform.FABRIC, listOf("1.21.11"))
        val pumpkin = jar("pumpkin", ReleasePlatform.PUMPKIN, listOf("26.3"))
        assertEquals("2.0.0", Modrinth.versionNumber(manifest(bukkit, pumpkin), bukkit))
        val both = manifest(bukkit, fabric, version = "2.0.0-beta.1")
        assertEquals("2.0.0-beta.1+fabric-1.21.11", Modrinth.versionNumber(both, fabric))
        val data = JsonSlurper().parseText(Modrinth.data(both, fabric, "AbCd1234")) as Map<*, *>
        assertEquals("beta", data["version_type"])
        assertEquals(listOf("fabric"), data["loaders"])
        assertEquals(listOf("1.21.11"), data["game_versions"])
        assertEquals("xBans 2.0.0-beta.1 (Fabric 1.21.11)", data["name"])
        assertEquals(listOf("bukkit", "spigot", "paper", "purpur", "folia"), bukkit.loaders)
    }

    @Test
    fun `sends Hangar one version with a jar per platform`() {
        val paper = jar("bukkit", ReleasePlatform.BUKKIT, listOf("1.21.11", "26.3"))
        val velocity = jar("velocity", ReleasePlatform.VELOCITY, listOf("3.4"))
        val upload = JsonSlurper().parseText(Hangar.versionUpload(manifest(paper, velocity), listOf(paper, velocity), emptyMap())) as Map<*, *>
        assertEquals("Release", upload["channel"])
        assertEquals(mapOf("PAPER" to listOf("1.21.11", "26.3"), "VELOCITY" to listOf("3.4")), upload["platformDependencies"])
        assertEquals(listOf(mapOf("platforms" to listOf("PAPER")), mapOf("platforms" to listOf("VELOCITY"))), upload["files"])
        val paperOnly = jar("paper", ReleasePlatform.PAPER, listOf("26.3"))
        assertThrows<GradleException> { Hangar.versionUpload(manifest(paper, paperOnly), listOf(paper, paperOnly), emptyMap()) }
    }

    @Test
    fun `narrows Paper's versions to the ones Hangar lists`() {
        // Recorded from https://hangar.papermc.io/api/v1/platforms/PAPER/versions.
        val known = mapOf("PAPER" to Hangar.platformVersions(resource("hangar-paper-versions.json")))
        val range = Release.minecraftReleases(resource("modrinth-game-versions.json"), "1.8.8", "1.9.4")
        val bukkit = jar("bukkit", ReleasePlatform.BUKKIT, range)
        val upload = JsonSlurper().parseText(Hangar.versionUpload(manifest(bukkit), listOf(bukkit), known)) as Map<*, *>
        assertEquals(mapOf("PAPER" to listOf("1.8.8", "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4")), upload["platformDependencies"])
        val ancient = jar("bukkit", ReleasePlatform.BUKKIT, listOf("1.7.10"))
        assertThrows<GradleException> { Hangar.versionUpload(manifest(ancient), listOf(ancient), known) }
    }

    @Test
    fun `never prints a secret in a dry run`() {
        val file = kotlin.io.path.createTempFile(suffix = ".jar").toFile().apply { writeText("jar") }
        val text = Voxel.upload(4, "2.0.0", "xBans 2.0.0", "- A fix.", false, "s3cret", file).describe()
        assertTrue("s3cret" !in text && "api_key: <secret>" in text && "[list]" in text)
    }
}
