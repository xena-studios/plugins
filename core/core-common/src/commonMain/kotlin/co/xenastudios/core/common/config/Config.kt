package co.xenastudios.core.common.config

/**
 * A loaded config: a value for every setting of its [schema], read once when the file loads.
 * Immutable, so it can be shared between threads and swapped whole on reload.
 */
public class Config internal constructor(
    public val schema: ConfigSchema,
    private val values: Map<ConfigKey<*>, Any>,
) {
    /** The value of [key]. */
    public operator fun <T : Any> get(key: ConfigKey<T>): T {
        val value = values[key] ?: throw IllegalArgumentException("$key isn't a setting of ${schema::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    override fun equals(other: Any?): Boolean = other is Config && other.schema === schema && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = values.entries.joinToString(", ", "Config(", ")") { "${it.key.path}=${it.value}" }
}

/**
 * Something wrong with a config file, which loading worked around: the [kind], the setting's
 * [path] (empty for the whole file), the [line] it's on (0 if unknown), the value [found]
 * there, and a [message] in English. The kinds are for messages in other languages later.
 */
public data class ConfigProblem(
    public val kind: Kind,
    public val path: String,
    public val line: Int,
    public val found: String?,
    public val message: String,
) {
    /** The kinds of problem. */
    public enum class Kind {
        /** A value that can't be used: the default is used instead, and the file keeps the value. */
        BAD_VALUE,

        /** `key:` with nothing after it, where that means nothing: the default is used. */
        EMPTY,

        /** A key the schema doesn't have, probably a typo: it's kept in the file and ignored. */
        UNKNOWN_KEY,

        /** `config-version` isn't a whole number: the file is read as the current version. */
        BAD_VERSION,

        /** The file is from a newer version of the plugin: it's read, but never written. */
        NEWER_VERSION,

        /** The old file couldn't be copied aside, so it's carried forward for this run only. */
        BACKUP_FAILED,

        /** The file couldn't be written: the settings are used as read, and the file is unchanged. */
        SAVE_FAILED,
    }

    /** [message], with the [line] when there is one, and the [file]'s name if given. */
    public fun describe(file: String? = null): String = when {
        line > 0 && file != null -> "$message ($file, line $line)"
        line > 0 -> "$message (line $line)"
        else -> message
    }

    override fun toString(): String = describe()
}

/** What loading changed in a file: the [kind], the settings it's about ([paths]), and a [message] in English. */
public data class ConfigNotice(public val kind: Kind, public val message: String, public val paths: List<String> = emptyList()) {
    /** The kinds of notice. */
    public enum class Kind {
        /** There was no file, so the default one was written. */
        WROTE_DEFAULTS,

        /** Settings the file didn't have were added with their defaults. */
        ADDED,

        /** Settings still on a former default were given the current one. */
        REFRESHED,

        /** An older file was carried forward to the current config version. */
        CARRIED_FORWARD,

        /** The old file was copied aside before it was carried forward. */
        BACKED_UP,
    }

    override fun toString(): String = message
}
