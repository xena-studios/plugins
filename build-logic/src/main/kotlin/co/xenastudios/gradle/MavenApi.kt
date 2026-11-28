package co.xenastudios.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import java.io.File
import java.util.zip.ZipFile

/**
 * A rewritten plugin's API as Maven Central gets it (xena.release-api): its classes are the ones
 * the shipped jar has, so what a caller compiles against is exactly what links at runtime, the
 * core's api types relocated into the plugin's package as the jar has them
 * (decisions/2026-11-24-xutilities-3-the-pilot.md).
 */
object MavenApi {
    /** `co.xenastudios.core.` in source, as each plugin's jar relocates it. */
    fun relocate(source: String, id: String): String =
        source.replace("co.xenastudios.core.", "co.xenastudios.$id.core.")

    private val expectDeclaration = Regex("""(?:/\*\*(?:(?!\*/)[\s\S])*\*/\n)?(?:@[\w.]+\n)*public expect [^\n]*\n\n?""")

    /**
     * The JVM's view of a multiplatform source file, for the docs: a common file loses its
     * `expect` declarations when the JVM's file has their `actual`s, and those lose the word
     * `actual`. Anything still `expect` or `actual` after that is an error: the docs would be wrong.
     */
    fun jvmView(common: String?, jvm: String?): String? {
        val withoutExpects = common?.let { if (jvm != null) expectDeclaration.replace(it, "") else it }
        val actuals = jvm?.replace(Regex("""\bactual """), "")
        return when {
            withoutExpects == null -> actuals
            actuals == null -> withoutExpects
            // Both: one file, the JVM's declarations first, then the common file's other helpers.
            else -> {
                val (jvmHead, jvmImports, jvmBody) = split(actuals)
                val (_, commonImports, commonBody) = split(withoutExpects)
                listOf(jvmHead, (jvmImports + commonImports).distinct().joinToString("\n"), jvmBody.trimEnd(), commonBody.trim())
                    .filter { it.isNotBlank() }.joinToString("\n\n") + "\n"
            }
        }?.also {
            if (Regex("""\b(expect|actual) (open |abstract )?(class|fun|val|var|typealias|interface|object)\b""").containsMatchIn(it)) {
                throw GradleException("A multiplatform declaration is left in the API docs' sources:\n$it")
            }
        }
    }

    private val declaration = Regex("""^(?:public )?(?:(?:data|enum|sealed|abstract|open|fun|value|annotation|inline) )*(?:class|interface|object|typealias) (\w+)""", RegexOption.MULTILINE)
    private val jvmName = Regex("""@file:JvmName\("(\w+)"\)""")

    /** The file class a Kotlin file names with `@file:JvmName`, if it does. */
    fun fileClasses(source: String): Set<String> = jvmName.findAll(source).map { it.groupValues[1] }.toSet()

    /** The top-level types a Kotlin file declares, and its file class when it names one. */
    fun declaredTypes(source: String): Set<String> =
        (declaration.findAll(source).map { it.groupValues[1] } + jvmName.findAll(source).map { it.groupValues[1] }).toSet()

