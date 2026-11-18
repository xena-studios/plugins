package co.xenastudios.xteleport;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xteleport.api.Warp;
import co.xenastudios.xteleport.config.XTConfig;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Sends joining players to the spawn (on their first join, or every join) and players who
 * die without a bed or anchor. Players are placed before they're in the world, so joining
 * at spawn is no teleport at all. Only listens while one of those is on, and only acts once
 * a spawn has been set with {@code /setspawn}.
 */
final class SpawnListener extends ListenerModule {

    private final XTeleport plugin;

    SpawnListener(XTeleport plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    private Location setSpawn() {
        Warp spawn = plugin.places().setSpawn();
        return spawn == null ? null : spawn.location();
    }

    // Off the server's threads, before the player is in the world: reads only memory.
    @EventHandler(priority = EventPriority.HIGH)
    void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        XTConfig.Spawn settings = plugin.config().spawn();
        if (settings.onEveryJoin() || (settings.onFirstJoin() && event.isNewPlayer())) {
            Location spawn = setSpawn();
            if (spawn != null) {
                event.setSpawnLocation(spawn);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onRespawn(PlayerRespawnEvent event) {
        // Deaths only: leaving the End goes where the game sends it.
        if (plugin.config().spawn().onRespawn() && event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.DEATH
            && !event.isBedSpawn() && !event.isAnchorSpawn()) {
            Location spawn = setSpawn();
            if (spawn != null) {
                event.setRespawnLocation(spawn);
            }
        }
    }
}
