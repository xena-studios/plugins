package co.xenastudios.xbans

import co.xenastudios.xbans.model.Length

/**
 * A punishment command's arguments, read the LiteBans way, as 1.x read them:
 * `/ban <player> [flags] [duration] [server:<name>] [template] [reason]`. Flags and `server:`
 * may stand anywhere after the player; everything after `--` is reason, even a word that looks
 * like a flag.
 *
 * - `-s` silent: only staff are told. `-S` extra silent: only the console.
 * - `-p` public, when punishments are silent by default.
 * - `-g` global: `server:*`. `-I` by IP too, as `/ipban`.
 * - `server:<name>`: one server, `server:*`, `global` or `all` for every server, `local` or
 *   `this` for this one.
 */
public class Args(
    public val target: String,
    public val silent: Boolean,
    public val extraSilent: Boolean,
    public val publicFlag: Boolean,
    public val ip: Boolean,
    /** `null` when none was given. */
    public val scope: String?,
    public val length: Length?,
    public val template: String?,
    public val reason: String?,
    /** A word meant as a length that isn't one (`10x`, or past 100 years), or `null`. */
    public val badLength: String?,
) {
    public companion object {
        private val SPACED_AMOUNT = Regex("\\d+(\\.\\d+)?")
        private val LENGTHISH = Regex("\\d[\\d.smhdwoy]*[smhdwoy][\\d.smhdwoy]*")

        /**
         * Reads [words] (the player, then the rest). [wantsLength]: whether a length may come
         * first (bans, mutes, warnings; not kicks or unbans); [isTemplate]: whether a word names a
         * template this command can use. `null` when there's no player.
         */
        public fun parse(words: List<String>, wantsLength: Boolean, isTemplate: (String) -> Boolean): Args? {
            if (words.isEmpty() || words[0].isBlank()) return null
            var silent = false
            var extraSilent = false
            var publicFlag = false
            var ip = false
            var scope: String? = null
            val rest = ArrayList<String>()
            var literal = false
            for (word in words.drop(1)) {
                if (word.isEmpty()) continue
                if (literal) {
                    rest += word
                    continue
                }
                when (word) {
                    "--" -> literal = true
                    "-s" -> silent = true
                    "-S" -> extraSilent = true
                    "-p" -> publicFlag = true
                    "-g" -> scope = "*"
                    "-I" -> ip = true
                    else -> if (word.lowercase().startsWith("server:") && word.length > "server:".length) scope = word.substring("server:".length) else rest += word
                }
            }
            var length: Length? = null
            var badLength: String? = null
            var next = 0
            if (wantsLength && rest.isNotEmpty()) {
                val first = rest[0]
                // "5 days" or "1.5 hours", typed with a space.
                val read = if (rest.size > 1 && SPACED_AMOUNT.matches(first) && Length.isUnit(rest[1])) {
                    Length.read(first + rest[1]).also { if (it.length != null) next = 2 }
                } else {
                    Length.read(first).also { if (it.length != null) next = 1 }
                }
                length = read.length
                if (read.tooLong) {
                    badLength = first
                    next = 1
                } else if (length == null && looksLikeLength(first)) {
                    // "10x" or "3dd": meant as a length, so say so rather than make it the reason.
                    badLength = first
                    next = 1
                }
            }
            var template: String? = null
            if (next < rest.size && isTemplate(rest[next].lowercase())) {
                template = rest[next].lowercase()
                next++
            }
            val reason = if (next < rest.size) rest.subList(next, rest.size).joinToString(" ") else null
            return Args(words[0], silent, extraSilent, publicFlag, ip, scope, length, template, reason, badLength)
        }

        /** The words of a command line after its name: split on spaces, as Bukkit hands them over. */
        public fun words(text: String?): List<String> = text?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

        // A short number and unit letters, such as "3dd" or "1h30": a length typed wrong, not the start of a reason.
        private fun looksLikeLength(word: String): Boolean = word.length <= 8 && LENGTHISH.matches(word.lowercase())
    }
}
