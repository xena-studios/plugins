package co.xenastudios.gradle

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
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
 * - every entry point in `fabric.mod.json` (`main`, `server`, `client` and the rest), and
 *   `velocity-plugin.json`'s `main`;
 * - every service in `META-INF/services/`, and the interface it's for;
 * - the core's api types the plugin's own API exposes ([exposedCoreApi]), which keep their names
 *   and members like the API itself (common.pro keeps the API but not the relocated core's);
 * - `-repackageclasses` into [packageName]: Bukkit's `PluginClassLoader` looks classes up in a
 *   cache every plugin shares, so two jars must never both have `a.a`.
 *
 * **Mixins** (a Fabric mod's, in the packages of the mixin configs its `fabric.mod.json`
 * lists) never go through R8: Mixin reads their annotations, which R8 would drop, and their
 * names and members, which it would change. They're left out of R8's input, given to it as
 * class path so the code that names them (an accessor) still resolves, and copied into the
 * shrunk jar as they are; whatever of ours they call keeps its name and members.
 *
 * R8 carries the jar's other files over; Maven's pom files and Kotlin's `.kotlin_builtins` and
 * `.kotlin_module` files (read only by kotlin-reflect and the compiler) are then dropped. R8's
 * marker (a ~190-byte `~~R8{...}` string in every class's constant pool, which nothing reads at
 * runtime) is emptied, and the jar is written at the strongest deflate level. The mapping file
 * stays with the build, for stack traces (`retrace`).
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
        val original = inputJar.singleFile
        val mixinPackages = mixinPackages(original)
        val input = if (mixinPackages.isEmpty()) original else File(temporaryDir, original.name).also { split(original, mixinPackages, it) }
        val mixins = File(temporaryDir, "mixins.jar")
        val generated = generatedRules.get().asFile
        generated.parentFile.mkdirs()
        generated.writeText(entryPointRules(input) + exposedCoreApi(input) + mixinRules(original, mixinPackages) + "-repackageclasses ${packageName.get()}\n")
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
        libraries.files.filter { it.exists() && it != original }.forEach { args += listOf("--lib", it.path) }
        if (mixinPackages.isNotEmpty()) args += listOf("--classpath", mixins.path)
        args += input.path
        exec.javaexec {
            executable = launcher.get().executablePath.asFile.path
            classpath = r8
            mainClass.set("com.android.tools.r8.R8")
            args(args)
        }
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        copyWithout(classes, out, startupList(classes), if (mixinPackages.isEmpty()) null else mixins)
        logger.lifecycle("%s: %.1f KiB -> %.1f KiB".format(out.name, original.length() / 1024.0, out.length() / 1024.0))
    }

    /** The packages of the mixin configs the jar's `fabric.mod.json` lists, as paths (`co/xenastudios/x/core/fabric/mixin/`). */
    private fun mixinPackages(jar: File): List<String> = ZipFile(jar).use { zip ->
        fun text(name: String) = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().decodeToString() } }
        val mod = text("fabric.mod.json") ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val configs = ((JsonSlurper().parseText(mod) as Map<String, Any?>)["mixins"] as? List<Any?>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("config") ?: it }
        configs.mapNotNull { config ->
            val json = text(config.toString()) ?: throw GradleException("fabric.mod.json lists the mixin config $config, which the jar doesn't have")
            @Suppress("UNCHECKED_CAST")
            ((JsonSlurper().parseText(json) as Map<String, Any?>)["package"] as? String)?.replace('.', '/')?.plus("/")
        }
    }

    // The jar's mixin classes into mixins.jar, the rest into [rest].
    private fun split(jar: File, packages: List<String>, rest: File) {
        val mixins = File(temporaryDir, "mixins.jar")
        ZipFile(jar).use { zip ->
            ZipOutputStream(rest.outputStream().buffered()).use { others ->
                ZipOutputStream(mixins.outputStream().buffered()).use { ours ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        val out = if (entry.name.endsWith(".class") && packages.any { entry.name.startsWith(it) }) ours else others
                        out.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    /** Keep rules for the jar's own classes the mixins call, read from each mixin's constant pool. */
    private fun mixinRules(jar: File, packages: List<String>): String {
        if (packages.isEmpty()) return ""
        val classRef = Regex("""co/xenastudios/[\w/$]+""")
        return ZipFile(jar).use { zip ->
            val classes = zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }.toHashSet()
            val called = sortedSetOf<String>()
            for (name in classes) {
                if (packages.none { name.startsWith(it) }) continue
                val bytes = zip.getInputStream(zip.getEntry("$name.class")).use { it.readBytes() }
                classRef.findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }
                    .filter { it in classes && packages.none { prefix -> it.startsWith(prefix) } }
                    .forEach { called += it }
            }
            called.joinToString("") { "# called by the mixins\n-keep class ${it.replace('/', '.')} { *; }\n" }
        }
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

            text("velocity-plugin.json")?.let { json ->
                @Suppress("UNCHECKED_CAST")
                val main = (JsonSlurper().parseText(json) as Map<String, Any?>)["main"]
                if (main != null) append("# velocity-plugin.json main\n-keep class $main { <init>(...); }\n")
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
     * Keep rules for the relocated core's api types (`co.xenastudios.<plugin>.core.api`) that the
     * plugin's own API (`api` packages outside the core, kept by common.pro) names, and those
     * these name in turn: another plugin calling the API sees them, so they keep their names and
     * members. Read from each class's constant pool, so a type the API only uses inside a method
     * is kept too, which costs a little and never breaks a caller.
     */
    private fun exposedCoreApi(jar: File): String {
        val classRef = Regex("""co/xenastudios/[\w/$]+""")
        val isApi = Regex("""(^|/)api/""")
        ZipFile(jar).use { zip ->
            val classes = zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }
                .map { it.removeSuffix(".class") }.toHashSet()
            fun refs(name: String): List<String> {
                val bytes = zip.getInputStream(zip.getEntry("$name.class")).use { it.readBytes() }
                return classRef.findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }.filter { it in classes }.toList()
            }
            val ownApi = classes.filter { isApi.containsMatchIn(it) && "/core/" !in it }
            val exposed = sortedSetOf<String>()
            val queue = ArrayDeque(ownApi.flatMap(::refs))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if ("/core/" !in name || !isApi.containsMatchIn(name.substringAfter("/core/")) || !exposed.add(name)) continue
                queue += refs(name)
            }
            return exposed.joinToString("") { "# exposed by the plugin's API\n-keep public class ${it.replace('/', '.')} { public protected *; }\n" }
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

    private fun copyWithout(from: File, to: File, startup: String?, mixins: File? = null) {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val mixinZip = mixins?.let(::ZipFile)
        mixinZip.use { _ -> ZipFile(from).use { zip ->
            ZipOutputStream(to.outputStream().buffered()).use { out ->
                out.setLevel(Deflater.BEST_COMPRESSION)
                val entries = zip.entries().asSequence().map { it.name }.filter { it != STARTUP_CLASSES }.toMutableList()
                if (startup != null) entries += STARTUP_CLASSES
                val mixinNames = mixinZip?.entries()?.asSequence()?.map { it.name }?.toHashSet().orEmpty()
                entries += mixinNames
                // The shipped translations in one entry, so what every language repeats (the keys,
                // the tags) compresses once: core-jvm's LangFolder.shipped reads either form.
                val languages = entries.filter { LANG.matches(it) }.sorted()
                val bundle = if (languages.size < 2) null else buildString {
                    for (name in languages) {
                        val text = zip.getInputStream(zip.getEntry(name)).use { String(it.readBytes(), Charsets.UTF_8) }
                        append(BUNDLE_MARK).append(name.removePrefix("lang/").removeSuffix(".yml")).append('\n')
                        append(text)
                        if (!text.endsWith("\n")) append('\n')
                    }
                }
                if (bundle != null) {
                    entries -= languages.toSet()
                    entries += LANG_BUNDLE
                }
                for (name in entries.sorted()) {
                    if (name.startsWith("META-INF/maven/") || name.endsWith(".kotlin_builtins") || name.endsWith(".kotlin_module")) continue
                    var bytes = when {
                        name == STARTUP_CLASSES && startup != null -> startup.toByteArray()
                        name == LANG_BUNDLE && bundle != null -> bundle.toByteArray(Charsets.UTF_8)
                        name in mixinNames -> mixinZip!!.getInputStream(mixinZip.getEntry(name)).use { it.readBytes() }
                        else -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                    }
                    if (name.endsWith(".class") && name !in mixinNames) bytes = withoutMarker(bytes)
                    // Sizes and CRC up front, so no data descriptor follows each entry (16 bytes
                    // each); fixed times, so the same input gives the same jar.
                    val entry = ZipEntry(name).apply {
                        time = FIXED_TIME
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                        compressedSize = deflatedSize(deflater, bytes)
                    }
                    out.putNextEntry(entry)
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        } }
        deflater.end()
    }

    private fun deflatedSize(deflater: Deflater, bytes: ByteArray): Long {
        deflater.reset()
        deflater.setInput(bytes)
        deflater.finish()
        val buffer = ByteArray(8192)
        var size = 0L
        while (!deflater.finished()) size += deflater.deflate(buffer)
        return size
    }

    companion object {
        /** Where the startup list goes in the jar, which the shim reads (core-loader's BukkitBootstrap). */
        const val STARTUP_CLASSES = "META-INF/xena/startup-classes.txt"

        /** Every shipped `lang/<tag>.yml` of a jar, one after another, each after a `@@ <tag>` line (core-jvm's LangFolder). */
        const val LANG_BUNDLE = "lang/shipped.txt"
        private const val BUNDLE_MARK = "@@ "
        private val LANG = Regex("lang/[A-Za-z0-9-]+\\.yml")

        /**
         * [bytes] with R8's marker emptied: R8 writes it as constant #1 of every class, a
         * `~~R8{"backend":"cf",...}` string nothing reads. Emptied rather than removed, so no
         * index in the class changes.
         */
        internal fun withoutMarker(bytes: ByteArray): ByteArray {
            if (bytes.size < 17 || bytes[10] != 1.toByte()) return bytes
            val length = ((bytes[11].toInt() and 0xff) shl 8) or (bytes[12].toInt() and 0xff)
            if (String(bytes, 13, minOf(4, length), Charsets.ISO_8859_1) != "~~R8") return bytes
            return bytes.copyOfRange(0, 11) + byteArrayOf(0, 0) + bytes.copyOfRange(13 + length, bytes.size)
        }

        // 1980-02-01, as Gradle's reproducible archives use.
        const val FIXED_TIME = 318_211_200_000L
    }
}
