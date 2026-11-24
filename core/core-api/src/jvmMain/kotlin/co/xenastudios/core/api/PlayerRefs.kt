@file:JvmName("PlayerRefs")

package co.xenastudios.core.api

import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/*
 * Java's way to a PlayerRef and back: the API names players by kotlin.uuid.Uuid, the same on
 * every platform, and Java code has java.util.UUID. From Java, PlayerRefs.of(player.getUniqueId())
 * and PlayerRefs.javaUuid(ref); from Kotlin, uuid.toPlayerRef() and ref.javaUuid. For a bare
 * Uuid elsewhere in an API, the stdlib's kotlin.uuid.UuidKt.toJavaUuid(uuid) and toKotlinUuid.
 */

/** The player with this UUID. From Java, `PlayerRefs.of(uuid)`. */
@JvmName("of")
public fun UUID.toPlayerRef(): PlayerRef = PlayerRef.of(toKotlinUuid())

/** The player's UUID as Java's type. From Java, `PlayerRefs.javaUuid(ref)`. */
public val PlayerRef.javaUuid: UUID
    @JvmName("javaUuid") get() = uuid.toJavaUuid()
