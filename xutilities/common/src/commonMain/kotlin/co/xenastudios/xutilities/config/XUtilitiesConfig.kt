package co.xenastudios.xutilities.config

import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.permission.PermissionSet

/**
 * xUtilities' `config.yml`, version 3. Every setting 2.x had keeps its path, so a 2.x config
 * carries forward with nothing but its messages moved (to `lang/`) and its theme moved into
 * `messages.theme`, and the 2.1.0 jar put back reads the commands' settings as they were.
 */
public object XUtilitiesConfig : ConfigSchema(
    3,
    "xUtilities: every command you need. None you don't.",
    "Docs: https://plugins.xenastudios.co/xutilities/configuration/",
    "",
    "Apply changes with /xutilities reload (or /xu reload). Everything applies, even turning",
    "commands on or off. A bad value falls back to its default with a warning in the console;",
    "xUtilities never stops over config. The messages are in lang/: one file per language.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val commands: Commands = Commands(this)

    /** The link commands an owner defines, by name. */
    public val infoCommands: ConfigKey<Map<String, LinkCommand>> = key(
        "info-commands", LinkCommand.TYPE, LinkCommand.DEFAULTS,
        "Commands you write yourself. Each key becomes a command that sends its message, usually",
        "a clickable link. Add one, such as vote:, and reload to get /vote. permission is optional:",
        "it defaults to xutilities.command.<name>, which everyone has. A name or alias that clashes",
        "with one of the commands above is skipped. The message is in the same markup as lang/.",
        "Replace the example links!",
    )

    public val updateChecker: UpdateChecker = UpdateChecker(this)

    /** Every command's section, in file order. */
    public val all: List<CommandSection> get() = commands.all

    init {
        // 1.x had an error for disabled commands; a disabled command doesn't exist.
        migrate(1) {
            remove("messages.error.disabled")
            Legacy.refreshLinks(this)
        }
        // 3.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(2) { Legacy.toThree(this) }
    }

    /** `commands:`, one section per command. */
    public class Commands(parent: ConfigGroup) : ConfigSection(
        parent, "commands",
        "Every command has the same settings:",
        "  enabled            false removes the command entirely",
        "  aliases            other names for it",
        "  permission         needed to use it; blank (\"\") lets anyone",
        "  permission-others  needed to use it on another player, on commands with a [player] form",
        "When one player changes another, the changed player gets the -self message too.",
        "The messages are in lang/<language>.yml.",
    ) {
        public val survival: CommandSection = CommandSection(this, "survival", listOf("gms", "gm0"), others = true, comment = arrayOf("/survival [player]"))
        public val creative: CommandSection = CommandSection(this, "creative", listOf("gmc", "gm1"), others = true, comment = arrayOf("/creative [player]"))
        public val adventure: CommandSection = CommandSection(this, "adventure", listOf("gma", "gm2"), others = true, comment = arrayOf("/adventure [player]"))
        public val spectator: CommandSection = CommandSection(this, "spectator", listOf("gmsp", "gm3"), others = true, comment = arrayOf("/spectator [player]"))
        public val gamemode: CommandSection = CommandSection(
            this, "gamemode", listOf("gm"), others = true,
            comment = arrayOf(
                "/gamemode <mode> [player]. Offers the modes whose own command is on, and needs",
                "that command's permission too. <mode> can be a name, a number or an alias such as gmc.",
            ),
        )
        public val fly: CommandSection = CommandSection(
            this, "fly", listOf("flight", "togglefly", "toggleflymode"), others = true,
            comment = arrayOf("/fly [player]: turns flight on or off."),
        )
        public val speed: Speed = Speed(this)
        public val top: CommandSection = CommandSection(
            this, "top", emptyList(), others = false,
            comment = arrayOf("/top: teleports you to the highest block above you, or in the Nether, the highest below the roof."),
        )
        public val setrotation: CommandSection = CommandSection(
            this, "setrotation", emptyList(), others = true,
            comment = arrayOf("/setrotation <yaw> <pitch> [player]: sets which way a player faces."),
        )
        public val ping: CommandSection = CommandSection(
            this, "ping", emptyList(), others = true,
            comment = arrayOf("/ping [player]: network latency in milliseconds. Everyone has it by default."),
        )
        public val back: CommandSection = CommandSection(
            this, "back", emptyList(), others = false,
            comment = arrayOf("/back: returns you to where you were before your last teleport, or where you died."),
        )
        public val heal: Heal = Heal(this)
        public val feed: CommandSection = CommandSection(this, "feed", emptyList(), others = true, comment = arrayOf("/feed [player]: restores hunger."))
        public val repair: CommandSection = CommandSection(
            this, "repair", listOf("fix"), others = true,
            comment = arrayOf("/repair [all] [player]: repairs the held item, or with all, the whole inventory."),
        )
        public val clear: Clear = Clear(this)
        public val enderchest: SoundCommand = SoundCommand(
            this, "enderchest", listOf("ec", "ender"), others = true, sound = Sound.parse("block.ender_chest.open 0.5"),
            comment = arrayOf("/enderchest [player]: opens your Ender Chest, or another player's. sound plays as it opens."),
        )
        public val anvil: SoundCommand = station(
            "anvil", emptyList(),
            "Workstation menus: open the real menu without the block. sound is played when it opens;",
            "leave it blank for none. A sound is its Minecraft name, optionally followed by a volume",
            "(0 to 10) and pitch (0.5 to 2): \"block.anvil.use 0.5 1.2\". Where a server can't open a",
            "menu (an anvil on Spigot before 1.21, say), the command says so.",
        )
        public val craft: SoundCommand = station("craft", listOf("workbench", "wb"))
        public val grinder: SoundCommand = station("grinder", listOf("grindstone"))
        public val loom: SoundCommand = station("loom", emptyList())
        public val smith: SoundCommand = station("smith", listOf("smithingtable", "smithtable"))
        public val stonecutter: SoundCommand = station("stonecutter", emptyList())
        public val cartography: SoundCommand = station("cartography", listOf("cartographytable"))
        public val trash: SoundCommand = SoundCommand(
            this, "trash", listOf("disposal"), others = false, sound = Sound.parse("block.barrel.open 0.5"),
            comment = arrayOf("/trash: an empty menu whose contents are deleted when it closes."),
        )

        /** Every command's section, in file order. */
        public val all: List<CommandSection> = listOf(
            survival, creative, adventure, spectator, gamemode, fly, speed, top, setrotation, ping, back,
            heal, feed, repair, clear, enderchest, anvil, craft, grinder, loom, smith, stonecutter, cartography, trash,
        )

        private fun station(name: String, aliases: List<String>, vararg comment: String): SoundCommand =
            SoundCommand(this, name, aliases, others = false, sound = Sound.NONE, comment = comment)
    }

    /** `/speed`'s section: its bounds. */
    public class Speed(parent: ConfigGroup) : CommandSection(
        parent, "speed", listOf("setspeed"), others = true,
        comment = arrayOf(
            "/speed <speed> [player]: fly speed while flying, walk speed otherwise. min and max bound",
            "what it accepts, on a 1 to 10 scale (Minecraft's 0.1 to 1.0; walking is 2 by default).",
        ),
    ) {
        public val min: ConfigKey<Int> = int("min", 1, min = 1, max = 10)
        public val max: ConfigKey<Int> = int("max", 10, min = 1, max = 10)
    }

    /** `/heal`'s section: what it restores besides health. */
    public class Heal(parent: ConfigGroup) : CommandSection(
        parent, "heal", emptyList(), others = true,
        comment = arrayOf("/heal [player]: restores health, and these extras."),
    ) {
        public val restoreHunger: ConfigKey<Boolean> = boolean("restore-hunger", true)
        public val extinguishFire: ConfigKey<Boolean> = boolean("extinguish-fire", true)
        public val clearNegativeEffects: ConfigKey<Boolean> = boolean("clear-negative-effects", true)
    }

    /** `/clear`'s section. */
    public class Clear(parent: ConfigGroup) : CommandSection(
        parent, "clear", listOf("clearinventory", "ci"), others = true,
        comment = arrayOf("/clear [player]: empties the inventory. include-armor: false keeps armour and the off-hand."),
    ) {
        public val includeArmor: ConfigKey<Boolean> = boolean("include-armor", true)
    }

    /** `update-checker:`. */
    public class UpdateChecker(parent: ConfigGroup) : ConfigSection(parent, "update-checker", "Tells you when a new version is out.") {
        // TODO(#core-m2-fixes): the core's update checker brings its own section and message;
        // until it lands this switch is read but nothing checks.
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", true,
            "Ask Modrinth for the newest version at startup and every 24 hours. It sends only the",
            "plugin version, the platform and the Minecraft version.",
        )
    }
}