    /**
     * Hides a file's top-level functions and properties from Dokka (`@suppress`), for a file whose
     * file class the jar doesn't have: a caller couldn't reach them.
     */
    fun suppressTopLevel(source: String): String {
        val lines = source.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            if (Regex("""^public (?:(?:inline|actual|operator|infix) )*(?:fun|val|var) """).containsMatchIn(lines[i])) {
                // Up past its annotations, to its KDoc if it has one.
                var j = i
                while (j > 0 && lines[j - 1].startsWith("@")) j--
                val kdocEnd = j - 1
                if (kdocEnd >= 0 && lines[kdocEnd].trimEnd().endsWith("*/")) {
                    val end = lines[kdocEnd].trimEnd()
                    if (end.trimStart().startsWith("/**")) {
                        // A one-line KDoc: a block tag needs a line of its own.
                        lines[kdocEnd] = "/**\n * " + end.trimStart().removePrefix("/**").removeSuffix("*/").trim() + "\n * @suppress\n */"
                    } else {
                        lines.add(kdocEnd, " * @suppress")
                        i++
                    }
                } else {
                    lines.add(j, "/** @suppress */")
                    i++
                }
            }
            i++
        }
        return lines.joinToString("\n")
    }

    /** A Kotlin file's package line (and any annotations above it), its imports, and the rest. */
    private fun split(source: String): Triple<String, List<String>, String> {
        val lines = source.lines()
        val pkg = lines.indexOfFirst { it.startsWith("package ") }
        val rest = lines.drop(pkg + 1)
        val imports = rest.takeWhile { it.isBlank() || it.startsWith("import ") }
        return Triple(lines.take(pkg + 1).joinToString("\n"), imports.filter { it.isNotBlank() }, rest.drop(imports.size).joinToString("\n"))
    }

    private val descriptorType = Regex("""L([\w/$]+)[;<]""")

    /**
     * Every class a class file names: its constant pool's class entries, and every type in its
     * descriptors and signatures (which also covers Kotlin's metadata strings).
     */
    fun referencedClasses(bytes: ByteArray): Set<String> {
        val reader = ClassReader(bytes)
        val buffer = CharArray(reader.maxStringLength)
        val names = mutableSetOf(reader.className)
        for (i in 1 until reader.itemCount) {
            val offset = reader.getItem(i)
            if (offset == 0) continue // the second slot of a long or double
            // CONSTANT_Class: its name, or an array's element type.
            if (bytes[offset - 1].toInt() == 7) {
                reader.readUTF8(offset, buffer)?.let { if (!it.startsWith("[")) names += it else descriptorType.findAll(it).forEach { m -> names += m.groupValues[1] } }
            }
        }
        // UTF8 entries: read each by its offset (tag 1 at offset - 1, length at offset).
        for (i in 1 until reader.itemCount) {
            val offset = reader.getItem(i)
            if (offset == 0 || bytes[offset - 1].toInt() != 1) continue
            val length = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
            val text = String(bytes, offset + 2, length, Charsets.UTF_8)
            descriptorType.findAll(text).forEach { names += it.groupValues[1] }
        }
        return names
    }

    /**
     * The plugin's classes ([ownPrefix], such as `co/xenastudios/xbans/`) that the API jar's classes
     * name but that aren't in it or in [alsoAvailable]: a caller compiling against the jar would
     * miss them.
     */
    fun missing(jar: File, ownPrefix: String, alsoAvailable: Set<String> = emptySet()): Map<String, Set<String>> = ZipFile(jar).use { zip ->
        val classes = zip.entries().asSequence().filter { it.name.endsWith(".class") }.toList()
        val present = classes.map { it.name.removeSuffix(".class") }.toSet() + alsoAvailable
        classes.associate { entry ->
            val refs = referencedClasses(zip.getInputStream(entry).readBytes())
            entry.name.removeSuffix(".class") to refs.filter { it.startsWith(ownPrefix) && it !in present }.toSet()
        }.filterValues { it.isNotEmpty() }
    }

    fun classesIn(jar: File): Set<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name.removeSuffix(".class") }.toSet()
    }

    /**
     * What Maven Central requires of a bundle (central.sonatype.org/publish/requirements): for each
     * artifact, a POM with name, description, url, a license with its url, a developer and scm, a
     * jar with sources and javadoc jars beside it, an md5 and sha1 of every file, and (once signed)
     * an .asc of each, and no SNAPSHOT version.
     */
    fun bundleProblems(repo: File, signed: Boolean): List<String> {
        val poms = repo.walkTopDown().filter { it.isFile && it.name.endsWith(".pom") }.toList()
        if (poms.isEmpty()) return listOf("$repo has no POM")
        val problems = mutableListOf<String>()
        for (pom in poms) {
            val base = pom.name.removeSuffix(".pom")
            val text = pom.readText()
            val where = pom.relativeTo(repo).path
            if ("SNAPSHOT" in base) problems += "$where: a SNAPSHOT can't go to Maven Central"
            for (tag in listOf("name", "description", "url")) {
                if (!Regex("<$tag>[^<]+</$tag>").containsMatchIn(text)) problems += "$where: no <$tag>"
            }
            if (!Regex("<license>[\\s\\S]*<name>[^<]+</name>[\\s\\S]*<url>[^<]+</url>[\\s\\S]*</license>").containsMatchIn(text)) problems += "$where: no <license> with a name and url"
            if (!Regex("<developer>[\\s\\S]*<name>[^<]+</name>[\\s\\S]*</developer>").containsMatchIn(text)) problems += "$where: no <developer> with a name"
            if (!Regex("<scm>[\\s\\S]*<url>[^<]+</url>[\\s\\S]*</scm>").containsMatchIn(text)) problems += "$where: no <scm> with a url"
            val files = listOf("$base.pom", "$base.jar", "$base-sources.jar", "$base-javadoc.jar")
            for (name in files) {
                val file = pom.resolveSibling(name)
                if (!file.isFile) {
                    problems += "${file.relativeTo(repo).path} is missing"
                    continue
                }
                for (ext in listOfNotNull("md5", "sha1", "asc".takeIf { signed })) {
                    if (!pom.resolveSibling("$name.$ext").isFile) problems += "${file.relativeTo(repo).path}.$ext is missing"
                }
            }
        }
        return problems
    }
}

