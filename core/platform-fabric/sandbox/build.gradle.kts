import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xSample as a Fabric mod, one jar per Minecraft line like the adapter beside it (its node for
// the same version), never released: what CI boots on Fabric (scripts/boot-fabric.sh), and
// what `runServer` runs.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    // The YAML parser downloaded at startup rather than carried (FabricLibraries), pinned in
    // the repo's gradle/library-pins.txt, and the JDBC drivers listed for the driver fetcher.
    id("xena.fetched-libraries")
    // The mod's classes shrunk by R8 before Loom remaps them, against the jar's line in
    // gradle/jar-budgets.txt.
    id("xena.r8")
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
    // R8, only on Google's repository.
    google {
        content { includeModule("com.android.tools", "r8") }
    }
}

// What goes inside the mod's jar, as classes: the adapter, the core and xSample. A plugin
// carries its own core, as on Bukkit; Fabric's jar-in-jar would keep only the newest copy
// among every mod that has one. The Kotlin stdlib stays out: fabric-language-kotlin has it.
// So does the YAML parser, with okio and urlencoder: they're downloaded (fetched, below).
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

xenaR8 {
    // xSample's own package, as on Bukkit; the default from this node's path (:sandbox:26.3)
    // isn't a package name.
    packageName = "co.xenastudios.xsample.r8"
    // Mojang's names: the jar is shrunk before Loom remaps it, against Minecraft, Fabric's
    // and the stdlib's named jars, and the libraries downloaded at runtime. Not what's bundled.
    libraries.setFrom(configurations.compileClasspath.map { it.minus(bundleClasspath.get()) }, configurations.named("fetchedJars"))
    rules.setFrom(fileTree("../../r8") { include("*.pro") })
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

    // The shrunk jar is the one remapped, below 26.1, and the one shipped on 26.1 and later.
    matching { it.name == "remapJar" }.configureEach {
        (this as net.fabricmc.loom.task.RemapJarTask).inputFile.set(named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar })
    }

    // The mod's jar for this version in build/libs of the build, for the boot script: shrunk,
    // then remapped below 26.1.
    register<Copy>("collect") {
        group = "build"
        description = "Copies this version's mod jar to the build's build/libs."
        if (sc.current.parsed >= "26.1") {
            from(named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar })
        } else {
            from(loomx.modJar.flatMap { it.archiveFile })
        }
        into(rootProject.layout.buildDirectory.dir("libs"))
    }
}
