package co.xenastudios.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SiteTest {
    private fun jar(name: String, platform: ReleasePlatform, versions: List<String>) = ReleaseFile(
        name, "xbans-2.0.0-$name.jar", platform, ReleaseAssemble.defaultLabel(name), versions,
        platform.modrinthLoaders(folia = true), 21, "ab".repeat(32), 1000,
    )

    private val release = ReleaseManifest(
        "xbans", "2.0.0", "xbans-v2.0.0", VersionType.RELEASE, Listing("xBans", null, null, null), "- New.",
        listOf(
            jar("bukkit", ReleasePlatform.BUKKIT, listOf("1.8.8", "26.3")),
            jar("velocity", ReleasePlatform.VELOCITY, listOf("1.8.8", "26.3")),
            jar("bungeecord", ReleasePlatform.BUNGEECORD, listOf("1.8.8", "26.3")),
            jar("fabric-1.21.11", ReleasePlatform.FABRIC, listOf("1.21.11")),
        ),
    )

    private val manifest = """{"platforms": [
        {"id": "paper", "status": "release"}, {"id": "folia", "status": "release"}, {"id": "spigot", "status": "release"},
        {"id": "fabric", "status": "release"}, {"id": "velocity", "status": "release"}, {"id": "bungeecord", "status": "release"},
        {"id": "neoforge", "status": "planned"}]}"""

    private val links = Site.storeLinks("xbans-v2.0.0", "xbans", "xBans", null, 10714, null, null)

    @Test
    fun `writes the download picker's entry`() {
        val entry = Site.entry(release, folia = true, Site.releasePlatforms(manifest), links, "2026-12-01")
        assertEquals("2.0.0", entry["version"])
        assertEquals("release", entry["channel"])
        val files = entry["files"] as List<*>
        val bukkit = files[0] as Map<*, *>
        assertEquals("https://github.com/xena-studios/plugins/releases/download/xbans-v2.0.0/xbans-2.0.0-bukkit.jar", bukkit["url"])
        assertEquals(listOf("paper", "folia", "spigot"), bukkit["platforms"])
        assertEquals(listOf("velocity"), (files[1] as Map<*, *>)["platforms"])
        assertEquals(listOf("bungeecord"), (files[2] as Map<*, *>)["platforms"])
        val stores = (entry["stores"] as List<*>).associate { (it as Map<*, *>)["store"] to it }
        assertEquals(listOf("modrinth", "hangar", "voxel", "github"), stores.keys.toList())
        assertEquals(listOf("paper", "folia", "spigot", "velocity", "bungeecord"), (stores["hangar"] as Map<*, *>)["platforms"])
        assertEquals(listOf("paper", "folia", "spigot"), (stores["voxel"] as Map<*, *>)["platforms"])
        assertTrue("platforms" !in (stores["modrinth"] as Map<*, *>))
        assertEquals("https://github.com/xena-studios/plugins/releases/tag/xbans-v2.0.0", (stores["github"] as Map<*, *>)["url"])
    }

    @Test
    fun `leaves out a platform the site doesn't release on`() {
        val entry = Site.entry(release, folia = false, setOf("paper", "spigot", "fabric"), links, "2026-12-01")
        val files = entry["files"] as List<*>
        assertEquals(listOf("xbans-2.0.0-bukkit.jar", "xbans-2.0.0-fabric-1.21.11.jar"), files.map { (it as Map<*, *>)["name"] })
        assertEquals(listOf("paper", "spigot"), (files[0] as Map<*, *>)["platforms"])
    }

    @Test
    fun `tells which recorded jars differ from the build`() {
        val entry = Site.entry(release, folia = true, null, links, "2026-12-01")
        val same = JsonOutput.toJson(mapOf("plugin" to "xbans", "releases" to listOf(entry)))
        assertEquals(emptyList<String>(), Site.mismatches(same, entry))
        val other = JsonOutput.toJson(mapOf("plugin" to "xbans", "releases" to listOf(entry + ("version" to "1.9.0"))))
        assertEquals(emptyList<String>(), Site.mismatches(other, entry), "a releases file without this version isn't checked")
        val changed = entry + ("files" to (entry["files"] as List<*>).mapIndexed { i, f ->
            if (i == 0) (f as Map<*, *>) + ("sha256" to "cd".repeat(32)) else f
        }.drop(0).dropLast(1))
        val problems = Site.mismatches(JsonOutput.toJson(mapOf("plugin" to "xbans", "releases" to listOf(changed))), entry)
        assertEquals(2, problems.size)
        assertTrue(problems.any { "bukkit.jar: built abab" in it })
        assertTrue(problems.any { "fabric-1.21.11.jar is in this build, not in the releases file" in it })
    }

    @Test
    fun `tells a release that isn't ready yet`() {
        val versions = """{"plugins": [{"id": "xbans", "newest": 2, "unreleased": {"version": "1.0.1"}}, {"id": "xvanish", "unreleased": {}}, {"id": "zchat", "newest": 4}]}"""
        assertTrue(Site.isUnreleased(versions, "xbans"))
        assertTrue(Site.isUnreleased(versions, "xvanish"), "a new plugin's empty unreleased counts")
        assertTrue(!Site.isUnreleased(versions, "zchat"))
        val changelog = "# xBans\n\n## 2.0.0 (unreleased)\n\nNot released yet.\n\n## 1.0.1\n\n- A fix.\n"
        assertEquals("2.0.0 (unreleased)", Release.changelogHeading(changelog, "2.0.0"))
        assertEquals("1.0.1", Release.changelogHeading(changelog, "1.0.1"))
        assertEquals(null, Release.changelogHeading(changelog, "1.0"))
    }
}
