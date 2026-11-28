plugins {
    id("dev.kikugie.stonecutter")
    // The root build's conventions the sandbox applies, loaded once here: they bring the Kotlin
    // Gradle plugin with them, which every node must share rather than load again.
    id("xena.r8") apply false
    id("xena.fetched-libraries") apply false
    id("xena.neoforge-mod") apply false
    // NeoForge's own Gradle plugin, loaded once here for every node.
    id("net.neoforged.moddev") version "2.0.149" apply false
}

// The version the source's comments are switched to for the IDE. Committed as the newest, so
// a checkout reads as today's Minecraft.
stonecutter active "26.3"

stonecutter parameters {
    // Forge 1.20.1's node (settings.gradle.kts): NeoForge's names, as the source has them, become
    // Forge's. Longest first, so a package's own rule wins over the shorter one around it. The
    // source is always NeoForge's: don't make the Forge node the active one.
    val forge = current.project.endsWith("-forge")
    constants["forge"] = forge
    replacements {
        string(forge) {
            replace("net.neoforged.neoforge.common.NeoForge", "net.minecraftforge.common.MinecraftForge")
        }
        string(forge) {
            replace("NeoForge.EVENT_BUS", "MinecraftForge.EVENT_BUS")
        }
        string(forge) {
            replace("net.neoforged.neoforge.", "net.minecraftforge.")
        }
        string(forge) {
            replace("net.neoforged.fml.", "net.minecraftforge.fml.")
        }
        string(forge) {
            replace("net.neoforged.bus.api.", "net.minecraftforge.eventbus.api.")
        }
    }
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
    // The Common Economy API's amounts became BigIntegers in 2.0, its build for 26.1 on (xMoney).
    constants["economy_bigint"] = current.parsed >= "26.1"
    // 1.20.5 moved ChunkStatus to its own package, and a chunk future's result into ChunkResult (xTeleport).
    constants["chunk_result"] = current.parsed >= "1.20.5"
    // 1.21.9 kept a world's spawn and a player's respawn point as RespawnData (xTeleport).
    constants["respawn_data"] = current.parsed >= "1.21.9"
    // 1.20.5 kept an item's name, lore and the rest as data components, not NBT (the menus).
    constants["item_components"] = current.parsed >= "1.20.5"
    // 26.1 renamed a menu click's ClickType to ContainerInput (the menus).
    constants["container_input"] = current.parsed >= "26.1"
    // 26.1 gave a player's drop and the like a Prediction (the menus).
    constants["item_prediction"] = current.parsed >= "26.1"
    // 1.21.5 made the player's selected hotbar slot a getter, not a field (xMarket).
    constants["selected_slot"] = current.parsed >= "1.21.5"
    // 1.21.2 split the server's explosions into ServerExplosion, which destroys blocks in explode() (the world hooks).
    constants["server_explosion"] = current.parsed >= "1.21.2"
    // By 1.21.11 thrown eggs moved to projectile.throwableitemprojectile and horses to animal.equine (the world hooks).
    constants["projectile_packages"] = current.parsed >= "1.21.11"
    constants["equine"] = current.parsed >= "1.21.11"
    // 26.1 sends action-bar text with sendOverlayMessage, not displayClientMessage (xTeleport).
    constants["overlay_message"] = current.parsed >= "26.1"
    // 1.21.6 read and wrote block entities through ValueInput and ValueOutput, not CompoundTags (xRewind).
    constants["value_io"] = current.parsed >= "1.21.6"
    // 1.21.2 made a registry its own lookup, with no asLookup() (xRewind).
    constants["registry_lookup"] = current.parsed >= "1.21.2"
    // NeoForge 21.9 (FML's own loader) turned FMLEnvironment's fields into methods (platform-neoforge).
    // 1.21.5 parsed a whole compound tag with parseCompoundFully, parseTag before (the menus, on 1.21.1).
    constants["tag_parser_fully"] = current.parsed >= "1.21.5"
    constants["fml_environment_methods"] = current.parsed >= "1.21.9"
}
