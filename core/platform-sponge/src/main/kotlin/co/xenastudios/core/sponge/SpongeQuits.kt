package co.xenastudios.core.sponge

import org.spongepowered.api.entity.living.player.server.ServerPlayer
import org.spongepowered.api.event.Event
import org.spongepowered.api.event.network.ServerSideConnectionEvent
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * A player leaving, on every API line, picked once at startup. API 8 says so with
 * `ServerSideConnectionEvent.Disconnect`, whose `player()` is who left; later APIs moved that to
 * `ServerSideConnectionEvent.Leave` and left `Disconnect` with only a profile (it fires for
 * connections that never joined too). The jar compiles against API 8, so `Leave` is found by
 * name, and its `player()` called through a method handle; `Disconnect.player()` is called only
 * where `Leave` doesn't exist (api-newest.txt lists it).
 */
internal object SpongeQuits {
    private val leave: Class<out Event>? = try {
        @Suppress("UNCHECKED_CAST")
        Class.forName("org.spongepowered.api.event.network.ServerSideConnectionEvent\$Leave", false, SpongeQuits::class.java.classLoader) as Class<out Event>
    } catch (_: ClassNotFoundException) {
        null
    }

    private val player: MethodHandle? = leave?.let {
        MethodHandles.publicLookup().findVirtual(it, "player", MethodType.methodType(ServerPlayer::class.java))
    }

    /** Calls [quit] with each player who leaves, through [platform]'s listeners. */
    fun listen(platform: SpongePlatform, quit: (ServerPlayer) -> Unit) {
        val type = leave
        val handle = player
        if (type == null || handle == null) {
            platform.listen(ServerSideConnectionEvent.Disconnect::class.java) { event -> quit(event.player()) }
        } else {
            platform.listen(type) { event -> quit(handle.invoke(event) as ServerPlayer) }
        }
    }
}
