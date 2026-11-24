package co.xenastudios.core.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.modern.entityById
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import kotlin.uuid.toJavaUuid

/**
 * The online players a command source can see, for the `player` argument: a player hidden from
 * another (`hidePlayer`, a vanish plugin) can't be named or completed by them. The console sees
 * everyone.
 */
internal class BukkitPlayers(private val server: Server) : OnlinePlayers {

    override fun find(name: String, viewer: CommandSource): PlayerRef? {
        val player = server.getPlayerExact(name) ?: return null
        return if (canSee(viewer, player)) player.ref else null
    }

    override fun names(viewer: CommandSource): List<String> {
        val seer = viewer.player?.online(server)
        return server.onlinePlayers.filter { seer == null || seer.canSee(it) }.map { it.name }
    }

    private fun canSee(viewer: CommandSource, player: Player): Boolean {
        val seer = viewer.player?.online(server) ?: return true
        return seer.canSee(player)
    }

    companion object {
        /** How entities are found by UUID here: by the server from 1.11, by looking through the worlds before. */
        fun entities(server: Server, version: BukkitServer): Entities =
            if (version.atLeast(1, 11)) modern(server) else legacy(server)

        // Its own function, so only a server with Server.getEntity(UUID) loads the code that calls it.
        private fun modern(server: Server): Entities = Entities { uuid -> server.entityById(uuid.toJavaUuid())?.takeIf(::present) }

        // Players are a map lookup; anything else means looking through every world, which only
        // work tied to a mob does, and only on servers before 1.11.
        private fun legacy(server: Server): Entities = Entities { uuid ->
            val id = uuid.toJavaUuid()
            server.getPlayer(id) ?: server.worlds.firstNotNullOfOrNull { world -> world.entities.firstOrNull { it.uniqueId == id } }?.takeIf(::present)
        }

        // A dead player is still there, and respawns; a mob that died or was removed isn't.
        private fun present(entity: Entity): Boolean = entity is Player || entity.isValid
    }
}
