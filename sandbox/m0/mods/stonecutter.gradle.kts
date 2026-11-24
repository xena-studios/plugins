plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3"

stonecutter parameters {
    swaps["minecraft"] = "\"${current.version}\""
    // adventure-platform-fabric moved to a multi-loader package in 6.0 (Minecraft 1.21).
    constants["modcommon"] = current.parsed >= "1.21"
}
