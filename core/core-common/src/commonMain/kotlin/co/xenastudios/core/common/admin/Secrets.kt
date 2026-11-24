package co.xenastudios.core.common.admin

/**
 * Takes secrets out of text before it leaves the server in a debug report: a config file, a
 * log line. A paste is public to anyone with the link, and owners share them in Discord, so
 * this errs towards removing too much.
 *
 * - The value of any YAML key whose name says it's secret (`password`, `token`, `secret`,
 *   `api-key`, `webhook`, ...), whatever its section: `password: hunter2` becomes
 *   `password: <removed>`.
 * - The user and password in a URL: `mysql://user:pass@host` becomes `mysql://<removed>@host`.
 * - `password=...` and `token=...` in a query string or a JDBC property list.
 */
public object Secrets {
    /** What a secret is replaced with. */
    public const val REMOVED: String = "<removed>"

    // A key that names a secret, alone or as a word of a longer key: mysql.password, bot-token,
    // api_key, webhook-url. Not "key" alone, which is every map's word for its keys.
    private val SECRET_KEY = Regex(
        "(^|[-_.])(password|passwd|pass|pwd|secret|token|api[-_]?key|private[-_]?key|access[-_]?key|credentials?|auth|webhook)([-_.]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val YAML_ENTRY = Regex("^(\\s*(?:-\\s+)?)([\"']?)([^\"':#\\s][^\"':#]*?)\\2(\\s*):(\\s+)(.*)$")
    private val URL_CREDENTIALS = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s@:]+(?::[^/\\s@]*)?@")
    private val QUERY_SECRET = Regex("([?&;]|^|\\s)(password|pass|pwd|token|secret|api[-_]?key)=([^&;\\s]+)", RegexOption.IGNORE_CASE)

    /** Whether a key called [name] holds a secret. */
    public fun isSecretKey(name: String): Boolean = SECRET_KEY.containsMatchIn(name.trim())

    /** [text] with every secret it holds replaced by [REMOVED]. */
    public fun redact(text: String): String = text.lineSequence().joinToString("\n") { redactLine(it) }

    private fun redactLine(line: String): String {
        val entry = YAML_ENTRY.find(line)
        val redacted = if (entry != null && entry.groupValues[6].isNotBlank() && isSecretKey(entry.groupValues[3])) {
            val (indent, quote, key, space, gap) = entry.destructured
            "$indent$quote$key$quote$space:$gap$REMOVED"
        } else {
            line
        }
        return QUERY_SECRET.replace(URL_CREDENTIALS.replace(redacted) { it.groupValues[1] + REMOVED + "@" }) {
            it.groupValues[1] + it.groupValues[2] + "=" + REMOVED
        }
    }
}
