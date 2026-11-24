plugins {
    id("dev.kikugie.stonecutter")
    // The root build's conventions the sandbox applies, loaded once here: they bring the Kotlin
    // Gradle plugin with them, which every node must share rather than load again.
    id("xena.r8") apply false
    id("xena.fetched-libraries") apply false
    id("xena.fabric-mod") apply false
}

// The version the source's comments are switched to for the IDE. Committed as the newest, so
// a checkout reads as today's Minecraft.
stonecutter active "26.3"

stonecutter parameters {
    // Minecraft 1.21.5 made click and hover events records, one class per action.
    constants["click_records"] = current.parsed >= "1.21.5"
    // 1.21.11 renamed ResourceLocation to Identifier, and ResourceKey.location() to identifier().
    constants["identifier"] = current.parsed >= "1.21.11"
    // 1.21.11 replaced operator levels with permission sets on a command source.
    constants["permission_sets"] = current.parsed >= "1.21.11"
    // 1.20.2 moved a player's client options, the language among them, into ClientInformation.
    constants["client_information"] = current.parsed >= "1.20.2"
    // 1.20.5 made a mob effect a registry holder on its instance.
    constants["effect_holders"] = current.parsed >= "1.20.5"
    // 1.21.2 gave teleportTo a set of relative parts, and a player's level() its server type.
    constants["modern_teleport"] = current.parsed >= "1.21.2"
}
