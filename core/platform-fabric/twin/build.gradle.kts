import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xTwin as a Fabric mod, never released: the family test's second mod, booted beside xSample
// (scripts/boot-fabric.sh with ../../core-testing/twin/family-checks.txt). Built like the sandbox
// beside it, then its core relocated to xTwin's own package. Fabric loads every mod in one class
// loader, so two mods carrying the core under the same names would share one copy, and the test
// would prove nothing; relocated, each has its own and they meet only on the family's board.
//
// Not shrunk with R8, unlike the sandbox: Shadow relocates its core after Loom's remap, and R8
// would have to run between the two. Its jar has no budget line; it's a test fixture, never
// shipped. The YAML parser is downloaded as the sandbox's is (FabricLibraries), so the family
// test also checks two mods sharing the one copy on the game's class path.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("com.gradleup.shadow") apply false
    id("xena.fetched-libraries")
}

version = "1.0.0+${sc.current.version}"
base.archivesName = "xtwin-fabric-unrelocated"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

// As the sandbox: the adapter, the core and xTwin inside the jar, as classes; the stdlib from
// fabric-language-kotlin.
val bundle = configurations.dependencyScope("bundle")
val bundleClasspath = configurations.resolvable("bundleClasspath") {
    extendsFrom(bundle.get())
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "it.krzeminski")
    exclude(group = "com.squareup.okio")
    exclude(group = "net.thauvin.erik.urlencoder")
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"]}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${sc.properties["deps.flk"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    modImplementation("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    include("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}")

    val adapter = project(":${sc.current.project}", configuration = "namedElements")
    implementation(adapter)
    implementation("co.xenastudios.core:core-jvm")
    implementation("co.xenastudios.core:twin")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios.core:twin")
    "bundle"("co.xenastudios.core:core-jvm")

    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
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

    jar {
        from(bundleClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // The mod's jar (remapped below 26.1) with the core moved to xTwin's packages, as each
    // Bukkit plugin's is. Minecraft's, Fabric's, Kotlin's and the YAML parser's names stay.
    val relocatedJar = register<ShadowJar>("relocatedJar") {
        group = "build"
        description = "The mod's jar with its own copy of the core, relocated."
        archiveBaseName = "xtwin-fabric"
        archiveVersion = project.version.toString()
        archiveClassifier = ""
        destinationDirectory = layout.buildDirectory.dir("relocated")
        from(loomx.modJar.map { zipTree(it.archiveFile) })
        relocate("co.xenastudios.core", "co.xenastudios.xtwin.core")
    }

    register<Copy>("collect") {
        group = "build"
        description = "Copies this version's mod jar to the build's build/libs."
        from(relocatedJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs"))
    }
}
