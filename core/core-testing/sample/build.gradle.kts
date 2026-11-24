plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest (part of check) and updateXenaManifest.
    id("xena.manifest")
}

group = "co.xenastudios.core"
version = "1.0.0"
description = "xSample: a plugin on the rewrite's core, never released, that proves its commands and reference manifest."

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
    }
}

// Its manifest is committed to docs/src/generated/xsample/, which the docs validate but no
// page shows. The platforms are an example of the table each plugin's build will have.
xenaReference {
    pluginId = "xsample"
    source = "co.xenastudios.core.sample.SampleReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "1.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "1.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "1.0.0")
    platform("velocity", "Velocity", "3.3", java = 21, status = "planned", kind = "proxy")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
}
