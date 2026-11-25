package co.xenastudios.core.jvm.config

import co.xenastudios.core.common.platform.Log
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The messages an owner edited in an old `config.yml`, carried into `lang/en.yml` as its starting
 * point when a plugin's rewrite moves its messages out of the config (xUtilities 3.0, zChat 4.0):
 * written before the language files are loaded, which then fill in the rest around them. The
 * plugin works out which messages were edited (its own table of old paths and shipped texts);
 * this writes them.
 */
public object CarriedMessages {
    /**
     * Writes [edited] (4.0's keys to the owner's text) to `lang/en.yml` in [folder], unless that
     * file is already there. [from] names where they came from (`zChat 3.x`) and [backup] the copy
     * of the old config they're still in. Returns what went wrong, in English, or `null`.
     */
    public fun write(folder: Path, edited: Map<String, String>, from: String, backup: String, log: Log): String? {
        if (edited.isEmpty()) return null
        val english = folder.resolve("lang").resolve("en.yml")
        if (Files.exists(english)) {
            return "config.yml is from $from and has messages you edited, but lang/en.yml is already there, " +
                "so they aren't copied into it. They're in $backup."
        }
        val text = buildString {
            append("# Your messages from ").append(from).append("'s config.yml.\n")
            for ((key, value) in edited) append(key).append(": ").append(quote(value)).append('\n')
        }
        return try {
            Files.createDirectories(english.parent)
            Files.write(english, text.toByteArray(StandardCharsets.UTF_8))
            log.info("Copied ${edited.size} message${if (edited.size == 1) "" else "s"} you edited in config.yml to lang/en.yml.")
            null
        } catch (e: Exception) {
            "Couldn't write lang/en.yml (${e.message}), so your edited messages are only in $backup."
        }
    }

    /** [text] as a YAML double-quoted string. */
    public fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
