// Never released. A plugin.yml plugin written against Vault's and VaultUnlocked's APIs, as
// shops and jobs plugins are, that calls them the ways such plugins do: from other threads,
// for players who never joined, with NaN and negative amounts. The Boot workflow boots it with
// xMoney 2.0 on every Bukkit-family server, so it's Java 8 against Spigot 1.8.8's API, the floor.
plugins {
    id("xena.java8")
}

dependencies {
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(libs.vaultunlocked.api) { isTransitive = false }
}

// Vault 1's API is deprecated in favour of VaultUnlocked 2's; calling it is the point here.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-deprecation")
}
