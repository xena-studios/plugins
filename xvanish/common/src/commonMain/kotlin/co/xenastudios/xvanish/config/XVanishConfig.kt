package co.xenastudios.xvanish.config

import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.NetworkSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection

/**
 * xVanish's `config.yml`, version 1. Every switch applies on `/xvanish reload`; a switch that's
 * off registers no listener at all. `network` and `storage` are read at startup.
 */
public object XVanishConfig : ConfigSchema(
    1,
    "xVanish: vanish for staff.",
    "Docs: https://plugins.xenastudios.co/xvanish/configuration/",
    "",
    "Apply changes with /xvanish reload. Everything applies except network and storage.",
    "A bad value falls back to its default with a warning in the console. The messages are",
    "in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val vanish: Vanish = Vanish(this)
    public val hide: Hide = Hide(this)
    public val noTrace: NoTrace = NoTrace(this)
    public val whileVanished: WhileVanished = WhileVanished(this)
    public val silentContainers: SilentContainers = SilentContainers(this)
    public val chat: Chat = Chat(this)

    /** Several servers sharing who's vanished, through one MySQL database. */
    public val network: NetworkSection = NetworkSection(this)

    /** Only with network.enabled: where the network's vanished players are kept. */
    public val storage: StorageSection = StorageSection(this, "xvanish_")

    public val updates: UpdatesSection = UpdatesSection(this)

    /** `vanish:` */
    public class Vanish(parent: ConfigGroup) : ConfigSection(parent, "vanish", "Vanishing itself.") {
        public val aliases: ConfigKey<List<String>> = stringList(
            "aliases", listOf("v", "sv", "pv"),
            "Other names for /vanish. sv and pv keep SuperVanish's and PremiumVanish's habits:",
            "/sv on, /sv off, /sv list, /sv login, /sv logout.",
        )
        public val remember: ConfigKey<Boolean> = boolean(
            "remember", true,
            "Stay vanished through a relog and a restart. Kept in vanished.txt in this folder,",
            "written as it changes, so a crash can't unvanish anyone.",
        )
        public val fakeMessages: ConfigKey<Boolean> = boolean(
            "fake-messages", true,
            "Vanishing sends a fake quit message and showing again a fake join message, so it",
            "looks as if you left and came back (lang/: fake.quit and fake.join). /vanish login",
            "and /vanish logout send them by hand.",
        )
        public val maxLevel: ConfigKey<Int> = int(
            "max-level", 5,
            "The highest xvanish.level.<n> looked for. A vanished player is seen only by players",
            "with xvanish.see and a level at least theirs; without a level, a player's is 1.",
            min = 1, max = 100,
        )
        public val xbansStaffSee: ConfigKey<Boolean> = boolean(
            "xbans-staff-see", true,
            "With xBans installed, the players it counts as staff (xbans.staff) see vanished",
            "players of level 1, as if they had xvanish.see.",
        )
        public val notifyStaff: ConfigKey<Boolean> = boolean(
            "notify-staff", true,
            "Tell the players who can see a vanished player when they vanish or show again.",
        )
        public val otherPluginsPermissions: ConfigKey<Boolean> = boolean(
            "other-plugins-permissions", false,
            "Honour SuperVanish's sv.* and PremiumVanish's pv.* permissions too (sv.use, sv.see,",
            "sv.list, sv.login, sv.logout, sv.toggleitems, sv.others, sv.joinvanished, and pv.level.<n>),",
            "so a permissions setup made for them works unchanged.",
        )
    }

    /** `hide:` */
    public class Hide(parent: ConfigGroup) : ConfigSection(
        parent, "hide",
        "Where else a vanished player is left out. They're always gone from the world and the",
        "tab list for anyone who can't see them, and from every Xena plugin.",
    ) {
        public val serverList: ConfigKey<Boolean> = boolean("server-list", true, "Not counted in the server list's player count, nor shown in its sample.")
        public val deathMessages: ConfigKey<Boolean> = boolean("death-messages", true, "Their death message isn't broadcast.")
        public val advancementMessages: ConfigKey<Boolean> = boolean(
            "advancement-messages", true, "Their advancements aren't announced. Paper and Folia only: Spigot has no way to.",
        )
        public val sleeping: ConfigKey<Boolean> = boolean("sleeping", true, "Not counted when the server checks whether everyone's asleep.")
        public val mobSpawning: ConfigKey<Boolean> = boolean("mob-spawning", true, "Mobs don't spawn because of them. Paper and Folia only.")
        public val locatorBar: ConfigKey<Boolean> = boolean("locator-bar", true, "Not on other players' locator bar (Minecraft 1.21.6 on).")
    }

    /** `no-trace:` */
    public class NoTrace(parent: ConfigGroup) : ConfigSection(parent, "no-trace", "Nothing in the world gives a vanished player away.") {
        public val mobTargeting: ConfigKey<Boolean> = boolean("mob-targeting", true, "Mobs don't target them.")
        public val pickup: ConfigKey<Boolean> = boolean(
            "pickup", true, "They don't pick up items or experience. /vanish pickup lets one player pick up again.",
        )
        public val physical: ConfigKey<Boolean> = boolean("physical", true, "They don't press pressure plates, trip tripwires, trample farmland or break turtle eggs.")
        public val sculk: ConfigKey<Boolean> = boolean("sculk", true, "Sculk sensors and shriekers don't hear them (Minecraft 1.17 on).")
        public val collisions: ConfigKey<Boolean> = boolean("collisions", true, "Players and mobs don't push them (Minecraft 1.9 on).")
        public val projectiles: ConfigKey<Boolean> = boolean("projectiles", true, "Arrows and other projectiles pass through them, where the server allows (Paper, and Spigot 1.20.4 on); elsewhere they do no harm.")
        public val raids: ConfigKey<Boolean> = boolean("raids", true, "They don't start raids (Minecraft 1.14 on).")
    }

    /** `while-vanished:` */
    public class WhileVanished(parent: ConfigGroup) : ConfigSection(parent, "while-vanished", "What vanishing gives the player themselves.") {
        public val nightVision: ConfigKey<Boolean> = boolean("night-vision", false, "Night vision.")
        public val fly: ConfigKey<Boolean> = boolean("fly", true, "Flight; it's taken away again as they show, unless they could fly before.")
        public val invulnerable: ConfigKey<Boolean> = boolean("invulnerable", true, "No damage, and no hunger.")
        public val bossBar: ConfigKey<Boolean> = boolean(
            "boss-bar", true,
            "A boss bar saying they're vanished (lang/: boss-bar). Minecraft 1.9 on.",
        )
    }

    /** `silent-containers:` */
    public class SilentContainers(parent: ConfigGroup) : ConfigSection(
        parent, "silent-containers",
        "A vanished player opening a chest, barrel or shulker box sees a read-only copy of it,",
        "with no lid moving and no sound. Sneaking opens it as usual. An ender chest opens",
        "their own, silently. Bukkit only.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    }

    /** `chat:` */
    public class Chat(parent: ConfigGroup) : ConfigSection(parent, "chat") {
        public val confirmPublic: ConfigKey<Boolean> = boolean(
            "confirm-public", true,
            "A vanished player's first public chat message is held back with a warning; saying the",
            "same again within 15 seconds sends it.",
        )
    }
}
