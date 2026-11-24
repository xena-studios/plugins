plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    // Only for the /api/<plugin>/ check in RESULTS.md: ./gradlew :core:dokkaGenerateHtml
    id("org.jetbrains.dokka") version "2.3.0" apply false
}
