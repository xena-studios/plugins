plugins {
    id("xena.plugin")
}

version = "0.0.0"
description = "Boots the core on a real server in CI. Never released."

paperPluginYaml {
    name = "Sandbox"
    main = "co.xenastudios.sandbox.SandboxPlugin"
}
