package co.xenastudios.core.common.config

import kotlin.time.Duration.Companion.seconds

enum class Storage { SQLITE, MYSQL, FLAT_FILE }

/** A config like a plugin's, with every kind of setting, at config version 2. */
object HomesConfig : ConfigSchema(2, "xHomes", "Docs: https://plugins.xenastudios.co/xhomes/") {
    val language = string("language", "auto", "The language players see: auto, or a code like en.")
    val storage = enum("storage", Storage.SQLITE, Storage.entries, "Where homes are kept.")
    val homes: Homes = Homes(this)
    val messages: Messages = Messages(this)

    class Homes(parent: ConfigGroup) : ConfigSection(parent, "homes", "Setting and going to homes.") {
        val limit = int("limit", 3, "How many homes a player can set.", min = 0, max = 100)
        val delay = duration("teleport-delay", 90.seconds, "How long a player stands still first.")
        val enabled = boolean("enabled", true)
        val cost = double("cost", 2.5, min = 0.0)
        val blockedWorlds = stringList("blocked-worlds", listOf("world_nether"), "Worlds homes can't be set in.")
        val slots = list("slots", ConfigType.INT, listOf(1, 2, 3))
    }

    class Messages(parent: ConfigGroup) : ConfigSection(parent, "messages") {
        val welcome = markup("welcome", "<green>Welcome home, <player>!", formerDefaults = listOf("<gray>Welcome home, <player>."))
        val prefix = string("prefix", "", "Put before every message. Empty for none.")
    }

    init {
        migrate(1) {
            move("home-limit", "homes.limit")
            move("delay", "homes.teleport-delay")
        }
    }
}
