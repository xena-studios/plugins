package co.xenastudios.xteleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.TeleportOptions;
import co.xenastudios.xteleport.api.TeleportResult;
import co.xenastudios.xteleport.api.XTeleportApi;
import co.xenastudios.xteleport.api.event.HomeSetEvent;
import co.xenastudios.xteleport.api.event.PreTeleportEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** xTeleport on a mock server: homes, TPA, warps and spawn, through to the teleport. */
class XTeleportTest {

    ServerMock server;
    WorldMock world;
    XTeleport plugin;
    PlayerMock steve;
    PlayerMock alex;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(TestXTeleport.class);
        await(plugin::ready);
        // MockBukkit can't say where a player's bed is.
        set("homes.bed-home", false);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        // Warmups are covered by the core's tests; here teleports go at once.
        steve.addAttachment(plugin, "xteleport.warmup.bypass", true);
        alex.addAttachment(plugin, "xteleport.warmup.bypass", true);
        await(() -> plugin.homes().cached(steve.getUniqueId()).isPresent() && plugin.homes().cached(alex.getUniqueId()).isPresent());
        steve.teleport(floor(0, 0));
        alex.teleport(floor(50, 50));
        drain(steve);
        drain(alex);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    /** A safe spot: stone underfoot, air at the feet and head. */
    Location floor(int x, int z) {
        world.getBlockAt(x, 63, z).setType(Material.STONE);
        world.getBlockAt(x, 64, z).setType(Material.AIR);
        world.getBlockAt(x, 65, z).setType(Material.AIR);
        return new Location(world, x + 0.5, 64, z + 0.5);
    }

    void await(BooleanSupplier condition) {
        await(condition, () -> "");
    }

    void await(BooleanSupplier condition, java.util.function.Supplier<String> seen) {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, () -> "timed out" + seen.get());
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Changes a setting in config.yml and reloads, as /xteleport reload would. */
    void set(String path, Object value) {
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        plugin.editConfig(yaml -> {
            yaml.set(path, value);
            return true;
        }, reason -> done.set(true));
        await(done::get);
    }

