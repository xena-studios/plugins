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
            val versions = JsonReader.read(body) as? List<*> ?: return Outcome.Err("Modrinth's answer isn't a list of versions")
            return Outcome.Ok(
                versions.mapNotNull { version ->
                    val fields = version as? Map<*, *> ?: return@mapNotNull null
                    val number = fields["version_number"] as? String ?: return@mapNotNull null
                    val channel = (fields["version_type"] as? String)?.let(UpdateChannel::ofModrinth) ?: return@mapNotNull null
                    val id = fields["id"] as? String ?: number
                    Release(number, channel, "https://modrinth.com/project/${encode(project)}/version/${encode(id)}")
                },
            )
        }

        /** GitHub's releases tagged `<project>-v<version>`, drafts left out. */
        internal fun parseGitHub(body: String, project: String): Outcome<List<Release>, String> {
            val releases = JsonReader.read(body) as? List<*> ?: return Outcome.Err("GitHub's answer isn't a list of releases")
            val prefix = "$project-v"
            return Outcome.Ok(
                releases.mapNotNull { release ->
                    val fields = release as? Map<*, *> ?: return@mapNotNull null
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
 * A small JSON reader for the release lists: objects as maps, arrays as lists, strings,
 * numbers as doubles, booleans and `null`. `null` for text that isn't JSON. Nested at most 64
 * deep, so an answer can't overflow the stack.
 */
internal object JsonReader {
    fun read(text: String): Any? = try {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.blank()
        if (parser.at < text.length) null else value
    } catch (e: IllegalArgumentException) {
        null
    }

    private class Parser(val text: String) {
        var at = 0

        fun value(depth: Int): Any? {
            require(depth < 64) { "too deep" }
            blank()
            require(at < text.length) { "end of text" }
            return when (text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            at++
            val map = LinkedHashMap<String, Any?>()
            blank()
            if (peek() == '}') { at++; return map }
            while (true) {
                blank()
                require(peek() == '"') { "a key" }
                val key = string()
                blank()
                require(peek() == ':') { "a colon" }
                at++
                map[key] = value(depth + 1)
                blank()
                when (peek()) {
                    ',' -> at++
                    '}' -> { at++; return map }
                    else -> throw IllegalArgumentException("a comma or }")
                }
            }
        }

        fun array(depth: Int): List<Any?> {
            at++
            val list = ArrayList<Any?>()
            blank()
            if (peek() == ']') { at++; return list }
            while (true) {
                list += value(depth + 1)
                blank()
                when (peek()) {
                    ',' -> at++
                    ']' -> { at++; return list }
                    else -> throw IllegalArgumentException("a comma or ]")
                }
            }
        }

        fun string(): String {
            at++
            val out = StringBuilder()
            while (true) {
                require(at < text.length) { "an unfinished string" }
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(at < text.length) { "an unfinished escape" }
                        when (val e = text[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                require(at + 4 <= text.length) { "a short \\u escape" }
                                out.append(text.substring(at, at + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("a bad \\u escape"))
                                at += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        fun word(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { word }
            at += word.length
            return value
        }

        fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDoubleOrNull() ?: throw IllegalArgumentException("a number")
        }

        fun peek(): Char? = text.getOrNull(at)

        fun blank() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }
}
