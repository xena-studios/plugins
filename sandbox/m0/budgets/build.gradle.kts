// R8 over the M0 spike jars: Hello and HelloCloud (sandbox/m0/bukkit, stdlib fetched, so the
// stdlib and Cloud are library classes here) and the Sponge plugin (sandbox/m0/mods/sponge,
// stdlib and cloud-core shaded and relocated, so they are shrunk with it).
//
//   ./gradlew shrink -Pspike.jars=<folder with Hello.jar, HelloCloud.jar, m0-sponge-0.0.1.jar>
//
// Writes build/r8/<name>.jar and its mapping file; rules in r8/. R8 carries the jar's other files
// over (plugin.yml, libraries.txt, sponge_plugins.json); Maven's pom files and Kotlin's
// .kotlin_builtins (read only by kotlin-reflect) are then dropped.
plugins { base }

val r8 by configurations.creating
val bukkitLib by configurations.creating { isTransitive = false }
val cloudLib by configurations.creating
val spongeLib by configurations.creating
// Plain runtime jars, as a Java project would pick (some of Sponge's libraries also publish a shadowed variant).
listOf(bukkitLib, cloudLib, spongeLib).forEach {
    it.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    r8("com.android.tools:r8:9.5.33")
    bukkitLib("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    bukkitLib("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    bukkitLib("org.jetbrains:annotations:13.0")
    cloudLib("org.incendo:cloud-paper:2.0.1")
    cloudLib("net.kyori:adventure-text-minimessage:4.26.1")
    cloudLib("net.kyori:adventure-platform-bukkit:4.4.1")
    cloudLib("net.md-5:bungeecord-chat:1.16-R0.4") { isTransitive = false }
    spongeLib("org.spongepowered:spongeapi:17.0.0")
}

val jars = providers.gradleProperty("spike.jars").map { file(it) }
// The JDK running Gradle (17+) runs R8 and is its library for java.*.
val javaHome = System.getProperty("java.home")

// Renamed classes go to a package of each plugin's own: Bukkit's PluginClassLoader looks classes up
// in a cache shared by every plugin, so two jars must never both have, say, `a.a`.
fun shrink(name: String, input: String, rules: String, pkg: String, libs: FileCollection) =
    tasks.register<JavaExec>("r8$name") {
        group = "m0"
        val inJar = jars.map { it.resolve(input) }
        val classes = layout.buildDirectory.file("r8/$name-r8.jar")
        val packageRule = layout.buildDirectory.file("r8/$name-package.pro")
        val out = layout.buildDirectory.file("r8/$name.jar")
        inputs.file(inJar); inputs.file("r8/$rules"); inputs.file("r8/common.pro"); inputs.files(libs)
        outputs.files(classes, out)
        classpath = r8
        mainClass = "com.android.tools.r8.R8"
        doFirst { packageRule.get().asFile.writeText("-repackageclasses $pkg\n") }
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--classfile", "--output", classes.get().asFile.path,
                "--pg-conf", file("r8/common.pro").path, "--pg-conf", file("r8/$rules").path,
                "--pg-conf", packageRule.get().asFile.path,
                "--pg-map-output", layout.buildDirectory.file("r8/$name.map").get().asFile.path,
                "--lib", javaHome) +
                libs.files.flatMap { listOf("--lib", it.path) } + inJar.get().path
        })
        doLast {
            val target = out.get().asFile
            target.delete()
            ant.withGroovyBuilder {
                "zip"("destfile" to target) {
                    "zipfileset"("src" to classes.get().asFile, "excludes" to "META-INF/maven/**,**/*.kotlin_builtins")
                }
            }
            val before = inJar.get().length()
            println("$name: ${before / 1024} KB -> ${target.length() / 1024} KB")
        }
    }

val hello = shrink("Hello", "Hello.jar", "bukkit.pro", "co.xenastudios.m0.hello.r8", bukkitLib)
val helloCloud = shrink("HelloCloud", "HelloCloud.jar", "bukkit.pro", "co.xenastudios.m0.hellocloud.r8", bukkitLib + cloudLib)
val sponge = shrink("Sponge", "m0-sponge-0.0.1.jar", "sponge.pro", "co.xenastudios.spike.sponge.r8", spongeLib)
tasks.register("shrink") { group = "m0"; dependsOn(hello, helloCloud, sponge) }
