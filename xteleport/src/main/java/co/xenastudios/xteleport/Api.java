package co.xenastudios.xteleport;

import co.xenastudios.core.Module;
import co.xenastudios.core.text.Message;
import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.RtpSettings;
import co.xenastudios.xteleport.api.TeleportOptions;
import co.xenastudios.xteleport.api.TeleportResult;
import co.xenastudios.xteleport.api.TeleportType;
import co.xenastudios.xteleport.api.Warp;
import co.xenastudios.xteleport.api.XTeleportApi;
import co.xenastudios.xteleport.home.Homes;
import co.xenastudios.xteleport.rtp.Search;
import co.xenastudios.xteleport.teleport.Teleporter;
import co.xenastudios.xteleport.warp.Places;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * xTeleport's developer API, registered with the services manager while the plugin is
 * enabled. Reads answer from memory; changes and searches return futures. It adds no
 * listeners or tasks of its own.
 */
final class Api implements XTeleportApi, Module {

    private final XTeleport plugin;

    Api(XTeleport plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        plugin.getServer().getServicesManager().register(XTeleportApi.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        plugin.getServer().getServicesManager().unregister(XTeleportApi.class, this);
    }

    @Override
    public CompletableFuture<TeleportResult> teleport(Player player, Location destination, TeleportOptions options) {
        Location to = destination.clone();
        Teleporter.Trip trip = new Teleporter.Trip(TeleportType.PLUGIN, options.cooldownKey(), options.cooldownSeconds(),
            options.cost(), options.warmupSeconds(), options.checkSafe(), null, Message.empty());
        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        plugin.teleporter().onPlayer(player, () -> plugin.teleporter().teleport(player, trip, () -> CompletableFuture.completedFuture(to))
            .whenComplete((done, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(done);
                }
            }), () -> result.complete(TeleportResult.FAILED));
        return result;
    }

    @Override
    public Duration cooldown(UUID player, String key) {
        return plugin.teleporter().cooldowns().remaining(player, key);
    }

    @Override
    public Optional<List<Home>> cachedHomes(UUID player) {
        return plugin.homes().cached(player).map(Homes.Loaded::list);
    }

    @Override
    public CompletableFuture<List<Home>> homes(UUID player) {
        return plugin.homes().of(player).thenApply(Homes.Loaded::list);
    }

    @Override
    public CompletableFuture<Boolean> setHome(UUID player, String name, Location location) {
        if (!Homes.validName(name) || !location.isWorldLoaded()) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.homes().set(new Home(player, name, location.getWorld().getName(), location.getX(), location.getY(),
            location.getZ(), location.getYaw(), location.getPitch()));
    }

    @Override
    public CompletableFuture<Boolean> deleteHome(UUID player, String name) {
        return plugin.homes().delete(player, name);
    }

    @Override
    public int homeLimit(Player player) {
        return plugin.homeLimits().cached(player, plugin.config().homes());
    }

    @Override
    public List<Warp> warps() {
        return plugin.places().warps();
    }

    @Override
    public Optional<Warp> warp(String name) {
        return plugin.places().warp(name);
    }

    @Override
    public CompletableFuture<Boolean> setWarp(String name, Location location) {
        if (!Homes.validName(name) || !location.isWorldLoaded()) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.places().setWarp(Places.of(name, location));
    }

    @Override
    public CompletableFuture<Boolean> deleteWarp(String name) {
        // Before the warps are read, only the database knows whether there's one.
        if (plugin.places().loaded() && plugin.places().warp(name).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.places().deleteWarp(name);
    }

    @Override
    public Optional<Location> spawn() {
        return Optional.ofNullable(plugin.places().spawn()).map(Location::clone);
    }

    @Override
    public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpSettings settings) {
        if (plugin.rtp().begin(null) != Search.Start.STARTED) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Search.Found> search;
        try {
            search = plugin.rtp().find(world, settings);
        } catch (RuntimeException e) {
            plugin.rtp().end(null);
            return CompletableFuture.failedFuture(e);
        }
        return search.handle((found, error) -> {
            plugin.rtp().end(null);
            return found == null ? Optional.<Location>empty() : Optional.of(found.location());
        });
    }
}
