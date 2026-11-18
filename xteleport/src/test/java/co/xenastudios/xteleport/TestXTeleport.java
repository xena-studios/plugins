package co.xenastudios.xteleport;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** xTeleport for MockBukkit, which can't load chunks in the background or {@code teleportAsync}: both happen at once. */
public class TestXTeleport extends XTeleport {

    @Override
    public CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ, boolean generate) {
        return CompletableFuture.completedFuture(world.getChunkAt(chunkX, chunkZ));
    }

    @Override
    public CompletableFuture<Boolean> move(Player player, Location to) {
        return CompletableFuture.completedFuture(player.teleport(to, PlayerTeleportEvent.TeleportCause.COMMAND));
    }
}
