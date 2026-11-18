plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "{{tagline}}"

paperPluginYaml {
    name = "{{display}}"
    main = "co.xenastudios.{{id}}.{{Class}}"
}

xena {
    // The shared core and bStats are about 75 KiB; the rest is {{display}}'s own code. Raise
    // this as the plugin grows, with a line on what the room is for.
    jarBudgetKb = 150
}
