package co.xenastudios.core.jvm.family

import co.xenastudios.core.common.family.FamilyBoard
import co.xenastudios.core.common.family.SharedMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * The family's board on the JVM: one `ConcurrentHashMap` per server, holding a `ConcurrentHashMap`
 * per [map] name, which every Xena plugin wraps in its own `JvmFamilyBoard`. Both are the JDK's,
 * so each plugin's relocated copy of the core, with its own copy of Kotlin, reads the same maps
 * (DECISIONS.md, "The family").
 *
 * The adapter finds the server's [root] through the platform's registry for objects plugins
 * share, keyed by a JDK type, and makes it with [newRoot] if it's the first:
 * - Bukkit: `ServicesManager`, under `ConcurrentMap.class`, picking the registration [isRoot]
 *   accepts. Every Xena plugin registers it, not only the first, so it stays found while any is
 *   enabled: Bukkit drops a plugin's services when it's disabled.
 * - Fabric: `FabricLoader.getObjectShare()`, `putIfAbsent("xena:board", newRoot())`.
 * - Velocity and BungeeCord, which have no such registry: the other Xena plugins' main objects,
 *   from the proxy's plugin manager. Each is the core-loader's shim, which hands out the board its
 *   plugin holds as a JDK `Supplier` ([fromShims]); every plugin sets its own to the same board.
 */
public class JvmFamilyBoard(
    /** The server's board: what [newRoot] made, as the registry gave it. */
    public val root: ConcurrentMap<String, Any>,
) : FamilyBoard {
    init {
        require(isRoot(root)) { "not a family board: it has no $MARKER" }
    }

    override fun map(name: String): SharedMap {
        // computeIfAbsent makes it once, whichever plugin asks first.
        @Suppress("UNCHECKED_CAST")
        val map = root.computeIfAbsent(name) { ConcurrentHashMap<String, Any>() } as ConcurrentMap<String, Any>
        return JdkMap(map)
    }

    private class JdkMap(private val map: ConcurrentMap<String, Any>) : SharedMap {
        override val size: Int get() = map.size

        override fun get(key: String): Any? = map[key]

        override fun put(key: String, value: Any): Any? = map.put(key, value)

        override fun putIfAbsent(key: String, value: Any): Any? = map.putIfAbsent(key, value)

        override fun replace(key: String, expected: Any, value: Any): Boolean = map.replace(key, expected, value)

        override fun remove(key: String): Any? = map.remove(key)

        override fun remove(key: String, expected: Any): Boolean = map.remove(key, expected)

        override fun keys(): List<String> = ArrayList(map.keys)
    }

    public companion object {
        /** The key a board holds, by which [isRoot] tells it from another plugin's map. */
        public const val MARKER: String = "co.xenastudios.family"

        /** The name a platform's registry keeps the board under, where it's keyed by text. */
        public const val NAME: String = "xena:board"

        /**
         * This plugin's board, once its platform found it: for the core's own parts that have
         * no platform to ask, such as `JdbcDatabase`, which puts the plugin's database on the
         * board for the family's backups (`FamilyBackups`). Each plugin relocates its own copy
         * of the core, so this is one plugin's; `null` where no platform set it (tests, tools).
         */
        @JvmStatic
        @Volatile
        public var server: JvmFamilyBoard? = null

        /** A new, empty board for a server, for the first plugin to start. */
        @JvmStatic
        public fun newRoot(): ConcurrentMap<String, Any> = ConcurrentHashMap<String, Any>().also { it[MARKER] = 1 }

        /** Whether [candidate], something another plugin shared, is the family's board. */
        @JvmStatic
        public fun isRoot(candidate: Any?): Boolean = candidate is ConcurrentMap<*, *> && candidate[MARKER] != null

        /**
         * The board another Xena plugin on a proxy holds, from the main objects of the proxy's
         * plugins ([instances]), or a new one if none has it: each Xena plugin's main object is the
         * loader's shim for that proxy, whatever package its plugin relocated it to, whose class
         * name ends with [shim] (`core.loader.velocity.VelocityBootstrap`), and which hands out its
         * board as a `java.util.function.Supplier`. Other plugins' objects are never called.
         */
        @JvmStatic
        public fun fromShims(instances: Iterable<Any?>, shim: String): JvmFamilyBoard =
            find(instances.filter { isShim(it, shim) }.map { (it as java.util.function.Supplier<*>).get() })

        private fun isShim(instance: Any?, shim: String): Boolean {
            if (instance !is java.util.function.Supplier<*>) return false
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                if (type.name.endsWith(".$shim")) return true
                type = type.superclass
            }
            return false
        }

        /** The first of [candidates] that's the family's board, or a new one if none is. */
        @JvmStatic
        public fun find(candidates: Iterable<Any?>): JvmFamilyBoard {
            @Suppress("UNCHECKED_CAST")
            val found = candidates.firstOrNull(::isRoot) as ConcurrentMap<String, Any>?
            return JvmFamilyBoard(found ?: newRoot())
        }
    }
}