/**
 * One command's settings: whether it's on, its aliases, its permission and, for a command with a
 * `[player]` form, the permission to use it on someone else.
 */
public open class CommandSection(
    parent: ConfigGroup,
    name: String,
    defaultAliases: List<String>,
    /** Whether it has a `[player]` form, and so `permission-others`. */
    public val others: Boolean,
    vararg comment: String,
) : ConfigSection(parent, name, *comment) {
    /** The command's name, which can't change: its aliases can. */
    public val command: String get() = name

    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", ALIAS, defaultAliases)
    public val permission: ConfigKey<String> = key("permission", PERMISSION, "xutilities.command.$name")
    public val permissionOthers: ConfigKey<String>? =
        if (others) key("permission-others", PERMISSION, "xutilities.command.$name.others") else null

    public companion object {
        /** A permission node, or blank for anyone. Read in any case, as Bukkit does. */
        public val PERMISSION: ConfigType<String> = ConfigType.scalar("permission", { it }) { raw ->
            val node = raw.trim().lowercase()
            if (node.isEmpty() || PermissionSet.isNode(node)) {
                co.xenastudios.core.common.Outcome.Ok(node)
            } else {
                co.xenastudios.core.common.Outcome.Err("isn't a permission node (words of letters, digits, - and _ joined with .), or blank for anyone")
            }
        }

        /** A command's other name: one word. */
        public val ALIAS: ConfigType<String> = ConfigType.scalar("alias", { it }) { raw ->
            val word = raw.trim().lowercase().removePrefix("/")
            if (WORD.matches(word)) co.xenastudios.core.common.Outcome.Ok(word)
            else co.xenastudios.core.common.Outcome.Err("isn't a command name (letters, digits, - and _)")
        }

        internal val WORD = Regex("[a-z0-9][a-z0-9_-]*")
    }
}

