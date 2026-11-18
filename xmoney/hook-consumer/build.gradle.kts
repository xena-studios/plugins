// Never released. A Paper plugin that charges players through the core's Vault hook, as
// xPlots and xTeleport do, so CI checks the hook works with xMoney standing in for Vault.
plugins {
    id("xena.java")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))
}

tasks.shadowJar {
    archiveClassifier = ""
    relocate("co.xenastudios.core", "co.xenastudios.xmoney.hookconsumer.core")
    relocate("org.bstats", "co.xenastudios.xmoney.hookconsumer.bstats")
    minimize()
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
