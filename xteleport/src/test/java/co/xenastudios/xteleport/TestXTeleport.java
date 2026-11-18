package co.xenastudios.xteleport;

import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xteleport.storage.FlakyStore;
import co.xenastudios.xteleport.storage.Store;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * xTeleport for MockBukkit, which can't load chunks in the background or {@code teleportAsync}:
 * both happen at once. Its store's writes can be made to fail, and its moves refused.
 */
public class TestXTeleport extends XTeleport {

    /** While true, the server refuses every teleport, as another plugin cancelling it would. */
    public volatile boolean refuseMoves;

    @Override
    protected Store openStore(StorageSettings settings) {
        return new FlakyStore(settings, getDataFolder().toPath(), getLogger());
    }

    public FlakyStore flakyStore() {
        return (FlakyStore) store();
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ, boolean generate) {
        return CompletableFuture.completedFuture(world.getChunkAt(chunkX, chunkZ));
    }

    @Override
    public CompletableFuture<Boolean> move(Player player, Location to) {
        if (refuseMoves) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(player.teleport(to, PlayerTeleportEvent.TeleportCause.COMMAND));
    }
}
