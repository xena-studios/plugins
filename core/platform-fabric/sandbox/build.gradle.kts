import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xSample as a Fabric mod, one jar per Minecraft line like the adapter beside it (its node for
// the same version), never released: what CI boots on Fabric (scripts/boot-fabric.sh), and
// what `runServer` runs.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    // How every Xena mod is packaged (build-logic): the core bundled and relocated to xSample's
    // package, shrunk by R8 against the jar's line in gradle/jar-budgets.txt, then remapped by
    // Loom below 26.1; the YAML parser downloaded at startup (FabricLibraries) and the JDBC
    // drivers listed for the driver fetcher, pinned in gradle/library-pins.txt.
    id("xena.fabric-mod")
}

xenaFabricMod {
    id = "xsample"
}

version = "1.0.0+${sc.current.version}"
base.archivesName = "xsample-fabric"

// As the adapter: Minecraft's Java decides the bytecode.
val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    // All of Fabric API, as servers have it: the mod depends on `fabric-api`, which only the
    // whole of it provides, so runServer needs it too.
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"]}")
    // Without the Fabric APIs these two name, for newer Minecraft (see ../build.gradle.kts).
    modImplementation("net.fabricmc:fabric-language-kotlin:${sc.properties["deps.flk"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    // Inside the jar, as every mod using it carries it: Fabric loads the newest copy.
    modImplementation("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    include("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}")

    // The adapter for this version, in Mojang's names: the jar is remapped as a whole.
    val adapter = project(":${sc.current.project}", configuration = "namedElements")
    implementation(adapter)
    // Named here too: the adapter's own dependencies don't come with its named jar.
    implementation("co.xenastudios.core:core-jvm")
    implementation("co.xenastudios.core:sample")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios.core:sample")
    "bundle"("co.xenastudios.core:core-jvm")

    // Downloaded into config/xena/libraries/ as the mod starts (the adapter's FabricLibraries),
    // as the Bukkit jar's libraries are: 870 KiB the jar doesn't carry.
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
    // The JDBC drivers the core fetches when a database needs one (FabricPlatform.drivers).
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
}

java {
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

kotlin {
    explicitApi()
    jvmToolchain(requiredJava.majorVersion.toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(requiredJava.majorVersion)
        allWarningsAsErrors = true
    }
}

loom {
    runConfigs.all {
        // One server folder per version, so worlds from different Minecraft versions never meet.
        runDirectory = file("run")
    }
}

tasks {
    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "minecraft" to sc.properties["mod.mc_compat"],
            "loader" to sc.properties["mod.loader_compat"],
            "flk" to sc.properties["deps.flk"].substringBefore('+'),
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
