package co.xenastudios.zchat.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.update.UpdatesSection

/**
 * zChat's `config.yml`, version 3 (zChat 4.0). Every setting 3.x had keeps its path, so a 3.x
 * config carries forward with nothing but its messages moved (to `lang/`), its theme moved into
 * `messages.theme` and `update-checker` into `updates`; the 3.2 jar put back reads the rest as
 * it was. The chat formats stay here: they're the server's look, the same for every reader.
 */
public object ZChatConfig : ConfigSchema(
    3,
    "zChat: format your chat. Keep it clean.",
    "Docs: https://plugins.xenastudios.co/zchat/configuration/",
    "",
    "Apply changes with /zchat reload (or /zc reload). Everything applies, even turning",
    "commands on or off. A bad value falls back to its default with a warning in the",
    "console; zChat never stops over config. Formats are in MiniMessage's tags:",
    "https://plugins.xenastudios.co/start/formatting/. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val formatting: Formatting = Formatting(this)
    public val filter: Filter = Filter(this)
    public val cooldown: Cooldown = Cooldown(this)
    public val antiRepeat: AntiRepeat = AntiRepeat(this)
    public val mentions: Mentions = Mentions(this)

    public val clearchat: ClearChat = ClearChat(this)
    public val mutechat: CommandSettings = CommandSettings(
        this, "mutechat", listOf("mc"),
        "/mutechat: mutes chat for everyone but zchat.bypass.mute until it's run again.",
    )
    public val togglechat: ToggleChat = ToggleChat(this)
    public val privateMessages: PrivateMessages = PrivateMessages(this)

    /** The update checker: `updates.check` was 3.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // zChat 2's example patterns were placeholders; zChat 3 put examples in words.
        migrate(1) {
            val patterns = this["filter.patterns"]
            if (patterns is co.xenastudios.core.common.config.YamlNode.Sequence &&
                patterns.items.map { (it as? co.xenastudios.core.common.config.YamlNode.Scalar)?.content } == listOf("badword1", "badword2")
            ) {
                this["filter.patterns"] = co.xenastudios.core.common.config.YamlNode.Sequence(emptyList())
            }
        }
        // 4.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(2) { Legacy.toThree(this) }
    }

    /** `formatting:` how chat looks. */
    public class Formatting(parent: ConfigGroup) : ConfigSection(
        parent, "formatting",
        "How chat looks. Formats can use <player> (the name), <displayname> (the name with",
        "any nickname colours), <message>, the theme's tags (<prefix>, <highlight>...), and",
        "PlaceholderAPI placeholders such as %vault_prefix% when PlaceholderAPI is installed.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true, "false leaves chat as the server shows it.")
        public val colorPermission: ConfigKey<String> = key(
            "color-permission", ConfigTypes.PERMISSION, "zchat.chat.color",
            "Players with this permission can use colour and style tags, such as <red> or",
            "<bold>, in their own messages. Never clicks or hovers. Blank lets nobody.",
        )
        public val defaultFormat: ConfigKey<String> = string(
            "default-format", "<gray><player>:</gray> <white><message></white>",
            "The format for players in no group below.",
        )
        public val groups: ConfigKey<Map<String, Group>> = key(
            "groups", Group.TYPE, Group.DEFAULTS,
            "Each group's format goes to players with its permission (zchat.group.<name> if left",
            "out). A player in several groups gets the one with the highest weight. Group",
            "permissions are given out on purpose: operators don't get them by being operators.",
        )
    }

    /** `filter:` the words chat and private messages are checked for. */
    public class Filter(parent: ConfigGroup) : ConfigSection(
        parent, "filter",
        "Catches words you don't want in chat and private messages. Players with",
        "zchat.bypass.filter skip it.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val mode: ConfigKey<FilterMode> = enum(
            "mode", FilterMode.CENSOR, FilterMode.entries,
            "censor hides what matched and lets the message through; block stops the message.",
        )
        public val censorChar: ConfigKey<String> = key("censor-char", ConfigTypes.ONE_CHARACTER, "*")
        public val smart: ConfigKey<Boolean> = boolean(
            "smart", true,
            "Also catch words dressed up to get past the filter: s p a c e d out, b4dw0rd,",
            "baaadword, bädword. false matches words only as typed.",
        )
        public val words: ConfigKey<List<String>> = stringList(
            "words", listOf("badword1", "badword2"),
            "Words to catch, matched as whole words. Put * at either end to also catch longer",
            "words: \"bad*\" catches badly and bads, where \"bad\" alone misses plurals.",
            "/filter add and /filter remove change this list in game.",
        )
        public val patterns: ConfigKey<List<String>> = stringList(
            "patterns", emptyList(),
            "Regular expressions, matched anywhere in the message as typed, ignoring case.",
            "For anything words can't express. Not smart.",
        )
        public val command: CommandSettings = CommandSettings(
            this, "command", listOf("chatfilter"),
            "/filter: add, remove and list words, and test a message, in game.",
            name = "filter", permission = "zchat.command.filter",
        )
    }

    /** `cooldown:` the shortest gap between one player's messages. */
    public class Cooldown(parent: ConfigGroup) : ConfigSection(
        parent, "cooldown", "The shortest gap between one player's messages. zchat.bypass.cooldown skips it.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val seconds: ConfigKey<Double> = double("seconds", 3.0, min = 0.0, max = 3600.0)
    }

    /** `anti-repeat:` the same message again, too soon. */
    public class AntiRepeat(parent: ConfigGroup) : ConfigSection(
        parent, "anti-repeat",
        "Stops a player sending the same message again soon after. Messages under three",
        "letters, like \"gg\", are let through. zchat.bypass.anti-repeat skips it.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val seconds: ConfigKey<Int> = int("seconds", 30, "How long a message counts as recent.", min = 1, max = 3600)
        public val similarity: ConfigKey<Int> = int(
            "similarity", 90,
            "How alike two messages must be, in percent, to count as a repeat. 100 catches",
            "only exact repeats; lower also catches small changes.",
            min = 50, max = 100,
        )
    }

    /** `mentions:` a name in chat, highlighted and pinged. */
    public class Mentions(parent: ConfigGroup) : ConfigSection(
        parent, "mentions",
        "Writing a player's name, or @name, highlights it and pings them. Players who've",
        "hidden chat aren't pinged.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val format: ConfigKey<String> = string(
            "format", "<highlight>@<name></highlight>",
            "How a mention looks, to everyone. <name> is the mentioned player's name.",
        )
        public val sound: ConfigKey<Sound> = key("sound", Sound.TYPE, Sound.parse(ConfigTypes.PING), "Played to the mentioned player. Blank for none.")
        public val everyonePermission: ConfigKey<String> = key(
            "everyone-permission", ConfigTypes.PERMISSION, "zchat.mention.everyone",
            "Players with this permission can write @everyone to mention everyone. Blank lets",
            "nobody.",
        )
    }

    /** `clearchat:` */
    public class ClearChat(parent: ConfigGroup) : CommandSettings(
        parent, "clearchat", listOf("cc"),
        "Every command has the same settings:",
        "  enabled     false removes the command entirely",
        "  aliases     other names for it",
        "  permission  needed to use it; blank (\"\") lets anyone",
        "The messages are in lang/<language>.yml.",
        "",
        "/clearchat: clears chat for everyone. zchat.bypass.clearchat keeps a player's.",
    ) {
        public val lines: ConfigKey<Int> = int("lines", 100, "How many blank lines push chat out of view.", min = 1, max = 500)
    }

    /** `togglechat:` */
    public class ToggleChat(parent: ConfigGroup) : CommandSettings(
        parent, "togglechat", listOf("tc"), "/togglechat: hides other players' chat for yourself, or shows it again.",
    ) {
        public val remember: ConfigKey<Boolean> = boolean("remember", true, "Keep a player's choice when they rejoin.")
    }

    /** `private-messages:` /msg, /reply and /socialspy. */
    public class PrivateMessages(parent: ConfigGroup) : ConfigSection(
        parent, "private-messages", "Private messages. The filter applies to them; the mute and cooldown don't.",
    ) {
        public val msg: Msg = Msg(this)
        public val reply: CommandSettings = CommandSettings(
            this, "reply", listOf("r"), "/reply <message>: answers whoever you last messaged, or who last messaged you.",
        )
        public val socialspy: SocialSpy = SocialSpy(this)
    }

    /** `private-messages.msg:` */
    public class Msg(parent: ConfigGroup) : CommandSettings(
        parent, "msg", listOf("tell", "w", "whisper", "m", "pm"),
        "/msg <player> <message>. Replaces Minecraft's /msg, /tell and /w. <player> is the",
        "other player and <message> what was said.",
    ) {
        public val formatSent: ConfigKey<String> = string("format-sent", "<highlight>You → <player></highlight><gray>:</gray> <white><message>")
        public val formatReceived: ConfigKey<String> = string("format-received", "<highlight><player> → You</highlight><gray>:</gray> <white><message>")
        public val sound: ConfigKey<Sound> = key("sound", Sound.TYPE, Sound.parse(ConfigTypes.PING), "Played to whoever receives a message. Blank for none.")
    }

    /** `private-messages.socialspy:` */
    public class SocialSpy(parent: ConfigGroup) : CommandSettings(
        parent, "socialspy", listOf("spy"), "/socialspy: shows staff every private message. <sender> and <receiver> are filled in.",
    ) {
        public val remember: ConfigKey<Boolean> = boolean("remember", true, "Keep a player's choice when they rejoin.")
        public val logToConsole: ConfigKey<Boolean> = boolean("log-to-console", true, "Also log every private message to the console.")
        public val format: ConfigKey<String> = string("format", "<dark_gray>[Spy]</dark_gray> <gray><sender> → <receiver>: <message>")
    }

    /** What `filter.mode` can be. */
    public enum class FilterMode { CENSOR, BLOCK }
}

/**
 * A command's settings: whether it's on, its aliases and its permission. [name] is the command
 * (it can't change); the section's own name is usually the same (`/filter`'s is `command`).
 */
public open class CommandSettings(
    parent: ConfigGroup,
    section: String,
    defaultAliases: List<String>,
    vararg comment: String,
    name: String = section,
    permission: String = "zchat.command.$name",
) : ConfigSection(parent, section, *comment) {
    /** The command's name. */
    public val command: String = name

    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", ConfigTypes.ALIAS, defaultAliases)
    public val permission: ConfigKey<String> = key("permission", ConfigTypes.PERMISSION, permission)
}
