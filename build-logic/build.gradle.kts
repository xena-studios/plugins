plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(plugin(libs.plugins.shadow))
    implementation(plugin(libs.plugins.resource.factory))
    implementation(plugin(libs.plugins.run.paper))
    implementation(plugin(libs.plugins.minotaur))
    implementation(plugin(libs.plugins.hangar.publish))
    implementation(plugin(libs.plugins.maven.publish))
    // The rewrite's Kotlin conventions (xena.kmp, xena.kotlin-jvm, xena.kmp-api).
    implementation(libs.kotlin.gradle.plugin)
    // Reads each API's class files for its signature check.
    implementation(libs.asm.tree)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Lets a precompiled script plugin apply a plugin from the version catalog.
fun plugin(plugin: Provider<PluginDependency>) =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
