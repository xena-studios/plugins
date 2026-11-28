package co.xenastudios.xlimbo.config

import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.update.UpdatesSection
import co.xenastudios.xlimbo.api.ReturnRoute

/**
 * xLimbo's `config.yml`, version 3. Every setting 2.x had keeps its path and meaning, so a 2.x
 * config carries forward with nothing but its messages moved (to `lang/`), its theme into
 * `messages.theme` and `update-checker` into `updates`; the 2.1.0 jar put back reads 3.0's file
 * (as a newer one, read-only) and keeps every setting. 3.0 adds `send-back`.
 */
public object XLimboConfig : ConfigSchema(
    3,
    "xLimbo: never drop a player again.",
    "Docs: https://plugins.xenastudios.co/xlimbo/configuration/",
    "",
    "Apply changes with /xlimbo reload. Everything applies, even the world's name and",
    "floor. A bad value falls back to its default with a warning in the console; xLimbo",
    "never stops over config. The messages are in lang/: one file per language.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val world: World = World(this)
    public val player: Player = Player(this)
    public val join: Join = Join(this)
    public val quit: Quit = Quit(this)
    public val death: Death = Death(this)

    public val mainServer: ConfigKey<String> = string(
        "main-server", "main",
        "The server /join and automatic returns send players to: its name in your proxy's",
        "config, such as \"main\". Only with send-back.via: proxy.",
    )

    public val sendBack: SendBack = SendBack(this)
    public val autoJoin: AutoJoin = AutoJoin(this)
    public val serverCheck: ServerCheck = ServerCheck(this)
    public val queue: Queue = Queue(this)
    public val joinCommand: JoinCommand = JoinCommand(this)
    public val actionBar: ActionBar = ActionBar(this)
    public val protections: Protections = Protections(this)

    /** The update checker: `updates.check` was 2.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 1.x spread arrivals up to 4000 blocks apart: a fresh chunk for nearly every player. And
        // 1.x read a build guard of 0 as "unlimited"; 2.0 says so with enabled: false.
        migrate(1) {
            // 1.x's default floor, plain GLASS as it wrote it, gets 2.0's purple; a block an owner chose is kept.
            val floor = this["world.floor-block"]
            if (floor is YamlNode.Scalar && floor.content.trim() == "GLASS") this["world.floor-block"] = "purple_stained_glass"
            val radius = this["world.border.spawn-radius"]
            if (radius is YamlNode.Scalar && radius.content.trim() == "4000") this["world.border.spawn-radius"] = "64"
            val places = this["protections.build-guard.max-places-per-second"]
            if (places is YamlNode.Scalar && places.content.trim() == "0") {
                this["protections.build-guard.enabled"] = "false"
                this["protections.build-guard.max-places-per-second"] = "30"
            }
        }
        // 3.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(2) { Legacy.toThree(this) }
    }

    /** `world:` */
    public class World(parent: ConfigGroup) : ConfigSection(
        parent, "world",
        "The void world players wait in: one floor layer, nothing else. Changing its name,",
        "floor-block or floor-y rebuilds it on /xlimbo reload; players are moved out and back.",
    ) {
        public val worldName: ConfigKey<String> = key(
            "name", ConfigType.STRING, "xlimbo",
            "The limbo world, created on startup. To use the server's main world instead, set",
            "this to its name (level-name in server.properties) and give it xLimbo's generator",
            "in bukkit.yml (worlds: <name>: generator: xLimbo).",
            check = { if (PLAIN.matches(it) && !it.all { c -> c == '.' }) null else "can only use letters, digits, _, - and ." },
        )
        public val floorBlock: ConfigKey<String> = key(
            "floor-block", BLOCK, "purple_stained_glass",
            "The one-block-thick floor everyone stands on: a block as Minecraft names it, such as",
            "white_stained_glass. It must be solid and not fall.",
        )
        public val floorY: ConfigKey<Int> = int("floor-y", 64, "The floor's height. Players stand one block above it.", min = -63, max = 318)
        public val autoSave: ConfigKey<Boolean> = boolean(
            "auto-save", false,
            "false keeps the world off disk: it starts fresh every time the server does. true",
            "saves it, for builds that should last.",
        )
        public val resetOnStartup: ConfigKey<Boolean> = boolean("reset-on-startup", false, "Start fresh on every startup even when auto-save is on.")
        public val border: Border = Border(this)
        public val viewDistance: ConfigKey<Int> = int(
            "view-distance", 4, "Chunks sent to players in this world, 2 to 32. -1 keeps the server's setting.", min = -1, max = 32,
        )
        public val simulationDistance: ConfigKey<Int> = int(
            "simulation-distance", 4, "Chunks that tick around players in this world, 2 to 32. -1 keeps the server's setting.", min = -1, max = 32,
        )

        private companion object {
            val PLAIN = Regex("[A-Za-z0-9_.-]+")

            // A block as Minecraft names it, in lower case without minecraft:, so 1.x's GLASS reads
            // as glass. Whether it's a solid block that doesn't fall is the server's to say.
            val BLOCK: ConfigType<String> = ConfigType.scalar("block", { it }) { text ->
                val name = text.trim().lowercase().removePrefix("minecraft:")
                if (Regex("[a-z0-9_]+").matches(name)) co.xenastudios.core.common.Outcome.Ok(name)
                else co.xenastudios.core.common.Outcome.Err("isn't a block, such as glass or white_stained_glass")
            }
        }
    }

    /** `world.border:` */
    public class Border(parent: ConfigGroup) : ConfigSection(parent, "border") {
        public val size: ConfigKey<Int> = int("size", 10_000, "The world border's width in blocks. It caps how much world can ever exist.", min = 64, max = 1_000_000)
        public val spawnRadius: ConfigKey<Int> = int(
            "spawn-radius", 64,
            "Arriving players land up to this many blocks from the centre, so they don't stack.",
            "Small keeps a mass arrival to a handful of chunks; the land around it is kept ready.",
            min = 0, max = 500_000,
        )
    }

    /** `player:` */
    public class Player(parent: ConfigGroup) : ConfigSection(parent, "player", "What arriving players get.") {
        public val setCreative: ConfigKey<Boolean> = boolean("set-creative", true, "Put arriving players in creative.")
        public val enableFlight: ConfigKey<Boolean> = boolean("enable-flight", true, "Give arriving players flight, and keep it on.")
        public val hideOthers: ConfigKey<Boolean> = boolean(
            "hide-others", true,
            "Players can't see each other. With hundreds arriving at once, everyone tracking",
            "everyone else is the biggest load a limbo has; this takes it away.",
        )
    }

    /** `join:` */
    public class Join(parent: ConfigGroup) : ConfigSection(parent, "join", "Arriving. The welcome message is join.welcome in lang/.") {
        public val suppressJoinMessage: ConfigKey<Boolean> = boolean("suppress-join-message", true, "Hide the \"joined the game\" broadcast.")
        public val clearChatOnJoin: ConfigKey<Boolean> = boolean("clear-chat-on-join", false, "Clear the arriving player's chat first.")
    }

    /** `quit:` */
    public class Quit(parent: ConfigGroup) : ConfigSection(parent, "quit") {
        public val suppressQuitMessage: ConfigKey<Boolean> = boolean("suppress-quit-message", true, "Hide the \"left the game\" broadcast.")
    }

    /** `death:` */
    public class Death(parent: ConfigGroup) : ConfigSection(parent, "death") {
        public val suppressDeathMessage: ConfigKey<Boolean> = boolean("suppress-death-message", true, "Hide death messages.")
    }

    /** `send-back:`, new in 3.0. */
    public class SendBack(parent: ConfigGroup) : ConfigSection(
        parent, "send-back",
        "How players go back. proxy: xLimbo is your network's fallback server, and players go",
        "to main-server through the proxy (BungeeCord, Waterfall or Velocity). teleport: xLimbo",
        "is a world on this server (/xlimbo send puts players in it), and players go back with",
        "a teleport, through xTeleport when it's installed.",
    ) {
        public val via: ConfigKey<ReturnRoute> = enum("via", ReturnRoute.PROXY, ReturnRoute.entries)
        public val destination: ConfigKey<String> = key(
            "destination", ConfigType.STRING, "previous",
            "With via: teleport, where to: previous (where they were before limbo, or spawn if",
            "that isn't known), spawn (xTeleport's /spawn, or the main world's), warp:<name>",
            "(an xTeleport warp), or the name of a destination another plugin gave xTeleport.",
            check = { if (DESTINATION.matches(it)) null else "must be previous, spawn, warp:<name>, or a destination's name" },
        )

        private companion object {
            val DESTINATION = Regex("[A-Za-z0-9_-]+(:[A-Za-z0-9_-]+)?")
        }
    }

    /** `auto-join:` */
    public class AutoJoin(parent: ConfigGroup) : ConfigSection(
        parent, "auto-join",
        "Send players back on their own this long after they arrive. The message they get is",
        "auto-join.sending in lang/.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", false, "Only without server-check, which sends players back as soon as the main server is up.",
        )
        public val delaySeconds: ConfigKey<Int> = int("delay-seconds", 30, "How long after arriving.", min = 1, max = 86_400)
    }

    /** `server-check:` */
    public class ServerCheck(parent: ConfigGroup) : ConfigSection(
        parent, "server-check",
        "Watch the main server, and send everyone back as soon as it's up, instead of waiting",
        "for /join or auto-join. Only with send-back.via: proxy. Everyone here is told",
        "(server-check.up in lang/) when it comes back.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val address: ConfigKey<String> = string(
            "address", "127.0.0.1:25566",
            "Your main server's address as xLimbo can reach it (not the proxy's), such as",
            "\"127.0.0.1:25566\". xLimbo asks it for its status, as a server list does.",
        )
        public val intervalSeconds: ConfigKey<Int> = int("interval-seconds", 5, min = 1, max = 300)
    }

    /** `queue:` */
    public class Queue(parent: ConfigGroup) : ConfigSection(
        parent, "queue",
        "How automatic returns are paced. While a player waits their turn, their action bar",
        "shows queue.action-bar from lang/ (blank shows the normal one instead).",
    ) {
        public val perSecond: ConfigKey<Int> = int(
            "per-second", 5,
            "How many players are sent back each second, so the main server isn't flooded the",
            "moment it's back. 0 sends everyone at once.",
            min = 0, max = 1000,
        )
    }

    /** `join-command:` */
    public class JoinCommand(parent: ConfigGroup) : ConfigSection(
        parent, "join-command",
        "/join: sends you back. Everyone has it by default. Its messages are join-command.* in lang/.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val aliases: ConfigKey<List<String>> = stringList("aliases", listOf("server", "lobby"))
        public val permission: ConfigKey<String> = key(
            "permission", ConfigType.STRING, "xlimbo.join", "Blank (\"\") lets anyone.",
            check = { if (it.isEmpty() || NODE.matches(it)) null else "isn't a permission node, such as xlimbo.join" },
        )
        public val cooldownSeconds: ConfigKey<Int> = int(
            "cooldown-seconds", 3, "Seconds between uses, per player, so nobody can spam your proxy.", min = 0, max = 3600,
        )

        private companion object {
            val NODE = Regex("[a-z0-9_-]+(\\.[a-z0-9_*-]+)*")
        }
    }

    /** `action-bar:` */
    public class ActionBar(parent: ConfigGroup) : ConfigSection(
        parent, "action-bar", "A hint repeated on every player's action bar: action-bar.hint in lang/.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val intervalTicks: ConfigKey<Int> = int("interval-ticks", 40, "Ticks between updates (20 is one second).", min = 1, max = 1200)
    }

    /** `protections:` */
    public class Protections(parent: ConfigGroup) : ConfigSection(
        parent, "protections",
        "Players here are in creative, so these stop them breaking, lagging or escaping the",
        "server. Each can be turned off; one that's off costs nothing.",
    ) {
        public val floor: ConfigKey<Boolean> = boolean("floor", true, "The floor can't be broken, pushed, or built under.")
        public val voidProtection: Void = Void(this)
        public val portals: ConfigKey<Boolean> = boolean("portals", true, "Nether and End portals, and Eyes of Ender in End portal frames.")
        public val liquids: ConfigKey<Boolean> = boolean("liquids", true, "Placing water and lava, and liquids flowing.")
        public val gravityBlocks: ConfigKey<Boolean> = boolean("gravity-blocks", true, "Sand, gravel, anvils, concrete powder and other blocks that fall.")
        public val fire: ConfigKey<Boolean> = boolean("fire", true, "Fire spreading, burning and being lit.")
        public val explosions: ConfigKey<Boolean> = boolean("explosions", true, "Every explosion.")
        public val mobSpawns: ConfigKey<Boolean> = boolean("mob-spawns", true, "Mobs spawning.")
        public val redstone: ConfigKey<Boolean> = boolean("redstone", true, "Redstone, pistons and dispensers.")
        public val itemDrops: ConfigKey<Boolean> = boolean("item-drops", true, "Dropped items.")
        public val entities: Entities = Entities(this)
        public val buildGuard: BuildGuard = BuildGuard(this)
    }

    /** `protections.void:` */
    public class Void(parent: ConfigGroup) : ConfigSection(parent, "void") {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true, "Put players who fall below threshold-y back on the floor.")
        public val thresholdY: ConfigKey<Int> = int("threshold-y", 0, min = -2048, max = 2048)
        public val cancelVoidDamage: ConfigKey<Boolean> = boolean("cancel-void-damage", true, "No void or fall damage, so a rescue never becomes a death loop.")
    }

    /** `protections.entities:` */
    public class Entities(parent: ConfigGroup) : ConfigSection(parent, "entities") {
        public val blockEntityItems: ConfigKey<Boolean> = boolean(
            "block-entity-items", true, "Armour stands, boats, minecarts, item frames, paintings, End crystals and spawn eggs.",
        )
        public val cleanupIntervalSeconds: ConfigKey<Int> = int(
            "cleanup-interval-seconds", 300,
            "Removes stray entities this often: everything but players and what builds are made of",
            "(armour stands, item frames, paintings, displays, interactions, markers), and anything",
            "tagged xlimbo.keep. 0 turns it off.",
            min = 0, max = 86_400,
        )
    }

    /** `protections.build-guard:` */
    public class BuildGuard(parent: ConfigGroup) : ConfigSection(parent, "build-guard") {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false, "Limits how fast each player can place blocks, against lag machines.")
        public val maxPlacesPerSecond: ConfigKey<Int> = int("max-places-per-second", 30, min = 1, max = 1000)
    }
}
