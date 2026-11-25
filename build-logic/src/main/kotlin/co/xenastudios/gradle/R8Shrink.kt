package co.xenastudios.gradle

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * Shrinks a plugin jar with R8 (`--classfile`, full mode), as M0 did for the spike jars
 * (`sandbox/m0/budgets`). Applied by the xena.r8 convention, which says what goes in.
 *
 * The keep rules are `xena/r8/common.pro` (in build-logic's resources: stack traces, enums,
 * type tokens, the public API, the loader's entry points), the project's own [rules], and
 * rules this task writes from the jar itself, so a plugin never repeats what its files say:
 * - `plugin.yml`'s and `paper-plugin.yml`'s `main` (and Paper's `bootstrapper` and `loader`),
 *   which the server makes by name;
 * - every entry point in `fabric.mod.json` (`main`, `server`, `client` and the rest);
 * - every service in `META-INF/services/`, and the interface it's for;
 * - `-repackageclasses` into [packageName]: Bukkit's `PluginClassLoader` looks classes up in a
 *   cache every plugin shares, so two jars must never both have `a.a`.
 *
 * R8 carries the jar's other files over; Maven's pom files and Kotlin's `.kotlin_builtins`
 * (read only by kotlin-reflect) are then dropped. The mapping file stays with the build, for
 * stack traces (`retrace`).
 *
 * [startupClasses], when given, is the list of classes the plugin loads as it starts, in their
 * own names (recorded on a real server by `scripts/budgets/check.sh --record-startup`): it goes
 * into the jar as [STARTUP_CLASSES] in the names R8 gave them, for the shim to load on a thread
 * of its own before the server enables the plugin. A class R8 removed, or that the jar no longer
 * has, is left out; the list is a hint, so a missing class only loads when it's first used.
 */
@CacheableTask
abstract class R8Shrink @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    /** R8 itself. */
    @get:Classpath
    abstract val r8: ConfigurableFileCollection

    /** The jar to shrink: the plugin as it would ship, with whatever it shades. */
    @get:Classpath
    abstract val inputJar: ConfigurableFileCollection

    /** What's there at runtime but not in the jar: the server's API, the fetched stdlib. */
    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    /** The project's own `.pro` files, after the shared ones. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rules: ConfigurableFileCollection

    /** The package renamed classes go to, of the plugin's own. */
    @get:Input
    abstract val packageName: Property<String>

    /** The JDK that runs R8, and its class library is R8's for `java.*`. */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:OutputFile
    abstract val mapping: RegularFileProperty

    /** The classes the plugin loads as it starts, one a line, in their own names: see the class's comment. */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val startupClasses: ConfigurableFileCollection

    /** The rules this task wrote from the jar, kept to read when a rule is missing. */
    @get:OutputFile
    abstract val generatedRules: RegularFileProperty

    @TaskAction
    fun shrink() {
        val input = inputJar.singleFile
        val generated = generatedRules.get().asFile
        generated.parentFile.mkdirs()
        generated.writeText(entryPointRules(input) + "-repackageclasses ${packageName.get()}\n")
        val common = File(temporaryDir, "common.pro")
        common.writeText(
            R8Shrink::class.java.getResourceAsStream("/xena/r8/common.pro")?.use { it.readBytes().decodeToString() }
                ?: throw GradleException("build-logic has no xena/r8/common.pro"),
        )
        val classes = File(temporaryDir, "r8-out.jar").also { it.delete() }
        val args = mutableListOf(
            "--classfile", "--release",
            "--output", classes.path,
            "--pg-map-output", mapping.get().asFile.path,
            "--lib", launcher.get().metadata.installationPath.asFile.path,
            "--pg-conf", common.path,
        )
        rules.files.sorted().forEach { args += listOf("--pg-conf", it.path) }
        args += listOf("--pg-conf", generated.path)
        libraries.files.filter { it.exists() && it != input }.forEach { args += listOf("--lib", it.path) }
        args += input.path
        exec.javaexec {
            executable = launcher.get().executablePath.asFile.path
            classpath = r8
            mainClass.set("com.android.tools.r8.R8")
            args(args)
        }
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        copyWithout(classes, out, startupList(classes))
        logger.lifecycle("%s: %.1f KiB -> %.1f KiB".format(out.name, input.length() / 1024.0, out.length() / 1024.0))
    }

    /** Keep rules for what the jar's own files name. */
    private fun entryPointRules(jar: File): String = buildString {
        ZipFile(jar).use { zip ->
            fun text(name: String) = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().decodeToString() } }

            for (file in listOf("plugin.yml", "paper-plugin.yml", "bungee.yml")) {
                val yml = text(file) ?: continue
                for (key in listOf("main", "bootstrapper", "loader")) {
                    val match = Regex("""(?m)^$key:\s*['"]?([\w.$]+)""").find(yml) ?: continue
                    append("# $file $key\n-keep class ${match.groupValues[1]} { <init>(...); }\n")
                }
            }

            text("fabric.mod.json")?.let { json ->
                @Suppress("UNCHECKED_CAST")
                val entrypoints = (JsonSlurper().parseText(json) as Map<String, Any?>)["entrypoints"] as? Map<String, Any?>
                entrypoints?.forEach { (kind, list) ->
                    for (item in list as? List<*> ?: emptyList<Any?>()) {
                        val value = (item as? Map<*, *>)?.get("value") ?: item
                        val owner = value.toString().substringBefore("::")
                        append("# fabric.mod.json $kind\n-keep class $owner { *; }\n")
                    }
                }
            }

            zip.entries().asSequence().filter { it.name.startsWith("META-INF/services/") && !it.isDirectory }.forEach { entry ->
                val service = entry.name.removePrefix("META-INF/services/")
                append("# service $service\n-keep,allowshrinking interface $service\n")
                zip.getInputStream(entry).use { it.readBytes().decodeToString() }.lineSequence()
                    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                    .forEach { append("-keep class $it { <init>(); }\n") }
            }
        }
    }

    /**
     * [startupClasses] in the shrunk jar's names, or `null` when there's no list. Classes of the
     * jar's own (`co.xenastudios.`) must be in the shrunk jar; the rest are the libraries', the
     * stdlib's, which R8 never sees, so they keep their names.
     */
    private fun startupList(shrunk: File): String? {
        val listed = startupClasses.files.filter { it.isFile }
        if (listed.isEmpty()) return null
        val renamed = HashMap<String, String>()
        mapping.get().asFile.forEachLine { line ->
            if (!line.startsWith(" ") && !line.startsWith("#") && line.endsWith(":")) {
                val (from, to) = line.removeSuffix(":").split(" -> ").takeIf { it.size == 2 } ?: return@forEachLine
                renamed[from] = to
            }
        }
        val present = ZipFile(shrunk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }.toHashSet()
        }
        val names = listed.flatMap { it.readLines() }.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .mapNotNull { name ->
                val now = renamed[name] ?: name
                when {
                    now in present -> now
                    name.startsWith("co.xenastudios.") -> null
                    else -> now
                }
            }
            .distinct()
        logger.info("${outputJar.get().asFile.name}: ${names.size} startup classes")
        return names.joinToString("\n", postfix = "\n")
    }

    private fun copyWithout(from: File, to: File, startup: String?) {
        ZipFile(from).use { zip ->
            ZipOutputStream(to.outputStream().buffered()).use { out ->
                val entries = zip.entries().asSequence().map { it.name }.filter { it != STARTUP_CLASSES }.toMutableList()
                if (startup != null) entries += STARTUP_CLASSES
                for (name in entries.sorted()) {
                    if (name.startsWith("META-INF/maven/") || name.endsWith(".kotlin_builtins")) continue
                    // Fixed times, so the same input gives the same jar.
                    out.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                    if (name == STARTUP_CLASSES && startup != null) {
                        out.write(startup.toByteArray())
                    } else {
                        zip.getInputStream(zip.getEntry(name)).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    companion object {
        /** Where the startup list goes in the jar, which the shim reads (core-loader's BukkitBootstrap). */
        const val STARTUP_CLASSES = "META-INF/xena/startup-classes.txt"

        // 1980-02-01, as Gradle's reproducible archives use.
        const val FIXED_TIME = 318_211_200_000L
    }
}
