import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xSample as a Fabric mod, one jar per Minecraft line like the adapter beside it (its node for
// the same version), never released: what CI boots on Fabric (scripts/boot-fabric.sh), and
// what `runServer` runs.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
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

// What goes inside the mod's jar, as classes: the adapter, the core and xSample. A plugin
// carries its own core, as on Bukkit; Fabric's jar-in-jar would keep only the newest copy
// among every mod that has one. The Kotlin stdlib stays out: fabric-language-kotlin has it.
val bundle = configurations.dependencyScope("bundle")
val bundleClasspath = configurations.resolvable("bundleClasspath") {
    extendsFrom(bundle.get())
    exclude(group = "org.jetbrains.kotlin")
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

    jar {
        from(bundleClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
        // Each library's own manifest and module files; ours win.
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // The mod's jar for this version in build/libs of the build, for the boot script: remapped
    // below 26.1, the plain jar on 26.1 and later.
    register<Copy>("collect") {
        group = "build"
        description = "Copies this version's mod jar to the build's build/libs."
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs"))
    }
}
