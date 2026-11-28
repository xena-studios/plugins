package co.xenastudios.xrewind.config

import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection

/**
 * xRewind's `config.yml`, version 2 (xRewind 2.0). Every setting 1.x had keeps its path and its
 * meaning: `log`, `rollback`, `purge` and `storage` are as they were, so a 1.x config carries
 * forward with only its messages moved (to `lang/`), its theme into `messages.theme` and
 * `update-checker` into `updates` ([Legacy]); the 1.x jar put back reads the rest as it was.
 */
public object XRewindConfig : ConfigSchema(
    2,
    "xRewind: every change, logged. Any of it, undone.",
    "Docs: https://plugins.xenastudios.co/xrewind/configuration/",
    "",
    "Apply changes with /xrewind reload (or /xr reload): everything applies, what's logged and",
    "where it's stored included. A bad value falls back to its default with a warning in the",
    "console; xRewind never stops over config. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    /** Where the history is kept: 1.x's `storage`. Changing it on reload switches to the new one; history isn't copied across. */
    public val storage: StorageSection = StorageSection(this, "xrewind_")

    public val log: Log = Log(this)
    public val rollback: Rollback = Rollback(this)
    public val purge: Purge = Purge(this)

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1, co.xenastudios.xrewind.Startup.configOne)
    }

    /** `log:` what's logged, each a kind of change. */
    public class Log(parent: ConfigGroup) : ConfigSection(
        parent, "log",
        "What's logged. Something that's off isn't even listened for, so it costs nothing.",
        "Changes apply on /xrewind reload.",
    ) {
        public val blockBreak: ConfigKey<Boolean> = boolean("block-break", true, "Blocks players break, with what they held, and blocks that fall off with them.")
        public val blockPlace: ConfigKey<Boolean> = boolean("block-place", true, "Blocks players place, and what they replaced.")
        public val explosions: ConfigKey<Boolean> = boolean("explosions", true, "Blocks explosions destroy. TNT is put down to whoever lit it.")
        public val fire: ConfigKey<Boolean> = boolean("fire", true, "Blocks burnt away, and fires players light.")
        public val liquids: ConfigKey<Boolean> = boolean("liquids", true, "Buckets, and flowing water or lava destroying a block. Plain spreading isn't logged.")
        public val pistons: ConfigKey<Boolean> = boolean("pistons", true, "Blocks pistons move.")
        public val mobs: ConfigKey<Boolean> = boolean("mobs", true, "Endermen, withers, ravagers, falling sand. Sheep eating grass and villagers farming aren't.")
        public val signs: ConfigKey<Boolean> = boolean("signs", true, "Text written on signs. Bukkit only: Fabric has no event for it.")
        public val containers: ConfigKey<Boolean> = boolean("containers", true, "Items players put into or take out of containers.")
        public val hoppers: ConfigKey<Boolean> = boolean("hoppers", false, "Items hoppers and droppers move. Busy on farms.")
        public val kills: ConfigKey<Boolean> = boolean("kills", true, "Mobs and players killed by players.")
        public val clicks: ConfigKey<Boolean> = boolean("clicks", false, "Doors, trapdoors, gates, levers and buttons players use.")
        public val leafDecay: ConfigKey<Boolean> = boolean("leaf-decay", false, "Leaves decaying.")
        public val sessions: ConfigKey<Boolean> = boolean("sessions", true, "Players joining and quitting, and where.")
        public val chat: ConfigKey<Boolean> = boolean("chat", false, "Chat messages. Off by default for players' privacy; lookups need xrewind.lookup.chat.")
        public val commands: ConfigKey<Boolean> = boolean(
            "commands", false,
            "Commands players run. Off by default; lookups need xrewind.lookup.chat. Bukkit only:",
            "Fabric has no event before a command runs.",
        )
        public val excludedWorlds: ConfigKey<List<String>> = stringList(
            "excluded-worlds", emptyList(),
            "Worlds nothing is logged in, by name: world_nether on Bukkit, minecraft:the_nether on Fabric.",
        )
    }

    /** `rollback:` */
    public class Rollback(parent: ConfigGroup) : ConfigSection(
        parent, "rollback",
        "Rollbacks and restores. Blocks are set a few milliseconds per tick, chunk by chunk (each",
        "region on its own thread on Folia), so even a big one doesn't freeze the server, and",
        "without physics, so nothing cascades. One stopped by a restart carries on when the",
        "server starts again.",
    ) {
        public val msPerTick: ConfigKey<Int> = int("ms-per-tick", 10, "How long a rollback may work each tick, in milliseconds. A tick is 50.", min = 1, max = 40)
        public val defaultRadius: ConfigKey<Int> = int("default-radius", 10, "The radius, in blocks, when a rollback doesn't give one with r:.", min = 0, max = 1000)
        public val maxRadius: ConfigKey<Int> = int(
            "max-radius", 100,
            "The largest r: without the xrewind.rollback.global permission, which r:#world and",
            "r:#global also need.",
            min = 0, max = 100_000,
        )
    }

    /** `purge:` */
    public class Purge(parent: ConfigGroup) : ConfigSection(parent, "purge", "Deleting old history, so the database doesn't grow forever.") {
        public val keepDays: ConfigKey<Int> = int(
            "keep-days", 30,
            "Delete history older than this many days, once a day. 0 keeps everything. A",
            "rollback can't reach further back than what's kept.",
            min = 0, max = 10_000,
        )
    }
}
