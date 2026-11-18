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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** xTeleport on a mock server: homes, TPA, warps and spawn, through to the teleport. */
class XTeleportTest extends ServerTest {

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
    void homeLimitsAreKeptForPlaceholdersButSettingAHomeChecksAgain() {
        assertEquals(3, plugin.homeLimits().cached(steve, plugin.config().homes()));
        steve.addAttachment(plugin, "xteleport.homes.10", true);
        assertEquals(3, plugin.homeLimits().cached(steve, plugin.config().homes()), "from memory, for a while");
        for (String name : List.of("a", "b", "c", "d")) {
            steve.performCommand("sethome " + name);
            told(steve, "Set home " + name);
        }
        assertEquals(10, plugin.homeLimits().cached(steve, plugin.config().homes()), "worked out again by /sethome");
        set("homes.default-limit", 20);
        assertEquals(20, plugin.homeLimits().cached(steve, plugin.config().homes()), "forgotten on reload");
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
    void askingAgainDoesntNotifyAgain() {
        alex.performCommand("tpa Steve");
        told(steve, "Alex wants to teleport to you");
        alex.performCommand("tpa Steve");
        told(alex, "You already asked Steve");
        alex.performCommand("tpahere Steve");
        told(alex, "You already asked Steve");
        assertTrue(drain(steve).isEmpty(), "Steve was told once");
        alex.performCommand("tpacancel Steve");
        told(alex, "Took back 1");
        alex.performCommand("tpahere Steve");
        told(steve, "Alex wants you to teleport to them");
    }

    @Test
    void tpahereRefusesToBringSomeoneIntoLava() {
        // Alex stands in a lava pool's edge: lava underfoot.
        world.getBlockAt(70, 63, 70).setType(Material.LAVA);
        world.getBlockAt(70, 64, 70).setType(Material.AIR);
        world.getBlockAt(70, 65, 70).setType(Material.AIR);
        alex.teleport(new Location(world, 70.5, 64, 70.5));
        alex.performCommand("tpahere Steve");
        told(steve, "Alex wants you to teleport to them");
        steve.performCommand("tpaccept");
        told(steve, "isn't safe");
        assertTrue(at(steve, new Location(world, 0, 64, 0)), "Steve stayed where he was");
    }

    @Test
    void tpaRefusesToSendSomeoneIntoTheAir() {
        // Steve is flying ten blocks up: arriving there would be a fall.
        world.getBlockAt(0, 63, 0).setType(Material.AIR);
        steve.teleport(new Location(world, 0.5, 74, 0.5));
        alex.performCommand("tpa Steve");
        told(steve, "wants to teleport");
        steve.performCommand("tpaccept");
        told(alex, "isn't safe");
        assertTrue(at(alex, new Location(world, 50, 64, 50)));
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

    @Test
    void anApiTeleportOnACommandsKeySharesItsCooldown() {
        set("commands.spawn.cooldown", 60);
        alex.performCommand("spawn");
        told(alex, "Welcome to spawn");
        CompletableFuture<TeleportResult> again = XTeleportApi.get().teleport(alex, new Location(world, 0.5, 64, 0.5),
            TeleportOptions.defaults().cooldown("spawn", 0));
        await(again::isDone);
        assertEquals(TeleportResult.ON_COOLDOWN, again.join(), "a 0-second cooldown still checks the key");
    }

    @Test
    void stoppingFinishesTeleportsStillWaiting() {
        PlayerMock sam = new TickingPlayer(server, "Sam");
        server.addPlayer(sam);
        sam.teleport(floor(5, 5));
        CompletableFuture<TeleportResult> waiting = XTeleportApi.get().teleport(sam, new Location(world, 0.5, 64, 0.5),
            TeleportOptions.defaults().warmup(30));
        assertFalse(waiting.isDone());
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(TeleportResult.CANCELLED, waiting.getNow(null));
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
