package co.xenastudios.core.common.update

import co.xenastudios.core.common.Outcome

/**
 * A GET over HTTP, given by the platform: on the JVM, `JvmHttp` in core-jvm. Blocking, so it's
 * only called off the server's threads. A failure to connect or read is an [Outcome.Err] with
 * the reason in English; any HTTP status is an [Outcome.Ok].
 */
public fun interface HttpGet {
    public fun get(url: String, headers: Map<String, String>): Outcome<HttpAnswer, String>
}

/** What a server answered: its [status] and its [body] as text. */
public class HttpAnswer(public val status: Int, public val body: String)

/**
 * A plugin's releases on Modrinth, for one loader and Minecraft version: `GET
 * /v2/project/<project>/version?loaders=["<loader>"]&game_versions=["<minecraft>"]`, as the
 * release workflow lists each jar (one version per jar, with its loaders and Minecraft
 * versions). Sends the plugin's version in the User-Agent, and the loader and Minecraft
 * version in the query, nothing else.
 *
 * A project Modrinth is still reviewing answers 404 to everyone else, so then it reads the
 * plugin's GitHub releases instead (tags `<project>-v<version>`), as today's plugins do: a
 * GitHub pre-release is a beta, or an alpha when its version says so.
 */
public class ModrinthReleases(
    /** The Modrinth project's slug or id: `xutilities`. */
    private val project: String,
    /** Modrinth's loader for the jar running: `bukkit`, `fabric`. */
    private val loader: String,
    /** The Minecraft version the server runs: `1.21.11`. */
    private val minecraft: String,
    /** The plugin's version, for the User-Agent. */
    private val current: String,
    private val http: HttpGet,
    private val modrinth: String = MODRINTH,
    private val gitHub: String = GITHUB_RELEASES,
) : ReleaseSource {

    private val headers = mapOf("User-Agent" to "xena-studios/$project/$current (contact@xenastudios.co)")

    override fun releases(): Outcome<List<Release>, String> {
        val query = "?loaders=" + encode("[\"$loader\"]") + "&game_versions=" + encode("[\"$minecraft\"]") + "&include_changelog=false"
        val answer = when (val got = http.get("${modrinth.trimEnd('/')}/v2/project/${encode(project)}/version$query", headers)) {
            is Outcome.Err -> return Outcome.Err("Modrinth can't be reached: ${got.error}")
            is Outcome.Ok -> got.value
        }
        return when (answer.status) {
            200 -> parseModrinth(answer.body, project)
            404 -> fromGitHub()
            else -> Outcome.Err("Modrinth answered HTTP ${answer.status}")
        }
    }

    private fun fromGitHub(): Outcome<List<Release>, String> {
        val answer = when (val got = http.get(gitHub, headers + ("Accept" to "application/vnd.github+json"))) {
            is Outcome.Err -> return Outcome.Err("$project isn't on Modrinth yet, and GitHub can't be reached: ${got.error}")
            is Outcome.Ok -> got.value
        }
        if (answer.status != 200) return Outcome.Err("$project isn't on Modrinth yet, and GitHub answered HTTP ${answer.status}")
        return parseGitHub(answer.body, project)
    }

    public companion object {
        /** Modrinth's API. */
        public const val MODRINTH: String = "https://api.modrinth.com"

        // The newest 100: years of releases for every plugin together.
        internal const val GITHUB_RELEASES = "https://api.github.com/repos/xena-studios/plugins/releases?per_page=100"

        /** Modrinth's list of versions as [Release]s; versions of a type it doesn't know are left out. */
        internal fun parseModrinth(body: String, project: String): Outcome<List<Release>, String> {
            val versions = JsonReader.objects(body) ?: return Outcome.Err("Modrinth's answer isn't a list of versions")
            return Outcome.Ok(
                versions.mapNotNull { fields ->
                    val number = fields["version_number"] as? String ?: return@mapNotNull null
                    val channel = (fields["version_type"] as? String)?.let(UpdateChannel::ofModrinth) ?: return@mapNotNull null
                    val id = fields["id"] as? String ?: number
                    Release(number, channel, "https://modrinth.com/project/${encode(project)}/version/${encode(id)}")
                },
            )
        }

        /** GitHub's releases tagged `<project>-v<version>`, drafts left out. */
        internal fun parseGitHub(body: String, project: String): Outcome<List<Release>, String> {
            val releases = JsonReader.objects(body) ?: return Outcome.Err("GitHub's answer isn't a list of releases")
            val prefix = "$project-v"
            return Outcome.Ok(
                releases.mapNotNull { fields ->
                    val tag = fields["tag_name"] as? String ?: return@mapNotNull null
                    if (fields["draft"] == true || !tag.startsWith(prefix)) return@mapNotNull null
                    val number = tag.removePrefix(prefix)
                    if (number.firstOrNull()?.isDigit() != true) return@mapNotNull null
                    val channel = when {
                        fields["prerelease"] != true -> UpdateChannel.RELEASE
                        else -> UpdateChannel.of(number).takeIf { it != UpdateChannel.RELEASE } ?: UpdateChannel.BETA
                    }
                    Release(number, channel, "https://github.com/xena-studios/plugins/releases/tag/${encode(tag)}")
                },
            )
        }

        /** Percent-encodes [value] for a URL's query or path, keeping only unreserved characters. */
        internal fun encode(value: String): String {
            val out = StringBuilder()
            for (byte in value.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                val char = c.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_' || char == '.' || char == '~') {
                    out.append(char)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                }
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * The release lists' JSON, read only as far as the checker needs: the objects directly inside
 * the top-level array, each as its top-level text and boolean fields. Nested objects and arrays
 * (a version's files) are skipped, numbers too. `null` for text that isn't an array of objects
 * closed as it should be. No recursion, so no answer can overflow the stack.
 */
internal object JsonReader {
    fun objects(text: String): List<Map<String, Any>>? {
        val out = ArrayList<Map<String, Any>>()
        var at = skip(text, 0)
        if (text.getOrNull(at) != '[') return null
        at++
        var depth = 1
        var current: HashMap<String, Any>? = null
        var key: String? = null
        while (at < text.length) {
            val c = text[at]
            when {
                c == '"' -> {
                    val end = end(text, at) ?: return null
                    if (depth == 2) {
                        val next = skip(text, end)
                        if (key == null && text.getOrNull(next) == ':') {
                            key = unescape(text, at + 1, end - 1)
                            at = next + 1
                            continue
                        }
                        key?.let { current?.put(it, unescape(text, at + 1, end - 1)) }
                    }
                    key = null
                    at = end
                    continue
                }
                c == '{' || c == '[' -> {
                    depth++
                    if (depth == 2) {
                        if (c != '{') return null
                        current = HashMap()
                    }
                    key = null
                }
                c == '}' || c == ']' -> {
                    depth--
                    if (depth == 1) current?.let { out += it }.also { current = null }
                    if (depth == 0) return if (skip(text, at + 1) == text.length) out else null
                    key = null
                }
                depth == 2 && key != null && text.startsWith("true", at) -> { current?.put(key, true); key = null; at += 4; continue }
                depth == 2 && key != null && text.startsWith("false", at) -> { current?.put(key, false); key = null; at += 5; continue }
                c == ',' -> key = null
            }
            at++
        }
        return null
    }

    private fun skip(text: String, from: Int): Int {
        var at = from
        while (at < text.length && text[at].isWhitespace()) at++
        return at
    }

    // Just past the string that starts at [start], or null if it never ends.
    private fun end(text: String, start: Int): Int? {
        var at = start + 1
        while (at < text.length) {
            when (text[at]) {
                '\\' -> at += 2
                '"' -> return at + 1
                else -> at++
            }
        }
        return null
    }

    private fun unescape(text: String, from: Int, to: Int): String {
        if (text.indexOf('\\', from).let { it < 0 || it >= to }) return text.substring(from, to)
        val out = StringBuilder()
        var at = from
        while (at < to) {
            val c = text[at++]
            if (c != '\\' || at >= to) {
                out.append(c)
                continue
            }
            when (val e = text[at++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    out.append(text.substring(at, minOf(at + 4, to)).toIntOrNull(16)?.toChar() ?: '?')
                    at += 4
                }
                else -> out.append(e)
            }
        }
        return out.toString()
    }
}
