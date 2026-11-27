package co.xenastudios.xbans.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.CommandTypes
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.NetworkSettings
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.model.Length
import kotlin.time.Duration.Companion.seconds

/**
 * xBans' `config.yml`, version 2 (xBans 2.0). Every setting 1.x had keeps its path and its
 * meaning, so a 1.x config carries forward with nothing but its messages moved (to `lang/`),
 * its theme into `messages.theme` and `update-checker` into `updates`; the 1.x jar put back reads
 * the rest as it was. `screens.appeal-link` stays: it's a setting, not a message. On a proxy the
 * same file is read, and the settings that are only a server's (mutes, signs) do nothing there.
 */
public object XBansConfig : ConfigSchema(
    2,
    "xBans: bans, mutes and warnings, the LiteBans way.",
    "Docs: https://plugins.xenastudios.co/xbans/configuration/",
    "",
    "Apply changes with /xbans reload. A bad value falls back to its default with a",
    "warning in the console; xBans never stops over config. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val screens: Screens = Screens(this)
    public val commands: Commands = Commands(this)
    public val punishing: Punishing = Punishing(this)
    public val mutes: Mutes = Mutes(this)
    public val warnings: Warnings = Warnings(this)

    public val templates: ConfigKey<Map<String, Template>> = key(
        "templates", MapType.TEMPLATES, Template.DEFAULTS,
        "Templates: /ban Steve hacking follows the \"hacking\" template. Each time the same",
        "player gets the same template, the next step of its ladder applies (the last step",
        "repeats). type is ban, mute or warn. A past punishment counts toward the ladder",
        "until expire-ladder has passed since it was made, unless it was revoked. ip makes",
        "it an IP punishment. A template's permission is needed to use it (blank:",
        "xbans.template.<name>); operators have it. Its lengths are held to the sender's",
        "staff-limits like typed ones, unless they have xbans.limit.bypass-templates.",
    )

    public val staffLimits: StaffLimits = StaffLimits(this)
    public val ips: Ips = Ips(this)
    public val scopes: Scopes = Scopes(this)
    public val lists: Lists = Lists(this)

    /** Where punishments are kept: 1.x's `storage`. */
    public val storage: StorageSection = StorageSection(this, "xbans_")

    public val network: Network = Network(this)

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1) { Legacy.toTwo(this) }
    }

    /** `screens:` what's left of 1.x's screens once the messages moved to lang/. */
    public class Screens(parent: ConfigGroup) : ConfigSection(parent, "screens", "What punished players see. The screens' words are in lang/.") {
        public val appealLink: ConfigKey<String> = string("appeal-link", "https://example.com/appeal", "<appeal> in the screens.")
    }

    /** `commands:` each command's settings, at 1.x's paths. */
    public class Commands(parent: ConfigGroup) : ConfigSection(
        parent, "commands",
        "Each command can be turned off (it isn't registered, so another plugin can have its",
        "name), given aliases, or a different permission. xBans takes over vanilla /ban,",
        "/pardon, /ban-ip, /pardon-ip, /kick and /banlist, and gives them back when its own",
        "are off.",
    ) {
        public val ban: Cmd = Cmd(this, "ban")
        public val tempban: Cmd = Cmd(this, "tempban")
        public val ipban: Cmd = Cmd(this, "ipban", "xbans.ipban", listOf("banip", "ban-ip"))
        public val tempipban: Cmd = Cmd(this, "tempipban", "xbans.ipban", listOf("tempbanip"))
        public val unban: Cmd = Cmd(this, "unban", "xbans.unban", listOf("pardon", "unbanip", "pardon-ip"))
        public val mute: Cmd = Cmd(this, "mute")
        public val tempmute: Cmd = Cmd(this, "tempmute")
        public val ipmute: Cmd = Cmd(this, "ipmute", "xbans.ipmute", listOf("muteip"))
        public val tempipmute: Cmd = Cmd(this, "tempipmute", "xbans.ipmute", listOf("tempmuteip"))
        public val unmute: Cmd = Cmd(this, "unmute", "xbans.unmute", listOf("unmuteip"))
        public val warn: Cmd = Cmd(this, "warn")
        public val unwarn: Cmd = Cmd(this, "unwarn")
        public val kick: Cmd = Cmd(this, "kick")
        public val history: Cmd = Cmd(this, "history", aliases = listOf("hist"))
        public val staffhistory: Cmd = Cmd(this, "staffhistory", aliases = listOf("staffhist"))
        public val checkban: Cmd = Cmd(this, "checkban")
        public val checkmute: Cmd = Cmd(this, "checkmute")
        public val warnings: Cmd = Cmd(this, "warnings", comment = arrayOf("Players see their own warnings with this; others' need xbans.warnings.others."))
        public val banlist: Cmd = Cmd(this, "banlist")
        public val mutelist: Cmd = Cmd(this, "mutelist")
        public val prunehistory: Cmd = Cmd(this, "prunehistory")
        public val alts: Cmd = Cmd(this, "alts", aliases = listOf("dupeip", "checkalts"), comment = arrayOf("Accounts that share an IP with a player: LiteBans' /dupeip."))
        public val iphistory: Cmd = Cmd(this, "iphistory")
    }

    public class Punishing(parent: ConfigGroup) : ConfigSection(parent, "punishing", "How punishing works.") {
        public val defaultReason: ConfigKey<String> = string("default-reason", "No reason given", "The reason when none is given.")
        public val silentByDefault: ConfigKey<Boolean> = boolean("silent-by-default", false, "Punishments are silent (-s) unless -p (public) is given.")
    }

    public class Mutes(parent: ConfigGroup) : ConfigSection(parent, "mutes", "What a mute blocks besides chat.") {
        public val blockedCommands: ConfigKey<List<String>> = stringList(
            "blocked-commands",
            listOf("msg", "tell", "w", "whisper", "r", "reply", "me", "say", "mail", "m", "t", "pm", "dm", "message", "teammsg", "tm"),
            "Commands a muted player can't use. Aliases and namespaced forms (/essentials:msg) are",
            "blocked too. On Fabric, Minecraft's /me and /say, and zChat's /msg and /reply.",
        )
        public val blockSigns: ConfigKey<Boolean> = boolean("block-signs", true, "Muted players can't write on signs (Bukkit).")
        public val blockBooks: ConfigKey<Boolean> = boolean("block-books", true, "Muted players can't write or sign books (Bukkit).")
        public val blockAnvils: ConfigKey<Boolean> = boolean("block-anvils", true, "Muted players can't rename items on an anvil (Bukkit, 1.9 on).")
    }

    public class Warnings(parent: ConfigGroup) : ConfigSection(parent, "warnings") {
        public val defaultDuration: ConfigKey<Length> = key(
            "default-duration", Length.TYPE, Length.parse("30d")!!, "How long a warning lasts when /warn isn't given a duration.",
        )
        public val actions: ConfigKey<Map<String, WarnAction>> = key(
            "actions", MapType.ACTIONS,
            linkedMapOf(
                "3" to WarnAction(listOf("tempmute <player> 1h -s Reached 3 warnings")),
                "5" to WarnAction(listOf("tempban <player> 1d -s Reached 5 warnings")),
            ),
            "Commands the console runs when a player reaches a number of active warnings. <player>",
            "is their name; each key is the count.",
        )
    }

    public class StaffLimits(parent: ConfigGroup) : ConfigSection(parent, "staff-limits", "Who can punish whom, and for how long.") {
        public val weights: ConfigKey<Boolean> = boolean(
            "weights", true,
            "Weights: a player with xbans.weight.<n> can only be punished by someone with a higher",
            "weight. Players without one can be punished by anyone with the command. A player's",
            "weight is the one they had when they last joined, if they're offline.",
        )
        public val groups: ConfigKey<Map<String, Limit>> = key(
            "groups", MapType.LIMITS,
            linkedMapOf(
                "helper" to Limit("xbans.limit.helper", Length.parse("1d")!!, Length.parse("1d")!!),
                "moderator" to Limit("xbans.limit.moderator", Length.parse("30d")!!, Length.parse("30d")!!),
            ),
            "Longest ban and mute each group may give. A staff member with a group's permission",
            "gets its limits; with several, the longest. Staff in no group have none. The",
            "permissions aren't given to operators by default.",
        )
    }

    public class Ips(parent: ConfigGroup) : ConfigSection(
        parent, "ips",
        "IP addresses: for IP bans and mutes, /alts and /iphistory. IP addresses are personal",
        "data: see https://plugins.xenastudios.co/xbans/privacy/",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", true,
            "Record players' IPs. Off: no IP bans or mutes, /alts or /iphistory, and nothing is",
            "stored.",
        )
        public val hash: ConfigKey<Boolean> = boolean(
            "hash", false,
            "Store a salted hash instead of the IP: alts and IP bans still work, but there's no list",
            "of addresses to leak. The salt is in ip-salt.txt in xBans' folder; copy it to every",
            "server of a network (and the proxy), and turn it on with every server stopped. Once on,",
            "it stays on for that database: addresses already stored are hashed. A lost salt: see",
            "the privacy page (reset-ip-salt.txt).",
        )
        public val altAlerts: ConfigKey<Boolean> = boolean("alt-alerts", true, "Tell staff when a player joins who shares an IP with a banned account.")
    }

    public class Scopes(parent: ConfigGroup) : ConfigSection(
        parent, "scopes",
        "Server scopes, for networks: a punishment can apply on every server (global) or on one.",
        "/ban Steve server:survival bans Steve on survival only.",
    ) {
        public val serverName: ConfigKey<String> = string(
            "server-name", "server",
            "This server's name, for server:<name>. Each server of a network needs its own: the",
            "name the proxy gives it, so the proxy turns a player away from the servers they're",
            "banned on.",
        )
        public val defaultScope: ConfigKey<DefaultScope> = enum(
            "default-scope", DefaultScope.GLOBAL, listOf(DefaultScope.GLOBAL, DefaultScope.LOCAL),
            "global: punishments apply on every server unless server: says otherwise. local: on this one.",
        )
    }

    public enum class DefaultScope { GLOBAL, LOCAL }

    public class Lists(parent: ConfigGroup) : ConfigSection(parent, "lists", "Lists: /history, /staffhistory, /warnings, /banlist, /mutelist, /alts.") {
        public val perPage: ConfigKey<Int> = int("per-page", 10, min = 1, max = 50)
        public val dateFormat: ConfigKey<String> = string(
            "date-format", "yyyy-MM-dd HH:mm",
            "How dates are written, as in Java's DateTimeFormatter (y M d H h m s a E and",
            "'quoted text'). The server's time zone.",
        )
    }

    /** `network:` 1.x's, with `check-every` in seconds as 1.x wrote it. */
    public class Network(parent: ConfigGroup) : ConfigSection(
        parent, "network",
        "Network mode: a ban made on one server kicks the player from the others and reaches",
        "their ban lists within check-every seconds; with xBans on the proxy too, a banned",
        "player can't get onto the network at all. Every server and the proxy share one MySQL or",
        "MariaDB database (storage) for it. Read at startup and on reload.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", false,
            "On when several servers share this plugin's MySQL or MariaDB database, so a change",
            "made on one (a ban, a mute) reaches the others within a few seconds.",
            "Leave it off for a single server: it does nothing on SQLite.",
        )
        public val checkEvery: ConfigKey<Int> = int("check-every", 3, "Seconds between checks for the other servers' changes.", min = 1, max = 60)

        public fun read(config: Config): NetworkSettings = NetworkSettings(config[enabled], config[checkEvery].seconds)
    }
}

