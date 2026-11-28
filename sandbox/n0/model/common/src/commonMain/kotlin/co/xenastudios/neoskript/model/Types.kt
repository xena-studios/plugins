package co.xenastudios.neoskript.model

/**
 * A kind of block, item or entity, by today's namespaced id (`minecraft:stone`) on every
 * version and platform. Each platform makes one table of them at startup ([Types]), so a type
 * is one object per id: `type of block is stone` is a pointer compare, never a string compare.
 *
 * Today's ids on every version: a 1.8.8 server's `WOOL:14` is `minecraft:red_wool` here, mapped
 * by a table made from Mojang's data fixer (as xMarket's `prices/legacy.txt`), so scripts and
 * variables mean the same thing everywhere. A type this server doesn't have isn't in its table:
 * a script naming it fails to load, with the version that added it.
 */
public abstract class GameType(
    /** Today's id, `minecraft:stone`. */
    public val id: String,
    /** Its place in its table, for the platform's arrays from its own objects to these. */
    public val index: Int,
) {
    final override fun toString(): String = id
}

/** A kind of block. */
public class BlockType(id: String, index: Int) : GameType(id, index)

/** A kind of item. */
public class ItemType(id: String, index: Int) : GameType(id, index)

/** A kind of entity: `minecraft:zombie`, `minecraft:player`. */
public class EntityType(id: String, index: Int) : GameType(id, index)

/**
 * Every type of one kind this server has, made once at startup from the platform's registry.
 * Looked up by id at parse time ([byId]); at run time the platform turns its own objects into
 * types with an array indexed by its own ordinal or raw id, never a map.
 */
public class Types<T : GameType>(ids: List<String>, make: (String, Int) -> T) {
    private val byIndex: List<T> = ids.mapIndexed { index, id -> make(id, index) }
    private val byName: Map<String, T> = HashMap<String, T>(ids.size * 2).also { map ->
        for (type in byIndex) {
            map[type.id] = type
            map[type.id.substringAfter(':')] = type
        }
    }

    /** How many there are. */
    public val size: Int get() = byIndex.size

    /** The type at [index]. */
    public operator fun get(index: Int): T = byIndex[index]

    /** The type called [id], with or without `minecraft:`; `null` if this server has none. */
    public fun byId(id: String): T? = byName[id.lowercase()]

    /** Every type, in table order. */
    public val all: List<T> get() = byIndex
}
