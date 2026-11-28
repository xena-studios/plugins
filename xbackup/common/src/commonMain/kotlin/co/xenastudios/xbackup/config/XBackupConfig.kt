package co.xenastudios.xbackup.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.CommandTypes
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.update.UpdatesSection

/**
 * xBackup's `config.yml`, version 2 (xBackup 2.0). Every setting 1.x had keeps its path and its
 * meaning, so a 1.x config carries forward with nothing but its messages moved (to `lang/`), its
 * theme into `messages.theme` and `update-checker` into `updates`; the 1.x jar put back reads the
 * rest as it was. The broadcasts, which 1.x turned on by giving them text, are switches here, with
 * their words in `lang/`.
 */
public object XBackupConfig : ConfigSchema(
    2,
    "xBackup: incremental backups that cost almost nothing, and a restore you can trust.",
    "Docs: https://plugins.xenastudios.co/xbackup/configuration/",
    "",
    "Apply changes with /xbackup reload: every setting applies, remotes included. A bad value",
    "falls back to its default with a warning in the console; xBackup never stops over config.",
    "The messages are in lang/. Remotes' passwords go in secrets.yml, never here.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val broadcasts: Broadcasts = Broadcasts(this)
    public val command: Command = Command(this)

    public val repository: ConfigKey<String> = string(
        "repository", "plugins/xBackup/repository",
        "Where backups are kept: a folder, relative to the server folder or absolute. Put it on",
        "a second disk if you have one. Changing it starts a new, empty repository there; the",
        "old one is left as it is. On Fabric the default is config/xbackup/repository.",
    )

    public val schedule: Schedule = Schedule(this)
    public val sources: Sources = Sources(this)

    public val exclude: ConfigKey<List<String>> = stringList(
        "exclude",
        listOf("**/session.lock", "plugins/.paper-remapped/**", "plugins/*/logs/**", "plugins/*/cache/**", "plugins/bStats/**", "**/*.tmp", "**/*.lck"),
        "Paths to leave out, relative to the server folder. * is anything but /, ** is anything,",
        "so \"plugins/*/logs/**\" leaves out every plugin's logs folder.",
    )

    public val retention: Retention = Retention(this)
    public val performance: Performance = Performance(this)
    public val verify: Verify = Verify(this)

    public val remotes: ConfigKey<Map<String, Remote>> = key(
        "remotes", RemoteType, emptyMap(),
        "Copies of the repository somewhere else, updated after each backup. Only new files are",
        "sent. Name each one; the docs list every type and its settings:",
        "https://plugins.xenastudios.co/xbackup/remotes/",
    )

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1, Migrations.ONE)
    }

    /** `broadcasts:` what 1.x's messages.broadcast-start and broadcast-done did, as switches. */
    public class Broadcasts(parent: ConfigGroup) : ConfigSection(parent, "broadcasts", "Told to everyone online. The words are in lang/ (broadcast.start, broadcast.done).") {
        public val start: ConfigKey<Boolean> = boolean("start", false, "When a backup starts.")
        public val done: ConfigKey<Boolean> = boolean("done", false, "When a backup is done, with how long it took.")
    }

    /** `command:` /backup, at 1.x's path. */
    public class Command(parent: ConfigGroup) : ConfigSection(
        parent, "command",
        "/backup: run, list, check and restore backups. Each subcommand has its own permission,",
        "xbackup.<subcommand>, which operators have by default.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, listOf("backups"))
    }

    public class Schedule(parent: ConfigGroup) : ConfigSection(parent, "schedule", "When backups run.") {
        public val every: ConfigKey<String> = string(
            "every", "1h",
            "How often a backup runs, such as 1h, 30m or 6h. \"off\" runs them only when asked",
            "(/backup now). Counted from the last backup, so a restart doesn't reset it.",
        )
        public val skipWhenIdle: ConfigKey<Boolean> = boolean("skip-when-idle", true, "Skip a scheduled backup when nobody has been online since the last one.")
    }

    public class Sources(parent: ConfigGroup) : ConfigSection(parent, "sources", "What's backed up.") {
        public val worlds: ConfigKey<Boolean> = boolean("worlds", true, "Every loaded world's save folder, with its nether and end.")
        public val plugins: ConfigKey<Boolean> = boolean(
            "plugins", true,
            "The plugins folder: every plugin's data and jar (on Fabric, the mods folder). xBackup's",
            "own repository is never included. The Xena plugins' databases are copied by each",
            "plugin's own writer, so none is caught mid-write.",
        )
        public val serverFiles: ConfigKey<Boolean> = boolean(
            "server-files", true,
            "The server's own settings: server.properties, the .yml, .json, .toml and .txt files",
            "beside it (ops, whitelist, bans), and the config folder.",
        )
        public val extra: ConfigKey<List<String>> = stringList("extra", emptyList(), "More paths to back up, relative to the server folder, such as an unloaded world.")
    }

    public class Retention(parent: ConfigGroup) : ConfigSection(
        parent, "retention",
        "Which backups to keep. Each tier keeps the newest backup of each of its last so many",
        "hours, days, weeks or months; a backup can count for several. The newest backup and",
        "pinned ones (/backup pin) are always kept. Space is given back once a day.",
    ) {
        public val last: ConfigKey<Int> = int("last", 3, "Always keep this many of the newest backups, however close together.", min = 0, max = 10_000)
        public val hourly: ConfigKey<Int> = int("hourly", 24, min = 0, max = 10_000)
        public val daily: ConfigKey<Int> = int("daily", 7, min = 0, max = 10_000)
        public val weekly: ConfigKey<Int> = int("weekly", 4, min = 0, max = 10_000)
        public val monthly: ConfigKey<Int> = int("monthly", 6, min = 0, max = 10_000)
        public val maxSize: ConfigKey<String> = string(
            "max-size", "off",
            "The most space the repository may use, such as 50GB. Past it, the oldest backups go",
            "first. \"off\" for no limit.",
        )
        public val minFreeSpace: ConfigKey<String> = string(
            "min-free-space", "2GB",
            "Free space a backup always leaves on the repository's disk. A backup that would go",
            "under it removes old backups first, and if that's not enough, doesn't start.",
        )
    }

    public class Performance(parent: ConfigGroup) : ConfigSection(
        parent, "performance",
        "Keeping backups cheap to run. Hashing and compressing happen off the server's threads;",
        "these keep them from crowding the disk or the network.",
    ) {
        public val readLimit: ConfigKey<String> = string("read-limit", "off", "The most a backup reads from disk per second, such as 50MB. \"off\" for no limit.")
        public val uploadLimit: ConfigKey<String> = string("upload-limit", "off", "The most sent to remotes per second, such as 5MB. \"off\" for no limit.")
        public val compressionLevel: ConfigKey<Int> = int(
            "compression-level", 6,
            "Deflate level for files that compress, 1 (fastest) to 9 (smallest). Region files are",
            "already compressed and stored as they are.",
            min = 1, max = 9,
        )
        public val maxPause: ConfigKey<String> = string(
            "max-pause", "15m",
            "The longest autosave may stay off for a backup. If reading the worlds takes longer,",
            "autosave goes back on and that backup is abandoned. Nothing is lost either way.",
        )
    }

    public class Verify(parent: ConfigGroup) : ConfigSection(
        parent, "verify",
        "Each backup checks what it just wrote. This also reads back a share of the older data",
        "now and then, so damage on the disk is found before you need a restore.",
    ) {
        public val every: ConfigKey<String> = string("every", "7d", "How often to read back older data, such as 7d. \"off\" never does.")
        public val percent: ConfigKey<Int> = int("percent", 10, "What share of the data each check reads, in percent.", min = 1, max = 100)
    }
}