/** A command's settings: whether it's on, its aliases and its permission, at 1.x's paths (`commands.<name>.*`). */
public class Cmd(
    parent: ConfigGroup,
    name: String,
    permission: String = "xbans.$name",
    aliases: List<String> = emptyList(),
    comment: Array<String> = emptyArray(),
) : ConfigSection(parent, name, *comment) {
    /** The command's name, which can't change. */
    public val command: String = name

    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, aliases)
    public val permission: ConfigKey<String> = key("permission", CommandTypes.PERMISSION, permission)
}

/** A template: `/ban Steve hacking`. */
public class Template(
    public val type: PunishmentType,
    public val reason: String,
    public val ladder: List<Length>,
    public val expireLadder: Length,
    public val ip: Boolean,
    public val silent: Boolean,
    public val permission: String,
) {
    /** The permission that lets staff use the template called [name]. */
    public fun permissionFor(name: String): String = permission.ifBlank { "xbans.template.$name" }

    override fun equals(other: Any?): Boolean = other is Template && other.type == type && other.reason == reason &&
        other.ladder == ladder && other.expireLadder == expireLadder && other.ip == ip && other.silent == silent && other.permission == permission

    override fun hashCode(): Int = (type.hashCode() * 31 + reason.hashCode()) * 31 + ladder.hashCode()

    override fun toString(): String = "Template($type, $reason, $ladder)"

    internal companion object {
        private fun of(name: String, type: PunishmentType, reason: String, vararg ladder: String) =
            Template(type, reason, ladder.map { Length.parse(it)!! }, Length.parse("180d")!!, false, false, "xbans.template.$name")

        val DEFAULTS: Map<String, Template> = linkedMapOf(
            "hacking" to of("hacking", PunishmentType.BAN, "Hacking", "7d", "30d", "permanent"),
            "spam" to of("spam", PunishmentType.MUTE, "Spamming", "1h", "1d", "7d"),
            "language" to of("language", PunishmentType.WARN, "Inappropriate language", "30d"),
        )
    }
}