/**
 * The JVM's view of an API's sources, relocated as the jar is: the plugin's API (common and JVM
 * code) and the core-api it exposes. The sources jar and Dokka both read it.
 */
@CacheableTask
abstract class ApiSources : DefaultTask() {
    @get:Input
    abstract val pluginId: Property<String>

    /** Kotlin source roots, common first then JVM, each pair given as `<common>|<jvm>`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val roots: ConfigurableFileCollection

    /** The common and JVM source roots, in pairs (common, jvm), either may be missing. */
    @get:Input
    abstract val pairs: ListProperty<String>

    /** Only these packages (paths under the root, such as `co/xenastudios/xbans/api/bukkit`), if any are given. */
    @get:Input
    abstract val only: ListProperty<String>

    /**
     * The API jar, when given: a core file is left out unless something it declares is in the jar
     * or named by it, so the docs never show a core type the shipped jar shrank away.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiJar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val jarNames = apiJar.files.filter { it.isFile }.flatMap { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }
                    .flatMap { MavenApi.referencedClasses(zip.getInputStream(it).readBytes()) }.toList()
            }
        }.map { it.substringAfterLast('/').substringBefore('$') }.toSet()
        val jarClasses = apiJar.files.filter { it.isFile }.flatMap { MavenApi.classesIn(it) }.map { it.substringAfterLast('/') }.toSet()
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val id = pluginId.get()
        for (pair in pairs.get()) {
            val (common, jvm) = pair.split('|').map { File(it) }
            fun files(root: File) = if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .associateBy { it.relativeTo(root).invariantSeparatorsPath } else emptyMap()
            val commons = files(common)
            // Pending.jvm.kt is Pending.kt's JVM side.
            val jvms = files(jvm).mapKeys { (path, _) -> path.replace(Regex("""\.jvm\.kt$"""), ".kt") }
            for (path in (commons.keys + jvms.keys).sorted()) {
                if (only.get().isNotEmpty() && only.get().none { path.startsWith("$it/") }) continue
                val text = MavenApi.jvmView(commons[path]?.readText(), jvms[path]?.readText()) ?: continue
                var view = text
                if (jarNames.isNotEmpty() && path.startsWith("co/xenastudios/core/")) {
                    if (MavenApi.declaredTypes(text).none { it in jarNames }) continue
                    val base = path.substringAfterLast('/').removeSuffix(".kt")
                    val facades = setOf("${base}Kt", "${base}_jvmKt") + MavenApi.fileClasses(text)
                    if (facades.none { it in jarClasses }) view = MavenApi.suppressTopLevel(text)
                }
                val target = out.resolve(path.replace("co/xenastudios/core/", "co/xenastudios/$id/core/"))
                target.parentFile.mkdirs()
                target.writeText(MavenApi.relocate(view, id))
            }
        }
    }
}

/** Fails when an API jar names a class of the plugin's that a caller compiling against it wouldn't have. */
@CacheableTask
abstract class ApiJarCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** The plugin's package as a path, `co/xenastudios/xbans/`. */
    @get:Input
    abstract val ownPrefix: Property<String>

