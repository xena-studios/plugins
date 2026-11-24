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
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import net.minecraft.network.chat.Component as McComponent

// Between Minecraft's players, worlds and places and the core's references to them, which
// shared code and every API use (DECISIONS.md, "The platform layer").

/** The player's reference: by UUID, as everywhere. */
public val ServerPlayer.ref: PlayerRef get() = PlayerRef.of(uuid.toKotlinUuid())

/** The world's reference: its dimension's id, such as `minecraft:overworld`. */
public val Level.ref: WorldRef get() = worldRef(dimension())

/** Where the entity is, and which way it faces. */
public val Entity.location: Location get() = Location(level().ref, x, y, z, yRot, xRot)

/** The world [world] names, if it's loaded. */
public fun MinecraftServer.level(world: WorldRef): ServerLevel? = allLevels.firstOrNull { worldRef(it.dimension()) == world }

/** The player [player] names, if they're online. */
public fun MinecraftServer.player(player: PlayerRef): ServerPlayer? = playerList.getPlayer(player.uuid.toJavaUuid())

private fun worldRef(key: ResourceKey<Level>): WorldRef =
    //? if identifier {
    WorldRef.of(key.identifier().toString())
    //?} else
    //WorldRef.of(key.location().toString())

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
