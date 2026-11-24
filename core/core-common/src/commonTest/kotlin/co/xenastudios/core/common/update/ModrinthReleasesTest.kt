package co.xenastudios.core.common.update

import co.xenastudios.core.common.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading Modrinth's and GitHub's answers, with the HTTP given by the test. */
class ModrinthReleasesTest {
    private val asked = mutableListOf<Pair<String, Map<String, String>>>()
    private val answers = ArrayDeque<Outcome<HttpAnswer, String>>()
    private val source = ModrinthReleases("xtest", "bukkit", "1.21.11", "2.0.0", { url, headers ->
        asked += url to headers
        answers.removeFirst()
    }, modrinth = "http://modrinth.test", gitHub = "http://github.test/releases")

    @Test
    fun asksForTheLoaderAndMinecraftVersionAndReadsTheVersions() {
        answers += Outcome.Ok(HttpAnswer(200, MODRINTH))
        val releases = (source.releases() as Outcome.Ok).value
        assertEquals(
            listOf(
                Release("3.0.0-beta.1", UpdateChannel.BETA, "https://modrinth.com/project/xtest/version/b2"),
                Release("2.1.0", UpdateChannel.RELEASE, "https://modrinth.com/project/xtest/version/a1"),
            ),
            releases,
        )
        val (url, headers) = asked.single()
        assertEquals(
            "http://modrinth.test/v2/project/xtest/version?loaders=%5B%22bukkit%22%5D&game_versions=%5B%221.21.11%22%5D&include_changelog=false",
            url,
        )
        assertEquals("xena-studios/xtest/2.0.0 (contact@xenastudios.co)", headers["User-Agent"])
    }

    @Test
    fun aProjectInReviewIsReadFromGitHub() {
        answers += Outcome.Ok(HttpAnswer(404, """{"error":"not_found"}"""))
        answers += Outcome.Ok(HttpAnswer(200, GITHUB))
        val releases = (source.releases() as Outcome.Ok).value
        assertEquals(
            listOf(
                Release("3.0.0-alpha.2", UpdateChannel.ALPHA, "https://github.com/xena-studios/plugins/releases/tag/xtest-v3.0.0-alpha.2"),
                Release("2.2.0-rc.1", UpdateChannel.BETA, "https://github.com/xena-studios/plugins/releases/tag/xtest-v2.2.0-rc.1"),
                Release("2.1.0", UpdateChannel.RELEASE, "https://github.com/xena-studios/plugins/releases/tag/xtest-v2.1.0"),
            ),
            releases,
        )
        assertEquals("http://github.test/releases", asked[1].first)
    }

    @Test
    fun failuresAreSentencesNotThrows() {
        answers += Outcome.Err("connection refused")
        assertEquals(Outcome.Err("Modrinth can't be reached: connection refused"), source.releases())
        answers += Outcome.Ok(HttpAnswer(503, "busy"))
        assertEquals(Outcome.Err("Modrinth answered HTTP 503"), source.releases())
        answers += Outcome.Ok(HttpAnswer(200, "<html>"))
        assertTrue(source.releases().isErr)
        answers += Outcome.Ok(HttpAnswer(404, ""))
        answers += Outcome.Ok(HttpAnswer(403, "rate limited"))
        assertEquals(Outcome.Err("xtest isn't on Modrinth yet, and GitHub answered HTTP 403"), source.releases())
    }

    @Test
    fun readsJson() {
        assertEquals(
            mapOf("a" to listOf(1.0, -2.5, true, false, null), "b" to "x\"\né/"),
            JsonReader.read("""{ "a": [1, -2.5e0, true, false, null], "b": "x\"\né\/" }"""),
        )
        assertNull(JsonReader.read("[1, 2"))
        assertNull(JsonReader.read("[1] 2"))
        assertNull(JsonReader.read("[".repeat(10_000)))
    }

    private companion object {
        const val MODRINTH = """[
          {"id":"b2","project_id":"P","version_number":"3.0.0-beta.1","version_type":"beta","loaders":["bukkit","paper"],"files":[]},
          {"id":"a1","project_id":"P","version_number":"2.1.0","version_type":"release","loaders":["bukkit"],"files":[]},
          {"id":"c3","project_id":"P","version_number":"9.9.9","version_type":"snapshot"}
        ]"""
        const val GITHUB = """[
          {"tag_name":"xtest-v3.0.0-alpha.2","draft":false,"prerelease":true},
          {"tag_name":"xtest-v2.2.0-rc.1","draft":false,"prerelease":true},
          {"tag_name":"xtest-v9.0.0","draft":true,"prerelease":false},
          {"tag_name":"xother-v5.0.0","draft":false,"prerelease":false},
          {"tag_name":"xtest-v2.1.0","draft":false,"prerelease":false}
        ]"""
    }
}
