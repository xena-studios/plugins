package co.xenastudios.gradle

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The SHA-256 of every library a plugin downloads at runtime, committed in
 * `gradle/library-pins.txt` as `group:artifact:version sha256` lines. The build compares the
 * jars Gradle resolves with them, so a version bump (by Renovate or by hand) fails until
 * someone has updated the pin, which is a reviewed line in the diff: the hash a server checks
 * is never just whatever the build happened to download.
 */
internal object LibraryPins {

    /** A resolved jar: its Maven coordinates and its SHA-256. */
    data class Jar(val coordinates: String, val sha256: String)

    /** `group:artifact:version` to SHA-256, from the pins file's text. */
    fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .associate { line ->
            val parts = line.split(Regex("\\s+"))
            require(parts.size == 2 && parts[0].count { it == ':' } == 2 && SHA256.matches(parts[1])) {
                "not a \"group:artifact:version sha256\" line: $line"
            }
            parts[0] to parts[1]
        }

    /** What's wrong with the jars against the pins, one line each; empty when every jar matches. */
    fun problems(jars: List<Jar>, pins: Map<String, String>): List<String> = jars.mapNotNull { jar ->
        when (val pinned = pins[jar.coordinates]) {
            null -> "${jar.coordinates} has no SHA-256 pinned (it resolves to ${jar.sha256})"
            jar.sha256 -> null
            else -> "${jar.coordinates} resolves to ${jar.sha256}, but its pin is $pinned"
        }
    }

    /** The pins file with the jars' hashes added or replaced, sorted, keeping the header comments. */
    fun updated(text: String, jars: List<Jar>): String {
        val header = text.lineSequence().takeWhile { it.startsWith("#") || it.isBlank() }.toList()
            .dropLastWhile { it.isBlank() }
        val pins = parse(text).toMutableMap()
        jars.forEach { pins[it.coordinates] = it.sha256 }
        return buildString {
            header.forEach { append(it).append('\n') }
            if (header.isNotEmpty()) append('\n')
            pins.toSortedMap().forEach { (coordinates, sha) -> append(coordinates).append(' ').append(sha).append('\n') }
        }
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A class of the jar's own, which the loader asks the server's class loaders for: when it's
     * already visible (the server bundles the library, or `libraries:` fetched it), the jar
     * isn't downloaded. The shortest top-level class name, so it's stable across builds.
     */
    fun probe(jar: File): String = ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter {
                it.endsWith(".class") && !it.startsWith("META-INF/") && '$' !in it &&
                    !it.endsWith("module-info.class") && !it.endsWith("package-info.class")
            }
            .minWithOrNull(compareBy<String> { it.length }.thenBy { it })
            ?.removeSuffix(".class")?.replace('/', '.')
            ?: error("${jar.name} has no classes to check for")
    }

    /** The generated resource the loader reads: one `coordinates sha256 probe` line per jar. */
    fun manifest(jars: List<Pair<Jar, String>>): String = buildString {
        append("# The libraries this plugin downloads where the server doesn't provide them, written by the build\n")
        append("# (PinnedLibraries) and read by co.xenastudios.core.loader.Manifest: coordinates, SHA-256, probe class.\n")
        append("xena-libraries 1\n")
        jars.sortedBy { it.first.coordinates }.forEach { (jar, probe) ->
            append(jar.coordinates).append(' ').append(jar.sha256).append(' ').append(probe).append('\n')
        }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