/**
 * A remote, as `remotes.<name>` holds it: one shape for every type; each type reads the settings
 * it needs, and the docs list them. 1.x's settings, at 1.x's names.
 */
public class Remote(
    public val type: String,
    public val enabled: Boolean,
    public val path: String,
    public val url: String,
    public val region: String,
    public val bucket: String,
    public val pathStyle: Boolean,
    public val username: String,
    public val host: String,
    public val port: Int,
    public val privateKey: String,
    public val hostKey: String,
    public val security: String,
    public val clientId: String,
) {
    override fun equals(other: Any?): Boolean = other is Remote && other.type == type && other.enabled == enabled && other.path == path &&
        other.url == url && other.region == region && other.bucket == bucket && other.pathStyle == pathStyle && other.username == username &&
        other.host == host && other.port == port && other.privateKey == privateKey && other.hostKey == hostKey && other.security == security &&
        other.clientId == clientId

    override fun hashCode(): Int = ((type.hashCode() * 31 + path.hashCode()) * 31 + url.hashCode()) * 31 + host.hashCode()

    override fun toString(): String = "Remote($type, $path)"
}

// remotes: each entry a section, a missing setting 1.x's template's; one that isn't a section skipped.
private object RemoteType : ConfigType<Map<String, Remote>>() {
    override val name: String get() = "remotes"

    override fun empty(): Map<String, Remote> = emptyMap()

    override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, Remote>, String> {
        if (node is YamlNode.Null) return Outcome.Ok(emptyMap())
        if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of remotes by name")
        val out = LinkedHashMap<String, Remote>()
        for (entry in node.entries) {
            val section = entry.value as? YamlNode.Mapping ?: continue
            fun text(key: String, default: String = ""): String = (section[key] as? YamlNode.Scalar)?.content ?: default
            fun flag(key: String, default: Boolean): Boolean = when (text(key).trim().lowercase()) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> default
            }
            out[entry.key] = Remote(
                text("type"), flag("enabled", true), text("path", "xbackup"), text("url"), text("region"), text("bucket"),
                flag("path-style", false), text("username"), text("host"), text("port").trim().toIntOrNull()?.coerceIn(0, 65_535) ?: 0,
                text("private-key"), text("host-key"), text("security", "explicit"), text("client-id"),
            )
        }
        return Outcome.Ok(out)
    }

    override fun encode(value: Map<String, Remote>): YamlNode = YamlNode.Mapping(
        value.map { (name, remote) ->
            val fields = listOf(
                "type" to remote.type, "enabled" to remote.enabled.toString(), "path" to remote.path, "url" to remote.url,
                "region" to remote.region, "bucket" to remote.bucket, "path-style" to remote.pathStyle.toString(), "username" to remote.username,
                "host" to remote.host, "port" to remote.port.toString(), "private-key" to remote.privateKey, "host-key" to remote.hostKey,
                "security" to remote.security, "client-id" to remote.clientId,
            )
            YamlNode.Mapping.Entry(name, YamlNode.Mapping(fields.map { YamlNode.Mapping.Entry(it.first, YamlNode.Scalar(it.second)) }))
        },
    )
}

/** The functions handed to the config as it's built: one class, not a lambda each (decisions/2026-11-25-a-lighter-core.md). */
internal class Migrations private constructor() : (MigrationScope) -> Unit {
    override fun invoke(scope: MigrationScope) = Legacy.toTwo(scope)

    companion object {
        @Suppress("UNCHECKED_CAST")
        val ONE: MigrationScope.() -> Unit get() = Migrations() as MigrationScope.() -> Unit
    }
}
