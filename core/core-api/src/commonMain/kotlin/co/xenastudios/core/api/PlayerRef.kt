package co.xenastudios.core.api

import kotlin.jvm.JvmStatic
import kotlin.uuid.Uuid

/**
 * A player, online or not, by their UUID: how every API names a player, on every platform.
 * It holds no name and no server object, so it's safe to keep, compare and send anywhere;
 * names change, and the family's player cache turns one into the other.
 *
 * Two references are equal when their UUIDs are. From Java, `PlayerRefs.of(java.util.UUID)`
 * makes one and `PlayerRefs.javaUuid(ref)` reads it back (DECISIONS.md).
 */
public class PlayerRef private constructor(
    /** The player's UUID, as the server knows it (offline-mode servers make their own). */
    public val uuid: Uuid,
) {
    override fun equals(other: Any?): Boolean = other is PlayerRef && other.uuid == uuid

    override fun hashCode(): Int = uuid.hashCode()

    /** The UUID in its usual form, `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. */
    override fun toString(): String = uuid.toString()

    public companion object {
        /** The player with this [uuid]. */
        @JvmStatic
        public fun of(uuid: Uuid): PlayerRef = PlayerRef(uuid)

        /** The player whose UUID is [text], with or without dashes, or `null` if it isn't one. */
        @JvmStatic
        public fun parseOrNull(text: String): PlayerRef? =
            (Uuid.parseHexDashOrNull(text) ?: Uuid.parseHexOrNull(text))?.let(::PlayerRef)
    }
}
