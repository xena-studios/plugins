package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.Theme
import co.xenastudios.zchat.config.ZChatConfig

/**
 * The chat formats, compiled once per config load (and theme): which a player gets, and drawing
 * a line with it. `%placeholder%`s become slots of their own at load, filled in after parsing, so
 * a placeholder that shows player-chosen text (a nickname) can't inject a click or a hover.
 */
public class Formats private constructor(
    /** Whether zChat draws chat at all (`formatting.enabled`). */
    public val enabled: Boolean,
    private val colorPermission: String,
    private val default: Format,
    private val groups: List<Format>,
    /** How a mention looks. */
    public val mention: Markup,
    public val sent: Format,
    public val received: Format,
    public val spy: Format,
) {
    /** One format: its group's [name] and [permission], the [markup], and the placeholders its slots stand for. */
    public class Format internal constructor(
        public val name: String,
        public val permission: String,
        public val markup: Markup,
        /** The `%placeholder%` identifiers, by slot: `<papi-0>` is the first. */
        internal val placeholders: List<String>,
    )

    /** Whether a format uses `%placeholders%`, to warn when no placeholder plugin is there. */
    public val usesPlaceholders: Boolean = (groups + default + sent + received + spy).any { it.placeholders.isNotEmpty() }

    /** Whether [player] may style their own messages. */
    public fun mayStyle(player: PlayerRef, server: ChatServer): Boolean = colorPermission.isNotEmpty() && server.has(player, colorPermission)

    /** The format [player] gets: their group's with the highest weight, or the default. */
    public fun of(player: PlayerRef, server: ChatServer): Format {
        for (group in groups) if (group.permission.isEmpty() || server.has(player, group.permission)) return group
        return default
    }

    /**
     * [format] drawn for one sender: [values] are the format's own tags (`player`, `message`...),
     * [added] other plugins', which come first so a name zChat uses stays zChat's. The
     * `%placeholders%` are asked of [server] for [player].
     */
    public fun render(
        format: Format,
        player: PlayerRef?,
        server: ChatServer,
        values: Map<String, Placeholder>,
        added: Map<String, String> = emptyMap(),
    ): Component {
        if (added.isEmpty() && format.placeholders.isEmpty()) return format.markup.render(values)
        val all = HashMap<String, Placeholder>(values.size + added.size + format.placeholders.size + 4)
        for ((name, value) in added) all[name] = Placeholder.component(ChatText.styles(value))
        if (player != null) {
            format.placeholders.forEachIndexed { i, identifier ->
                all["papi-$i"] = Placeholder.component(placeholder(server, player, identifier))
            }
        }
        all.putAll(values)
        return format.markup.render(all)
    }

    public companion object {
        /** A `%placeholder%` as a format writes it: `%vault_prefix%`. */
        private val PERCENT = Regex("%([^%\\s<>']+)%")

        /** The tags zChat's formats fill in, which other plugins' placeholders can't take. */
        public val OWN: Set<String> = setOf("player", "displayname", "message", "server", "name", "sender", "receiver")

        /** Compiles the formats of [config] with [theme]'s tags. Problems go to [warnings]. */
        public fun compile(config: Config, theme: Theme, warnings: MutableList<String>): Formats {
            val f = ZChatConfig.formatting
            val default = format("default", "", config[f.defaultFormat], theme, "formatting.default-format", warnings)
            val groups = config[f.groups].entries.sortedByDescending { it.value.weight }.map { (name, group) ->
                val text = group.format.ifEmpty { config[f.defaultFormat] }
                format(name, group.permission(name), text, theme, "formatting.groups.$name.format", warnings)
            }
            val pm = ZChatConfig.privateMessages
            return Formats(
                config[f.enabled],
                config[f.colorPermission],
                default,
                groups,
                Markup.parse(config[ZChatConfig.mentions.format], theme.tags),
                format("sent", "", config[pm.msg.formatSent], theme, "private-messages.msg.format-sent", warnings),
                format("received", "", config[pm.msg.formatReceived], theme, "private-messages.msg.format-received", warnings),
                format("spy", "", config[pm.socialspy.format], theme, "private-messages.socialspy.format", warnings),
            )
        }

        private fun format(name: String, permission: String, text: String, theme: Theme, path: String, warnings: MutableList<String>): Format {
            val identifiers = ArrayList<String>()
            val template = PERCENT.replace(text) { match ->
                val identifier = match.groupValues[1]
                var slot = identifiers.indexOf(identifier)
                if (slot < 0) {
                    slot = identifiers.size
                    identifiers += identifier
                }
                "<papi-$slot>"
            }
            if ("<message>" !in template && "<message/>" !in template) warnings += "config.yml: $path has no <message>, so the message itself won't show."
            return Format(name, permission, Markup.parse(template, theme.tags), identifiers)
        }

        // A broken placeholder must never break chat: it shows nothing.
        private fun placeholder(server: ChatServer, player: PlayerRef, identifier: String): Component {
            val value = try {
                server.placeholder(player, identifier)
            } catch (e: Exception) {
                null
            } ?: return Component.EMPTY
            return ChatText.legacy(value)
        }
    }
}
