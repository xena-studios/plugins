package co.xenastudios.core.common.family

import co.xenastudios.core.common.locked

/**
 * Where the Xena plugins on one server meet: maps every plugin of the family sees, for what
 * they share at runtime (the player cache). The platform's adapter finds the board when the
 * plugin starts, or makes it if it's the first; on the JVM that's `core-jvm`'s `JvmFamilyBoard`,
 * found through the server's registry for objects plugins share.
 *
 * **Only the JDK's types cross it.** Each plugin shades its own relocated copy of the core, and
 * on Spigot each has its own copy of Kotlin too (`libraries:` gives every plugin a class loader
 * of its own), so a class of the core or of Kotlin from one plugin is a different class in
 * another, and a cast between them fails. What's put on a board is only ever text, boxed
 * numbers and booleans, and arrays of those (`Array<Any?>`, Java's `Object[]`), which every
 * copy reads alike; and a value is never changed once it's there, only replaced
 * ([SharedMap.replace]). A plugin's own objects, lambdas included, never go on it.
 *
 * Where plugins can't share objects (Pumpkin, whose plugins only send each other bytes), each
 * plugin has a [MemoryBoard] of its own, and what's on it is that plugin's alone.
 */
public interface FamilyBoard {
    /**
     * The map called [name], made empty by the first plugin to ask for it. A name says what's
     * in it and the version of its layout (`xena.players.v1`), so plugins built on different
     * versions of the core never read each other's values wrong: a new layout is a new name.
     */
    public fun map(name: String): SharedMap
}

/**
 * One map of a [FamilyBoard], by text key, safe to use from any thread of any plugin: on the
 * JVM a `ConcurrentHashMap` every plugin holds. Each change is atomic on its own; a change that
 * depends on what's there uses [putIfAbsent], [replace] or the two-argument [remove], which
 * compare by identity for arrays and by equality for text and numbers.
 */
public interface SharedMap {
    /** How many keys it has. */
    public val size: Int

    /** The value under [key], or `null`. */
    public operator fun get(key: String): Any?

    /** Sets [key] to [value]; returns what was there. */
    public fun put(key: String, value: Any): Any?

    /** Sets [key] to [value] if it has none; returns what was there, or `null` if this put it. */
    public fun putIfAbsent(key: String, value: Any): Any?

    /** Sets [key] to [value] only if it's still [expected]; returns whether it did. */
    public fun replace(key: String, expected: Any, value: Any): Boolean

    /** Removes [key]; returns what was there. */
    public fun remove(key: String): Any?

    /** Removes [key] only if it's still [expected]; returns whether it did. */
    public fun remove(key: String, expected: Any): Boolean

    /** Its keys as they are now. */
    public fun keys(): List<String>
}

/**
 * A [FamilyBoard] in this plugin's memory: for platforms whose plugins can't share objects
 * (Pumpkin), and for tests, where one board handed to several plugins on the fake platform
 * stands in for a server's.
 */
public class MemoryBoard : FamilyBoard {
    private val lock = Any()
    private val maps = HashMap<String, SharedMap>()

    override fun map(name: String): SharedMap = locked(lock) { maps.getOrPut(name) { MemoryMap() } }

    private class MemoryMap : SharedMap {
        private val lock = Any()
        private val entries = HashMap<String, Any>()

        override val size: Int get() = locked(lock) { entries.size }

        override fun get(key: String): Any? = locked(lock) { entries[key] }

        override fun put(key: String, value: Any): Any? = locked(lock) { entries.put(key, value) }

        override fun putIfAbsent(key: String, value: Any): Any? = locked(lock) {
            entries[key] ?: run {
                entries[key] = value
                null
            }
        }

        override fun replace(key: String, expected: Any, value: Any): Boolean = locked(lock) {
            if (same(entries[key], expected)) {
                entries[key] = value
                true
            } else {
                false
            }
        }

        override fun remove(key: String): Any? = locked(lock) { entries.remove(key) }

        override fun remove(key: String, expected: Any): Boolean = locked(lock) {
            if (same(entries[key], expected)) {
                entries.remove(key)
                true
            } else {
                false
            }
        }

        override fun keys(): List<String> = locked(lock) { entries.keys.toList() }

        // As ConcurrentHashMap compares: equals, which for an array is identity.
        private fun same(found: Any?, expected: Any): Boolean = found != null && found == expected
    }
}