/** The commands the console runs at a count of warnings. */
public class WarnAction(public val commands: List<String>) {
    override fun equals(other: Any?): Boolean = other is WarnAction && other.commands == commands
    override fun hashCode(): Int = commands.hashCode()
    override fun toString(): String = "WarnAction($commands)"
}

/** A staff group's longest ban and mute. */
public class Limit(public val permission: String, public val ban: Length, public val mute: Length) {
    override fun equals(other: Any?): Boolean = other is Limit && other.permission == permission && other.ban == ban && other.mute == mute
    override fun hashCode(): Int = (permission.hashCode() * 31 + ban.hashCode()) * 31 + mute.hashCode()
    override fun toString(): String = "Limit($permission, $ban, $mute)"
}

// The three maps' type as one class, told apart by [kind], rather than an object each: each
// entry a section, a missing setting its default (1.x's templates), one it can't read skipped.
private class MapType<V : Any>(private val kind: Int) : ConfigType<Map<String, V>>() {
    override val name: String get() = when (kind) {
        0 -> "templates"
        1 -> "warning actions"
        else -> "staff groups"
    }

    override fun empty(): Map<String, V> = emptyMap()

    @Suppress("UNCHECKED_CAST")
    override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, V>, String> {
        if (node is YamlNode.Null) return Outcome.Ok(emptyMap())
        if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of $name by name")
        val out = LinkedHashMap<String, V>()
        for (entry in node.entries) {
            val section = entry.value as? YamlNode.Mapping ?: continue
            val read = when (kind) {
                0 -> template(section)
                1 -> WarnAction(strings(section["commands"]))
                else -> limit(section)
            } ?: continue
            out[if (kind == 0) entry.key.lowercase() else entry.key] = read as V
        }
        return Outcome.Ok(out)
    }

