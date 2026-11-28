@file:JvmName("SpongeRefs")

package co.xenastudios.core.sponge

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.Delivery
import net.kyori.adventure.text.Component
import org.spongepowered.api.Server
import org.spongepowered.api.Sponge
import org.spongepowered.api.entity.living.player.server.ServerPlayer
import org.spongepowered.api.scheduler.Task
import org.spongepowered.api.world.server.ServerWorld
import java.util.Locale
import kotlin.uuid.toJavaUuid

// Between Sponge's players, worlds and places and the core's references to them, which shared
// code and every API use (DECISIONS.md, "The platform layer").

/** The player's reference: by UUID, as everywhere. */
public val ServerPlayer.ref: PlayerRef get() = uniqueId().toPlayerRef()

/** The world's reference: its key, such as `minecraft:overworld`, as on Fabric. */
public val ServerWorld.ref: WorldRef get() = WorldRef.of(key().formatted())

/** Where the player is, and which way they face. */
public val ServerPlayer.location: Location
    get() {
        val position = position()
        val rotation = rotation()
        // Sponge's rotation is (pitch, yaw, roll) in degrees.
        return Location(world().ref, position.x(), position.y(), position.z(), rotation.y().toFloat(), rotation.x().toFloat())
    }

/** The world [world] names, if it's loaded. */
public fun Server.world(world: WorldRef): ServerWorld? = worldManager().worlds().firstOrNull { it.key().formatted() == world.name }

/** The player [player] names, if they're online. */
public fun Server.player(player: PlayerRef): ServerPlayer? = player(player.uuid.toJavaUuid()).orElse(null)

/** The server, once it's running; `null` before and after. */
internal fun runningServer(): Server? = if (Sponge.isServerAvailable()) Sponge.server() else null

/**
 * Runs [work] on the server thread: now if this is it (or there's no server yet, while plugins
 * are constructed on the game's main thread), else at the start of the next tick, as Minecraft
 * sends to players from there.
 */
internal fun onServerThread(work: () -> Unit) {
    val server = runningServer()
    if (server == null || server.onMainThread()) {
        work()
        return
    }
    server.scheduler().submit(Task.builder().plugin(SpongeBootstrap.anyContainer()).execute(Runnable { work() }).build())
}

/**
 * The players online, for the core's player arguments: found by name in any case, and listed
 * for completion.
 */
public class SpongeOnlinePlayers internal constructor() : OnlinePlayers {
    override fun find(name: String, viewer: CommandSource): PlayerRef? =
        runningServer()?.onlinePlayers()?.firstOrNull { it.name().equals(name, ignoreCase = true) }?.ref

    override fun names(viewer: CommandSource): List<String> =
        runningServer()?.onlinePlayers()?.map { it.name() } ?: emptyList()
}

/** Each player's language, from the settings their client sends, as Minecraft writes it: `pt_br`. */
public object SpongeLanguages {
    /** [player]'s client locale. */
    public fun clientLocale(player: ServerPlayer): String = player.locale().toString().lowercase(Locale.ROOT)
}

/**
 * Sends drawn messages ([co.xenastudios.core.common.i18n.MessageDrawer]) to a player by
 * reference, or to the console for `null`. A player who's gone by then gets nothing.
 */
public class SpongeDelivery internal constructor() : Delivery<Component> {
    override fun deliver(viewer: PlayerRef?, text: Component) {
        if (viewer == null) {
            Sponge.systemSubject().sendMessage(text)
            return
        }
        onServerThread { runningServer()?.player(viewer)?.sendMessage(text) }
    }
}
