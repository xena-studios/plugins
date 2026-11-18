// Never released. A plugin.yml plugin written against Vault's and VaultUnlocked's APIs, as
// shops and jobs plugins are, that calls them the ways such plugins do: from other threads,
// for players who never joined, with NaN and negative amounts. CI boots it with xMoney.
plugins {
    id("xena.java")
}

dependencies {
    compileOnly(libs.vaultunlocked.api) { isTransitive = false }
}
