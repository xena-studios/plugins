package co.xenastudios.xlimbo.world;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xlimbo.XLimbo;
import co.xenastudios.xlimbo.config.XLConfig;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Collections;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Where players land and what they're given: on arrival, on respawn, and quietly. */
public final class Arrivals extends ListenerModule {

    private static final Component CLEAR = Component.text(String.join("\n", Collections.nCopies(100, " ")));

    private final XLimbo xlimbo;

    public Arrivals(XLimbo xlimbo) {
        super(xlimbo);
        this.xlimbo = xlimbo;
    }

    // Chosen before the player is in the world, so they're placed once, where the land
    // is already loaded: no teleport straight after joining.
    @EventHandler(priority = EventPriority.HIGH)
    void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location spawn = xlimbo.limbo().spawn();
        if (spawn != null) {
            event.setSpawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onJoin(PlayerJoinEvent event) {
        XLConfig config = xlimbo.config();
        Player player = event.getPlayer();
        if (config.join().suppressJoinMessage()) {
            event.joinMessage(null);
        }
        Location spawn = xlimbo.limbo().spawn();
        if (spawn != null && !xlimbo.limbo().isLimbo(player.getWorld())) {
            // Joined before the world existed, or while it was being rebuilt: bring them in.
            player.teleportAsync(spawn);
        }
        equip(player, config);
        if (config.join().clearChatOnJoin()) {
            player.sendMessage(CLEAR);
        }
        config.join().customJoinMessage().send(player);
    }

    private static void equip(Player player, XLConfig config) {
        if (config.player().setCreative() && player.getGameMode() != GameMode.CREATIVE) {
            player.setGameMode(GameMode.CREATIVE);
        }
        if (config.player().enableFlight()) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
    }

    // Flight goes with a gamemode change, which is applied after this event: give it
    // back on the next tick so nobody drops.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (xlimbo.config().player().enableFlight()) {
            Player player = event.getPlayer();
            player.getScheduler().run(plugin, task -> player.setAllowFlight(true), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onRespawn(PlayerRespawnEvent event) {
        Location spawn = xlimbo.limbo().spawn();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onQuit(PlayerQuitEvent event) {
        xlimbo.forget(event.getPlayer().getUniqueId());
        if (xlimbo.config().quit().suppressQuitMessage()) {
            event.quitMessage(null);
        }
    }

    // Nobody needs to hear that someone got "Minecraft" for arriving.
    @EventHandler(priority = EventPriority.HIGH)
    void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (xlimbo.limbo().isLimbo(event.getPlayer().getWorld())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onDeath(PlayerDeathEvent event) {
        if (xlimbo.config().death().suppressDeathMessage()) {
            event.deathMessage(null);
        }
    }
}
