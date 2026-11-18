package co.xenastudios.xteleport.teleport;

import co.xenastudios.core.hook.VaultEconomy;
import co.xenastudios.core.player.Warmups;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.api.TeleportResult;
import co.xenastudios.xteleport.api.TeleportType;
import co.xenastudios.xteleport.api.event.PreTeleportEvent;
import co.xenastudios.xteleport.api.event.TeleportEvent;
import co.xenastudios.xteleport.config.Settings;
import co.xenastudios.xteleport.config.XTConfig;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Every teleport xTeleport makes, and the ones other plugins ask for through the API, go
 * through here, in this order: cooldown and cost checked, the warmup (cancelled by moving
 * or damage), the destination worked out and its chunk loaded in the background, the
 * safety check on the destination's own thread, {@link PreTeleportEvent}, the cost taken,
 * then {@code teleportAsync} with the {@code COMMAND} cause, which xUtilities' {@code /back}
 * records. A teleport that fails after paying is refunded. Every teleport's future
 * completes, even when the plugin stops partway: then with {@code CANCELLED}.
 */
public final class Teleporter {

    public static final String WARMUP_BYPASS = "xteleport.warmup.bypass";
    public static final String COOLDOWN_BYPASS = "xteleport.cooldown.bypass";
    public static final String COST_BYPASS = "xteleport.cost.bypass";

    /**
     * One teleport's settings.
     *
     * @param warmup seconds to stand still, or -1 for the config's
     * @param payer who pays and has the cooldown, or null for the player teleporting: for
     *     {@code /tpahere}, whoever asked
     * @param arrived told to the player once they arrive, with {@code placeholders}
     */
    public record Trip(TeleportType type, String cooldownKey, int cooldown, double cost, int warmup, boolean checkSafe,
                       Player payer, Message arrived, TagResolver... placeholders) {

        /** A command's teleport, with its cooldown and cost. */
        public static Trip of(TeleportType type, String key, Settings.Travel settings, boolean checkSafe, Message arrived,
                              TagResolver... placeholders) {
            return new Trip(type, key, settings.cooldown(), settings.cost(), -1, checkSafe, null, arrived, placeholders);
        }

        public Trip paidBy(Player who) {
            return new Trip(type, cooldownKey, cooldown, cost, warmup, checkSafe, who, arrived, placeholders);
        }
    }

    /**
     * A destination that turned out not to exist, with what to tell the player instead of
     * "the teleport didn't work": complete the destination's future with it.
     */
    public static final class NoDestination extends RuntimeException {

        private final transient Message message;

        public NoDestination(Message message) {
            super(null, null, false, false);
            this.message = message;
        }
    }

    private final XTeleport plugin;
    private final Warmups warmups;
    private final Cooldowns cooldowns = new Cooldowns();
    private final VaultEconomy economy = new VaultEconomy();
    // Every teleport not yet finished, so stopping the plugin can finish them.
    private final Set<CompletableFuture<TeleportResult>> unfinished = ConcurrentHashMap.newKeySet();
    private volatile boolean warnedEconomy;

