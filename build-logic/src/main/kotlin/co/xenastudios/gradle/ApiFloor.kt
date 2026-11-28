package co.xenastudios.gradle

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Fails when a shrunk Bukkit jar calls a server API member its floor doesn't have, unless the
 * plugin's `api-floor.txt` lists it as one it calls only after checking the server has it.
 *
 * Each Bukkit adapter's main code compiles against its floor's API, so the compiler stops a
 * plain call to something newer. R8 doesn't: it shrinks against the newest API, and may rewrite
 * a call the floor has into one it hasn't, as it did with `(type as? Keyed)?.key` (#238: R8,
 * knowing every EntityType is Keyed, called EntityType.getKey(), which 1.13 lacks). The JVM
 * links a call when it first runs, so nothing fails until that line does, on that server. This
 * reads every call, field and class the jar names in the server's packages, and looks each up
 * in the floor's API (the project's compile classpath), up the class hierarchy.
 *
 * What's left are the guarded calls (Folia's schedulers, the `modern` source sets): the file
 * lists them, one a line as this prints them, so a new one is looked at in review. A line no
 * longer needed fails too, so the list stays the jar's. `updateApiFloor` writes the file.
 */
@DisableCachingByDefault(because = "Quick, and its answer is the file in the repo")
abstract class ApiFloor : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** R8's mapping, to name a caller as the source does. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    /** The floor's API and what else the code compiled against. */
    @get:Classpath
    abstract val floor: ConfigurableFileCollection

    /** The guarded members: `<project>/api-floor.txt`. Read, or written by `updateApiFloor`. */
    @get:Internal
    abstract val allowed: RegularFileProperty

    @get:Input
    abstract val update: Property<Boolean>

    @TaskAction
    fun check() {
        val missing = missing(jar.get().asFile, floor.files, mapping.get().asFile)
        val file = allowed.get().asFile
        if (update.get()) {
            file.writeText(HEADER + missing.keys.joinToString("") { "$it\n" })
            logger.lifecycle("${file.name}: ${missing.size} guarded members written")
            return
        }
        val listed = if (file.isFile) read(file) else emptySet()
        val new = missing.filterKeys { it !in listed }
        val stale = listed - missing.keys
        if (new.isEmpty() && stale.isEmpty()) {
            logger.lifecycle("${jar.get().asFile.name}: every server API member is at its floor, or one of ${listed.size} guarded")
            return
        }
        throw GradleException(buildString {
            if (new.isNotEmpty()) {
                append("${jar.get().asFile.name} uses server API members its floor doesn't have:\n")
                for ((member, from) in new) append("  $member  (from ${from.joinToString()})\n")
                append(
                    "On the floor's servers these throw NoSuchMethodError or NoClassDefFoundError when they run. " +
                        "If R8 made the call (a cast it saw through), look the member up by reflection or keep it in a class only newer servers load. " +
                        "If the code checks the server has it first, list it in ${file.path}.\n",
                )
            }
            if (stale.isNotEmpty()) append("${file.path} lists members the jar no longer uses: ${stale.joinToString()}. Take them out.\n")
            append("./gradlew ${path.substringBeforeLast(':')}:updateApiFloor rewrites the file from the jar.")
        })
    }

    companion object {
        /** The server's packages: what a plugin finds on the server, not in its own jar or the JDK. */
        val SERVER_PACKAGES = listOf("org/bukkit/", "org/spigotmc/", "com/destroystokyo/", "io/papermc/", "net/kyori/")

        private const val HEADER = "# Server API members this plugin's shrunk jar uses that its floor's API lacks, each called only\n" +
            "# once the code has checked the server has it. The build's checkApiFloor fails on one not listed\n" +
            "# here (decisions/2026-11-28-api-floor-check.md); updateApiFloor rewrites it from the jar.\n"

        fun read(file: File): Set<String> = file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

        /** What [jar] uses of the server's packages that [floor] hasn't, each with the jar's classes using it (by their source names). */
        fun missing(jar: File, floor: Collection<File>, mapping: File?): Map<String, Set<String>> {
            val names = mapping?.takeIf { it.isFile }?.let(::sourceNames).orEmpty()
            val jarClasses = HashMap<String, Info>()
            val uses = HashMap<Use, MutableSet<String>>()
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                    val bytes = zip.getInputStream(entry).readBytes()
                    val info = info(bytes)
                    jarClasses[info.name] = info
                    collect(bytes) { use -> uses.getOrPut(use) { HashSet() } += names[info.name] ?: info.name.replace('/', '.') }
                }
            }
            val api = Classes(floor, jarClasses)
            try {
                val out = sortedMapOf<String, Set<String>>()
                for ((use, from) in uses) {
                    val found = when (use.name) {
                        null -> api[use.owner] != null || use.owner in jarClasses
                        else -> api.has(use.owner, use.name, use.descriptor, use.method)
                    }
                    if (found) continue
                    // A class of the server's the floor lacks is one line, not one per member.
                    val key = if (api[use.owner] == null) use.owner else "${use.owner}.${use.name}${use.descriptor}"
                    if (SERVER_PACKAGES.none { key.startsWith(it) }) continue
                    out[key] = (out[key].orEmpty() + from).toSortedSet()
                }
                return out
            } finally {
                api.close()
            }
        }

        /** R8's mapping read backwards: shrunk class name to the source's. */
        private fun sourceNames(mapping: File): Map<String, String> {
            val out = HashMap<String, String>()
            mapping.forEachLine { line ->
                if (line.startsWith(" ") || line.startsWith("#") || !line.endsWith(":")) return@forEachLine
                val (source, shrunk) = line.removeSuffix(":").split(" -> ").takeIf { it.size == 2 } ?: return@forEachLine
                out[shrunk.replace('.', '/')] = source
            }
            return out
        }

        /** One use of a server class: the class itself ([name] null), or a member of it. */
        private data class Use(val owner: String, val name: String?, val descriptor: String?, val method: Boolean)

        /** What a class says of itself: its supertypes and members. */
        private class Info(val name: String, val supers: List<String>, val members: Set<String>)

        private fun info(bytes: ByteArray): Info {
            var name = ""
            val supers = ArrayList<String>()
            val members = HashSet<String>()
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, n: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    name = n
                    superName?.let(supers::add)
                    interfaces?.let { supers.addAll(it) }
                }

                override fun visitField(access: Int, n: String, descriptor: String, signature: String?, value: Any?) = null.also { members += "$n:$descriptor" }

                override fun visitMethod(access: Int, n: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? = null.also { members += "$n$descriptor" }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return Info(name, supers, members)
        }

        // Every class, field and method a class's code names, outside the JDK.
        private fun collect(bytes: ByteArray, use: (Use) -> Unit) {
            fun type(t: Type) {
                var e = t
                while (e.sort == Type.ARRAY) e = e.elementType
                if (e.sort == Type.OBJECT && !jdk(e.internalName)) use(Use(e.internalName, null, null, false))
            }
            fun owner(o: String) = if (o.startsWith("[")) Type.getType(o).elementType.internalName else o
            fun member(o: String, n: String, d: String, method: Boolean) {
                val ownerName = owner(o)
                if (jdk(ownerName)) return
                use(Use(ownerName, n, d, method))
            }
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    superName?.let { type(Type.getObjectType(it)) }
                    interfaces?.forEach { type(Type.getObjectType(it)) }
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitTypeInsn(opcode: Int, t: String) = type(Type.getObjectType(t))
                        override fun visitFieldInsn(opcode: Int, o: String, n: String, d: String) = member(o, n, d, method = false)
                        override fun visitMethodInsn(opcode: Int, o: String, n: String, d: String, itf: Boolean) = member(o, n, d, method = true)
                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type && value.sort != Type.METHOD) type(value)
                        }
                        override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any?) {
                            for (arg in args) if (arg is Handle) member(arg.owner, arg.name, arg.desc, arg.tag != Opcodes.H_GETFIELD && arg.tag != Opcodes.H_PUTFIELD && arg.tag != Opcodes.H_GETSTATIC && arg.tag != Opcodes.H_PUTSTATIC)
                        }
                        override fun visitTryCatchBlock(start: org.objectweb.asm.Label?, end: org.objectweb.asm.Label?, handler: org.objectweb.asm.Label?, t: String?) {
                            t?.let { type(Type.getObjectType(it)) }
                        }
                    }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }

        private fun jdk(name: String) = name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/")

        /** The floor's classes, read as asked, and the jar's own, for members a jar class inherits. */
        private class Classes(floor: Collection<File>, private val jar: Map<String, Info>) : AutoCloseable {
            private val zips = floor.filter { it.isFile && it.name.endsWith(".jar") }.map(::ZipFile)
            private val dirs = floor.filter { it.isDirectory }
            private val cache = HashMap<String, Info?>()

            operator fun get(name: String): Info? = cache.getOrPut(name) {
                val path = "$name.class"
                // The JDK's from the JDK running the build: the plugins' Java 8 and on have what it inherits from.
                if (jdk(name)) return@getOrPut ClassLoader.getSystemResourceAsStream(path)?.use { info(it.readBytes()) }
                zips.firstNotNullOfOrNull { z -> z.getEntry(path)?.let { z.getInputStream(it).use { s -> info(s.readBytes()) } } }
                    ?: dirs.firstNotNullOfOrNull { d -> File(d, path).takeIf { it.isFile }?.let { info(it.readBytes()) } }
            }

            /**
             * Whether [owner] or a supertype declares the member. A supertype that's nowhere (a
             * library the floor's API leaves out) may declare it: taken as found.
             */
            fun has(owner: String, name: String, descriptor: String?, method: Boolean): Boolean {
                val key = if (method) "$name$descriptor" else "$name:$descriptor"
                val seen = HashSet<String>()
                val queue = ArrayDeque(listOf(owner))
                var unknown = false
                while (queue.isNotEmpty()) {
                    val c = queue.removeFirst()
                    if (!seen.add(c)) continue
                    val info = jar[c] ?: this[c]
                    if (info == null) {
                        // The owner itself missing is a missing class; a supertype missing can't be told.
                        if (c != owner) unknown = true
                        continue
                    }
                    if (key in info.members) return true
                    queue.addAll(info.supers)
                }
                return unknown
            }

            override fun close() = zips.forEach { it.close() }
        }
    }
}