    override fun encode(value: Map<String, V>): YamlNode = YamlNode.Mapping(
        value.map { (name, item) ->
            val fields: List<Pair<String, YamlNode>> = when (item) {
                is Template -> listOf(
                    "type" to YamlNode.Scalar(item.type.name.lowercase()),
                    "reason" to YamlNode.Scalar(item.reason),
                    "ladder" to YamlNode.Sequence(item.ladder.map { YamlNode.Scalar(it.typed()) }),
                    "expire-ladder" to YamlNode.Scalar(item.expireLadder.typed()),
                    "ip" to YamlNode.Scalar(item.ip.toString()),
                    "silent" to YamlNode.Scalar(item.silent.toString()),
                    "permission" to YamlNode.Scalar(item.permission),
                )
                is WarnAction -> listOf("commands" to YamlNode.Sequence(item.commands.map { YamlNode.Scalar(it) }))
                else -> {
                    val limit = item as Limit
                    listOf(
                        "permission" to YamlNode.Scalar(limit.permission),
                        "ban" to YamlNode.Scalar(limit.ban.typed()),
                        "mute" to YamlNode.Scalar(limit.mute.typed()),
                    )
                }
            }
            YamlNode.Mapping.Entry(name, YamlNode.Mapping(fields.map { YamlNode.Mapping.Entry(it.first, it.second) }))
        },
    )

    private fun template(section: YamlNode.Mapping): Template? {
        val type = when (text(section, "type")?.trim()?.lowercase() ?: "ban") {
            "ban" -> PunishmentType.BAN
            "mute" -> PunishmentType.MUTE
            "warn", "warning" -> PunishmentType.WARN
            "kick" -> PunishmentType.KICK
            else -> return null
        }
        val ladder = strings(section["ladder"]).mapNotNull { Length.parse(it) }.ifEmpty { listOf(Length.PERMANENT) }
        val expire = text(section, "expire-ladder")?.let { Length.parse(it) } ?: Length.parse("180d")!!
        return Template(
            type, text(section, "reason") ?: "", ladder, expire,
            flag(section, "ip") ?: false, flag(section, "silent") ?: false, (text(section, "permission") ?: "").trim().lowercase(),
        )
    }

    private fun limit(section: YamlNode.Mapping): Limit? {
        val ban = text(section, "ban")?.let { Length.parse(it) ?: return null } ?: Length.PERMANENT
        val mute = text(section, "mute")?.let { Length.parse(it) ?: return null } ?: Length.PERMANENT
        return Limit((text(section, "permission") ?: "").trim().lowercase(), ban, mute)
    }

    private fun strings(node: YamlNode?): List<String> = when (node) {
        is YamlNode.Sequence -> node.items.mapNotNull { (it as? YamlNode.Scalar)?.content }
        is YamlNode.Scalar -> listOf(node.content)
        else -> emptyList()
    }

    private fun text(section: YamlNode.Mapping, key: String): String? = (section[key] as? YamlNode.Scalar)?.content

    private fun flag(section: YamlNode.Mapping, key: String): Boolean? = when (text(section, key)?.trim()?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }

    companion object {
        val TEMPLATES: ConfigType<Map<String, Template>> = MapType(0)
        val ACTIONS: ConfigType<Map<String, WarnAction>> = MapType(1)
        val LIMITS: ConfigType<Map<String, Limit>> = MapType(2)
    }
}