    static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    List<String> drain(PlayerMock player) {
        List<String> all = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            all.add(text(next));
        }
        return all;
    }

    final java.util.Map<PlayerMock, List<String>> unread = new java.util.HashMap<>();

    /** Waits until the player has been told something containing the text; what came after it is kept for the next call. */
    void told(PlayerMock player, String part) {
        List<String> seen = unread.computeIfAbsent(player, p -> new ArrayList<>());
        await(() -> {
            seen.addAll(drain(player));
            return seen.stream().anyMatch(line -> line.contains(part));
        }, () -> " waiting for \"" + part + "\"; " + player.getName() + " was told " + seen);
        int at = 0;
        while (!seen.get(at).contains(part)) {
            at++;
        }
        seen.subList(0, at + 1).clear();
    }

    boolean at(PlayerMock player, Location where) {
        return player.getLocation().getBlockX() == where.getBlockX() && player.getLocation().getBlockZ() == where.getBlockZ();
    }

    @Test
    void setsGoesToAndDeletesHomes() {
        steve.performCommand("sethome base");
        told(steve, "Set home base");
        steve.teleport(floor(20, 20));
        steve.performCommand("home base");
        told(steve, "Welcome to base");
        assertTrue(at(steve, new Location(world, 0, 64, 0)));
        steve.performCommand("homes");
        told(steve, "Your homes (1/3)");
        steve.performCommand("delhome BASE");
        told(steve, "Deleted home base");
        steve.performCommand("home base");
        told(steve, "no home called base");
    }

    @Test
    void oneHomeNeedsNoName() {
        steve.performCommand("sethome cabin");
        told(steve, "Set home cabin");
        steve.teleport(floor(30, 30));
        steve.performCommand("home");
        told(steve, "Welcome to cabin");
    }

    @Test
    void limitsAndNames() {
        for (String name : List.of("a", "b", "c")) {
            steve.performCommand("sethome " + name);
            told(steve, "Set home " + name);
        }
        steve.performCommand("sethome d");
        told(steve, "You can have 3 homes");
        steve.performCommand("sethome a");
        told(steve, "Moved home a");
        steve.addAttachment(plugin, "xteleport.homes.10", true);
        steve.performCommand("sethome d");
        told(steve, "Set home d");
        set("homes.bed-home", true);
        steve.performCommand("sethome bed");
        told(steve, "your bed's name");
        steve.performCommand("sethome no!");
        told(steve, "1 to 32 letters");
    }

    @Test
    void refusesAnUnsafeHome() {
        steve.performCommand("sethome lava");
        told(steve, "Set home lava");
        world.getBlockAt(0, 64, 0).setType(Material.LAVA);
        steve.teleport(floor(20, 20));
        steve.performCommand("home lava");
        told(steve, "isn't safe");
        assertTrue(at(steve, new Location(world, 20, 64, 20)));
    }

    @Test
    void staffUseOtherPlayersHomes() {
        alex.performCommand("sethome hideout");
        told(alex, "Set home hideout");
        steve.performCommand("home Alex:hideout");
        told(steve, "You don't have permission");
        steve.setOp(true);
        steve.performCommand("homes Alex");
        told(steve, "Alex's homes (1)");
        steve.performCommand("home Alex:hideout");
        told(steve, "Welcome to hideout");
        assertTrue(at(steve, new Location(world, 50, 64, 50)));
        steve.performCommand("delhome Alex:hideout");
        told(steve, "Deleted Alex's home hideout");
        assertTrue(plugin.homes().cached(alex.getUniqueId()).orElseThrow().homes().isEmpty());
    }

    @Test
    void tpaAskAcceptArrive() {
        alex.performCommand("tpa Steve");
        told(alex, "Asked Steve");
        told(steve, "Alex wants to teleport to you");
        steve.performCommand("tpaccept");
        told(alex, "Steve accepted");
        told(alex, "Teleported to Steve");
        assertTrue(at(alex, steve.getLocation()));
    }

    @Test
    void tpahereBringsTheTargetOver() {
        alex.performCommand("tpahere Steve");
        told(steve, "Alex wants you to teleport to them");
        steve.performCommand("tpaccept Alex");
        told(steve, "Teleported to Alex");
        assertTrue(at(steve, alex.getLocation()));
    }

    @Test
    void tpaDenyCancelToggle() {
        alex.performCommand("tpa Steve");
        told(steve, "wants to teleport");
        steve.performCommand("tpdeny");
        told(alex, "Steve denied");
        steve.performCommand("tpaccept");
        told(steve, "no teleport requests");

        alex.performCommand("tpa Steve");
        told(steve, "wants to teleport");
        alex.performCommand("tpacancel");
        told(steve, "Alex took back");
        told(alex, "Took back 1");

        steve.performCommand("tptoggle");
        told(steve, "won't get teleport requests");
        alex.performCommand("tpa Steve");
        told(alex, "isn't taking teleport requests");
        alex.performCommand("tpa Alex");
        told(alex, "can't ask yourself");
    }

    @Test
    void vanishedPlayersCantBeAsked() {
        alex.hidePlayer(plugin, steve);
        alex.performCommand("tpa Steve");
        told(alex, "No player named Steve");
    }

    @Test
    void warpsAndSpawn() {
        steve.setOp(true);
        steve.performCommand("setwarp Shop");
        told(steve, "Set warp Shop");
        alex.performCommand("warps");
        told(alex, "Warps (1)");
        alex.performCommand("warp shop");
        told(alex, "Welcome to Shop");
        assertTrue(at(alex, new Location(world, 0, 64, 0)));
        steve.performCommand("delwarp shop");
        told(steve, "Deleted warp Shop");
        alex.performCommand("warp shop");
        told(alex, "no warp called shop");

        steve.teleport(floor(-40, -40));
        steve.performCommand("setspawn");
        told(steve, "Set the spawn");
        alex.performCommand("spawn");
        told(alex, "Welcome to spawn");
        assertTrue(at(alex, new Location(world, -40, 64, -40)));
    }

    @Test
    void perWarpPermissionsHideWarps() {
        steve.setOp(true);
        steve.performCommand("setwarp vault");
        told(steve, "Set warp vault");
        set("warps.per-warp-permission", true);
        alex.performCommand("warp vault");
        told(alex, "no warp called vault");
        alex.addAttachment(plugin, "xteleport.warp.vault", true);
        alex.performCommand("warp vault");
        told(alex, "Welcome to vault");
    }

    @Test
    void cooldownsAfterArriving() {
        set("commands.spawn.cooldown", 60);
        alex.performCommand("spawn");
        told(alex, "Welcome to spawn");
        alex.performCommand("spawn");
        told(alex, "You can do that again in 1m");
    }

    public static final class Refuser implements Listener {
        @EventHandler
        void onHome(HomeSetEvent event) {
            event.setCancelled(true);
        }

        @EventHandler
        void onTeleport(PreTeleportEvent event) {
            event.setCancelled(true);
        }
    }

    @Test
    void pluginsCanRefuseHomesAndTeleports() {
        steve.performCommand("sethome base");
        told(steve, "Set home base");
        server.getPluginManager().registerEvents(new Refuser(), plugin);
        steve.performCommand("sethome other");
        steve.performCommand("homes");
        told(steve, "Your homes (1/3)");
        steve.teleport(floor(20, 20));
        CompletableFuture<TeleportResult> result = XTeleportApi.get().teleport(steve, new Location(world, 0.5, 64, 0.5),
            TeleportOptions.defaults());
        await(result::isDone);
        assertEquals(TeleportResult.BLOCKED, result.join());
    }

    @Test
    void apiReadsAndChanges() {
        XTeleportApi api = XTeleportApi.get();
        assertTrue(api.setHome(steve.getUniqueId(), "fromApi", new Location(world, 0.5, 64, 0.5)).join());
        List<Home> homes = api.cachedHomes(steve.getUniqueId()).orElseThrow();
        assertEquals("fromApi", homes.getFirst().name());
        assertFalse(api.setHome(steve.getUniqueId(), "bad name", steve.getLocation()).join());
        assertEquals(3, api.homeLimit(steve));
        assertTrue(api.setWarp("arena", new Location(world, 0.5, 64, 0.5)).join());
        assertEquals("arena", api.warp("ARENA").orElseThrow().name());
        assertNotNull(api.spawn().orElse(null));
        CompletableFuture<TeleportResult> moved = api.teleport(alex, new Location(world, 0.5, 64, 0.5),
            TeleportOptions.defaults().cooldown("plothome", 30));
        await(moved::isDone);
        assertEquals(TeleportResult.TELEPORTED, moved.join());
        assertTrue(api.cooldown(alex.getUniqueId(), "plothome").getSeconds() > 25);
    }
}
