plugins {
    id("dev.kikugie.stonecutter")
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
}