/** A command's section with the sound it plays as its menu opens. */
public class SoundCommand(
    parent: ConfigGroup,
    name: String,
    defaultAliases: List<String>,
    others: Boolean,
    sound: Sound,
    vararg comment: String,
) : CommandSection(parent, name, defaultAliases, others, *comment) {
    public val sound: ConfigKey<Sound> = key("sound", Sound.TYPE, sound)
}

/**
 * A sound as the config writes it: its Minecraft name, then optionally a volume (0 to 10) and
 * a pitch (0.5 to 2), `"block.anvil.use 0.5 1.2"`. Blank is none.
 */
public class Sound(public val name: String, public val volume: Float = 1f, public val pitch: Float = 1f) {
    /** Whether it plays nothing. */
    public val isNone: Boolean get() = name.isEmpty()

    override fun equals(other: Any?): Boolean = other is Sound && other.name == name && other.volume == volume && other.pitch == pitch
    override fun hashCode(): Int = (name.hashCode() * 31 + volume.hashCode()) * 31 + pitch.hashCode()

    /** As the config writes it. */
    override fun toString(): String = when {
        isNone -> ""
        pitch != 1f -> "$name ${number(volume)} ${number(pitch)}"
        volume != 1f -> "$name ${number(volume)}"
        else -> name
    }

    public companion object {
        /** No sound. */
        public val NONE: Sound = Sound("")

        private val NAME = Regex("[a-z0-9_.:/-]+")

        /** A sound as the config writes it, or [NONE] if blank. Throws on one that can't be read: for defaults. */
        public fun parse(text: String): Sound = when (val read = read(text)) {
            is co.xenastudios.core.common.Outcome.Ok -> read.value
            is co.xenastudios.core.common.Outcome.Err -> throw IllegalArgumentException("\"$text\" ${read.error}")
        }

        internal fun read(text: String): co.xenastudios.core.common.Outcome<Sound, String> {
            val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return co.xenastudios.core.common.Outcome.Ok(NONE)
            if (parts.size > 3) return co.xenastudios.core.common.Outcome.Err("should be a sound's name, then optionally a volume and a pitch")
            val name = parts[0].lowercase()
            if (!NAME.matches(name)) return co.xenastudios.core.common.Outcome.Err("should start with a sound's name, such as block.anvil.use")
            val volume = parts.getOrNull(1)?.let { it.toFloatOrNull()?.takeIf { v -> v in 0f..10f } ?: return co.xenastudios.core.common.Outcome.Err("has a volume that isn't from 0 to 10") } ?: 1f
            val pitch = parts.getOrNull(2)?.let { it.toFloatOrNull()?.takeIf { p -> p in 0.5f..2f } ?: return co.xenastudios.core.common.Outcome.Err("has a pitch that isn't from 0.5 to 2") } ?: 1f
            return co.xenastudios.core.common.Outcome.Ok(Sound(name, volume, pitch))
        }

        /** How a sound reads and writes in the config. */
        public val TYPE: ConfigType<Sound> = object : ConfigType<Sound>() {
            override val name: String get() = "sound"
            override fun empty(): Sound = NONE
            override fun decode(node: YamlNode, context: co.xenastudios.core.common.config.ConfigContext): co.xenastudios.core.common.Outcome<Sound, String> =
                if (node is YamlNode.Scalar) read(node.content) else co.xenastudios.core.common.Outcome.Err("should be a single value")
            override fun encode(value: Sound): YamlNode = YamlNode.Scalar(value.toString())
        }

        private fun number(value: Float): String =
            if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()
    }
}
