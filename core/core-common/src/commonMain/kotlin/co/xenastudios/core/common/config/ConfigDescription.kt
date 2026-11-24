package co.xenastudios.core.common.config

/**
 * A [ConfigSchema] as plain data, for the site's generated config page: the build's manifest
 * task (later) reads this and writes it as JSON, beside [Configs.defaultText] as the page's
 * full default file. Nothing here runs on a server.
 */
public data class ConfigDescription(
    public val version: Int,
    public val header: List<String>,
    public val entries: List<Entry>,
) {
    /** A setting or a section. */
    public sealed interface Entry {
        public val path: String
        public val comment: List<String>
    }

    /** A section, and what's in it. */
    public data class Section(
        override val path: String,
        override val comment: List<String>,
        public val entries: List<Entry>,
    ) : Entry

    /**
     * A setting: its [type] (`integer`, `duration`, `list<string>`, ...), its default as YAML
     * ([default]) and as the file writes it ([defaultText], `"auto"` or `[a, b]`), the values
     * it takes if they're fixed ([choices]), its number limits, and its former defaults.
     */
    public data class Setting(
        override val path: String,
        override val comment: List<String>,
        public val type: String,
        public val default: YamlNode,
        public val defaultText: String,
        public val choices: List<String>,
        public val min: Double?,
        public val max: Double?,
        public val formerDefaults: List<YamlNode>,
    ) : Entry

    internal companion object {
        fun of(schema: ConfigSchema): ConfigDescription = ConfigDescription(schema.version, schema.header, entries(schema))

        private fun entries(group: ConfigGroup): List<Entry> = group.children.map {
            when (it) {
                is ConfigSection -> Section(it.path, it.comment, entries(it))
                is ConfigKey<*> -> setting(it)
            }
        }

        private fun <T : Any> setting(key: ConfigKey<T>): Setting {
            val default = key.type.encode(key.default)
            val type = key.type
            val plain = if (type is ConfigType.ListType<*>) type.element.plain else type.plain
            return Setting(
                key.path, key.comment, type.name, default, ConfigWriter.inline(default, plain), type.choices,
                key.min, key.max, key.formerDefaults.map { type.encode(it) },
            )
        }
    }
}