    /**
     * The unrelocated JVM jars the API is built from (core-api's, the plugin api's): a name the
     * API jar mentions only counts as missing if it's a class there. Kotlin's metadata also names
     * typealiases (core-api's Pending, which is CompletableFuture on the JVM), which have no class.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceJars: ConfigurableFileCollection

    /** Jars the artifact depends on (the Bukkit helpers' own API jar). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencies: ConfigurableFileCollection

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val available = dependencies.files.flatMap { MavenApi.classesIn(it) }.toSet()
        val prefix = ownPrefix.get()
        val real = sourceJars.files.filter { it.isFile }.flatMap { MavenApi.classesIn(it) }
            .map { it.replace("co/xenastudios/core/", "${prefix}core/") }.toSet()
        val missing = MavenApi.missing(jar.get().asFile, prefix, available)
            .mapValues { (_, names) -> names.filter { it in real }.toSet() }.filterValues { it.isNotEmpty() }
        if (missing.isNotEmpty()) {
            throw GradleException("${jar.get().asFile.name} names classes that aren't in it, so a caller couldn't compile against it " +
                "(keep them in the shipped jar, xena/r8/common.pro):\n  " + missing.entries.joinToString("\n  ") { "${it.key}: ${it.value.sorted()}" })
        }
        val classes = MavenApi.classesIn(jar.get().asFile)
        if (classes.any { it.endsWith("/core/api/PlayerRef") } && classes.none { it.endsWith("/core/api/PlayerRefs") }) {
            throw GradleException("${jar.get().asFile.name} has PlayerRef but not PlayerRefs, the Java helpers the API pages point to (xena/r8/common.pro)")
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}

/**
 * Checks the release's Maven Central bundle (`<release>/maven`, written by releaseMavenBundle)
 * against Central's requirements, and says what would be uploaded. It never uploads: release.yml's
 * maven job signs the bundle with gpg and sends it to the Central Portal, so neither the signing
 * key nor the Portal's token ever reaches a build script.
 */
@DisableCachingByDefault(because = "Reports on the release folder")
abstract class MavenCentralCheck : DefaultTask() {
    @get:Internal
    abstract val releaseDir: DirectoryProperty

    @TaskAction
    fun check() {
        val dir = releaseDir.get().asFile
        val repo = dir.resolve("maven")
        if (!repo.isDirectory) throw GradleException("$repo is missing: run releaseMavenBundle after releaseAssemble")
        StoreChecks.require("The Maven Central bundle in $repo", MavenApi.bundleProblems(repo, signed = false))
        val report = buildString {
            appendLine("Maven Central: POST https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC")
            appendLine("  Authorization: <secret> (MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD, a Portal user token)")
            appendLine("  bundle: every file below, each signed with SIGNING_KEY by release.yml's maven job (.asc)")
            repo.walkTopDown().filter { it.isFile && it.extension in setOf("pom", "jar") }.sortedBy { it.path }.forEach {
                appendLine("    ${it.relativeTo(repo).invariantSeparatorsPath} (${it.length()} bytes)")
            }
            appendLine("  checked: Central's requirements (POM name, description, url, license, developer, scm; sources and javadoc jars; md5 and sha1)")
        }
        DryRun.report(dir, "maven-central", report, logger)
    }
}
