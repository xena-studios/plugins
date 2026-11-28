@file:JvmName("FabricRefs")

package co.xenastudios.core.fabric

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.Delivery
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import net.minecraft.network.chat.Component as McComponent

// Between Minecraft's players, worlds and places and the core's references to them, which
// shared code and every API use (DECISIONS.md, "The platform layer").

/** The player's reference: by UUID, as everywhere. */
public val ServerPlayer.ref: PlayerRef get() = PlayerRef.of(uuid.toKotlinUuid())

/** The world's reference: its dimension's id, such as `minecraft:overworld`. Made once per dimension. */
public val Level.ref: WorldRef get() = worldRef(dimension())

/** Where the entity is, and which way it faces. */
public val Entity.location: Location get() = Location(level().ref, x, y, z, yRot, xRot)

/** The world [world] names, if it's loaded. */
public fun MinecraftServer.level(world: WorldRef): ServerLevel? = allLevels.firstOrNull { worldRef(it.dimension()) == world }

/** The player [player] names, if they're online. */
public fun MinecraftServer.player(player: PlayerRef): ServerPlayer? = playerList.getPlayer(player.uuid.toJavaUuid())

// One reference per dimension: hot paths ask a block or an entity for its world on every event.
// Keyed by the dimension's ResourceKey (Minecraft keeps one per id), never by the level, so a
// level a mod unloads isn't held; a mod that makes dimensions by the thousand clears the lot now
// and then rather than keeping them all.
private val worldRefs = ConcurrentHashMap<ResourceKey<Level>, WorldRef>()

internal const val MAX_WORLD_REFS: Int = 256

private fun worldRef(key: ResourceKey<Level>): WorldRef =
    //? if identifier {
    cachedWorldRef(worldRefs, key) { it.identifier().toString() }
    //?} else
    //cachedWorldRef(worldRefs, key) { it.location().toString() }

/** [cache]'s reference for [key], made from [name] the first time; the cache is cleared once it holds 256. */
internal inline fun <K : Any> cachedWorldRef(cache: ConcurrentHashMap<K, WorldRef>, key: K, name: (K) -> String): WorldRef {
    cache[key]?.let { return it }
    if (cache.size >= MAX_WORLD_REFS) cache.clear()
    val made = WorldRef.of(name(key))
    return cache.putIfAbsent(key, made) ?: made
}

/**
 * The players online, for the core's player arguments: found by name in any case, and listed
 * for completion. [server] is `null` until the server starts.
 */
public class FabricOnlinePlayers(private val server: () -> MinecraftServer?) : OnlinePlayers {
    override fun find(name: String, viewer: CommandSource): PlayerRef? =
        server()?.playerList?.players?.firstOrNull { it.scoreboardName.equals(name, ignoreCase = true) }?.ref

    override fun names(viewer: CommandSource): List<String> =
        server()?.playerList?.players?.map { it.scoreboardName } ?: emptyList()
}

/**
 * Each player's language, from the options their client sends: the locale, such as `pt_br`.
 * [FabricFamily] records it in the family's player cache on a join and when it changes, and
 * messages read it there ([co.xenastudios.core.common.family.PlayerCache] is the
 * [co.xenastudios.core.common.i18n.LanguageSource]).
 *
 * Minecraft 1.20.1 doesn't keep the client's language after reading its options packet (1.20.2
 * started to), and reaching that packet would take a mixin, so on 1.20.1 every player reads the
 * family's `default-language`.
 */
public object FabricLanguages {
    /** [player]'s client locale, or `null` where Minecraft doesn't keep it (1.20.1). */
    public fun clientLocale(player: ServerPlayer): String? =
        //? if client_information {
        player.clientInformation().language()
        //?} else
        //null
}

/**
 * Sends drawn messages ([co.xenastudios.core.common.i18n.MessageDrawer]) to a player by
 * reference, or to the console for `null`. A player who's gone by then gets nothing. On the
 * server thread, where Minecraft sends from.
 */
public class FabricDelivery(private val server: () -> MinecraftServer?) : Delivery<McComponent> {
    override fun deliver(viewer: PlayerRef?, text: McComponent) {
        val running = server() ?: return
        if (viewer == null) running.sendSystemMessage(text) else running.player(viewer)?.sendSystemMessage(text)
    }
}