    public Teleporter(XTeleport plugin) {
        this.plugin = plugin;
        this.warmups = new Warmups(plugin);
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    /** Whether a player is waiting out a warmup. Any thread. */
    public boolean warmingUp(UUID player) {
        return warmups.waiting(player);
    }

    /**
     * Stops every warmup without a word, as when the plugin stops, and completes every
     * teleport not yet finished with {@code CANCELLED}, so no caller waits forever.
     */
    public void clear() {
        warmups.clear();
        for (CompletableFuture<TeleportResult> result : List.copyOf(unfinished)) {
            result.complete(TeleportResult.CANCELLED);
        }
        unfinished.clear();
    }

    private XTConfig.TeleportMessages messages() {
        return plugin.config().messages().teleport();
    }

    private static Player payer(Trip trip, Player player) {
        return trip.payer() == null ? player : trip.payer();
    }

    private double cost(Trip trip, Player player) {
        return trip.cost() > 0 && !payer(trip, player).hasPermission(COST_BYPASS) ? trip.cost() : 0;
    }

    private String money(double amount) {
        String formatted = economy.format(amount);
        return formatted != null ? formatted : (amount == Math.rint(amount) ? Long.toString((long) amount) : Double.toString(amount));
    }

    /**
     * The checks that can say no before anything starts: the payer's cooldown and whether
     * they can afford it. Tells the payer why. On the player's thread.
     *
     * @return null if it can go ahead, otherwise why not
     */
    public TeleportResult precheck(Player player, Trip trip) {
        Player payer = payer(trip, player);
        if (onCooldown(payer, trip.cooldownKey())) {
            return TeleportResult.ON_COOLDOWN;
        }
        double cost = cost(trip, player);
        if (cost > 0) {
            if (!economy.ready()) {
                noEconomy(payer);
                return TeleportResult.CANT_AFFORD;
            }
            if (!economy.has(payer, cost)) {
                messages().cantAfford().send(payer, Placeholders.text("cost", money(cost)));
                return TeleportResult.CANT_AFFORD;
            }
        }
        return null;
    }

    /**
     * Whether a cooldown on this key is still running for the player, telling them how
     * long is left if so. Whatever length the teleport would start: a cooldown another
     * teleport started on the same key counts. False for a blank key or with
     * {@link #COOLDOWN_BYPASS}.
     */
    public boolean onCooldown(Player player, String key) {
        if (key.isEmpty() || player.hasPermission(COOLDOWN_BYPASS)) {
            return false;
        }
        Duration left = cooldowns.remaining(player.getUniqueId(), key);
        if (left.isZero()) {
            return false;
        }
        messages().cooldown().send(player, Placeholders.text("time", Durations.format(left.plusMillis(999))));
        return true;
    }

    private void noEconomy(Player payer) {
        messages().noEconomy().send(payer);
        if (!warnedEconomy) {
            warnedEconomy = true;
            plugin.getLogger().warning(economy.installed()
                ? "A teleport has a cost, but no economy plugin is registered with Vault. Install one, or set the costs to 0."
                : "A teleport has a cost, but Vault isn't installed. Install Vault and an economy plugin, or set the costs to 0.");
        }
    }

    /**
     * Teleports a player, step by step as the class says. Call on the player's thread.
     *
     * @param destination works out where to once the warmup is over, such as another
     *     player's spot at that moment; may complete on any thread, with null if there's
     *     nowhere to go any more
     * @return a future completed with how it ended, on whichever thread finished it
     */
    public CompletableFuture<TeleportResult> teleport(Player player, Trip trip, Supplier<CompletableFuture<Location>> destination) {
        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        TeleportResult refused = precheck(player, trip);
        if (refused != null) {
            result.complete(refused);
            return result;
        }
        unfinished.add(result);
        result.whenComplete((done, error) -> unfinished.remove(result));
        XTConfig config = plugin.config();
        int seconds = player.hasPermission(WARMUP_BYPASS) ? 0 : trip.warmup() >= 0 ? trip.warmup() : config.teleports().warmup();
        if (seconds > 0) {
            messages().warmup().send(player, Placeholders.text("seconds", seconds));
        }
        warmups.start(player, Warmups.Warmup.<Player>of(seconds, moving -> arrive(moving, trip, destination, result))
            .countdown(messages().countdown())
            .cancelOnDamage(config.teleports().cancelOnDamage())
            .onCancel((moving, reason) -> {
                switch (reason) {
                    case MOVED -> messages().movedCancelled().send(moving);
                    case DAMAGED -> messages().damageCancelled().send(moving);
                    default -> { }
                }
                result.complete(TeleportResult.CANCELLED);
            }));
        return result;
    }

    // After the warmup, on the player's thread.
    private void arrive(Player player, Trip trip, Supplier<CompletableFuture<Location>> destination, CompletableFuture<TeleportResult> result) {
        CompletableFuture<Location> where;
        try {
            where = destination.get();
        } catch (RuntimeException e) {
            where = CompletableFuture.failedFuture(e);
        }
        where.whenComplete((to, error) -> {
            Throwable cause = error instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : error;
            if (cause instanceof NoDestination none) {
                onPlayer(player, () -> none.message.send(player), () -> {});
                result.complete(TeleportResult.FAILED);
                return;
            }
            if (error != null || to == null) {
                onPlayer(player, () -> messages().failed().send(player), () -> {});
                result.complete(TeleportResult.FAILED);
                return;
            }
            if (!to.isWorldLoaded()) {
                onPlayer(player, () -> messages().unsafe().send(player), () -> {});
                result.complete(TeleportResult.UNSAFE);
                return;
            }
            if (!trip.checkSafe() || !plugin.config().teleports().checkSafe()) {
                onPlayer(player, () -> go(player, trip, to, result), () -> result.complete(TeleportResult.FAILED));
                return;
            }
            Safety.check(plugin, plugin::loadChunk, to, plugin.config().teleports().maxFall()).whenComplete((safe, failed) -> {
                if (failed != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Couldn't check a teleport's destination", failed);
                }
                if (failed != null || !Boolean.TRUE.equals(safe)) {
                    onPlayer(player, () -> messages().unsafe().send(player), () -> {});
                    result.complete(TeleportResult.UNSAFE);
                    return;
                }
                onPlayer(player, () -> go(player, trip, to, result), () -> result.complete(TeleportResult.FAILED));
            });
        });
    }

    // The last steps, on the player's thread: the event, payment, the teleport.
    private void go(Player player, Trip trip, Location to, CompletableFuture<TeleportResult> result) {
        if (!player.isOnline()) {
            result.complete(TeleportResult.FAILED);
            return;
        }
        if (PreTeleportEvent.getHandlerList().getRegisteredListeners().length > 0) {
            PreTeleportEvent event = new PreTeleportEvent(player, to.clone(), trip.type());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                result.complete(TeleportResult.BLOCKED);
                return;
            }
        }
        double cost = cost(trip, player);
        Player payer = payer(trip, player);
        if (cost > 0) {
            if (!economy.ready()) {
                noEconomy(payer);
                result.complete(TeleportResult.CANT_AFFORD);
                return;
            }
            if (!economy.take(payer, cost)) {
                messages().cantAfford().send(payer, Placeholders.text("cost", money(cost)));
                result.complete(TeleportResult.CANT_AFFORD);
                return;
            }
            messages().paid().send(payer, Placeholders.text("cost", money(cost)));
        }
        Location from = player.getLocation();
        CompletableFuture<Boolean> move;
        try {
            move = plugin.move(player, to);
        } catch (RuntimeException e) {
            move = CompletableFuture.failedFuture(e);
        }
        move.whenComplete((moved, error) -> {
            if (error == null && Boolean.TRUE.equals(moved)) {
                cooldowns.start(payer.getUniqueId(), trip.cooldownKey(), trip.cooldown());
                onPlayer(player, () -> {
                    trip.arrived().send(player, trip.placeholders());
                    if (TeleportEvent.getHandlerList().getRegisteredListeners().length > 0) {
                        Bukkit.getPluginManager().callEvent(new TeleportEvent(player, from, to.clone(), trip.type()));
                    }
                }, () -> {});
                result.complete(TeleportResult.TELEPORTED);
                return;
            }
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "A teleport failed", error);
            }
            if (cost > 0 && economy.give(payer, cost)) {
                messages().refunded().send(payer, Placeholders.text("cost", money(cost)));
            }
            onPlayer(player, () -> messages().failed().send(player), () -> {});
            result.complete(TeleportResult.FAILED);
        });
    }

    /**
     * Runs on the player's own thread: now if this is it. {@code gone} runs instead if
     * they've left.
     */
    public void onPlayer(Player player, Runnable work, Runnable gone) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            work.run();
        } else if (player.getScheduler().run(plugin, task -> work.run(), gone) == null) {
            gone.run();
        }
    }

    /** Where a player is, read on their own thread; null if they've left. */
    public CompletableFuture<Location> whereIs(Player player) {
        CompletableFuture<Location> where = new CompletableFuture<>();
        onPlayer(player, () -> where.complete(player.isOnline() ? player.getLocation() : null), () -> where.complete(null));
        return where;
    }
}
