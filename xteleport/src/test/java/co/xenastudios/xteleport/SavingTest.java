package co.xenastudios.xteleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.XTeleportApi;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

/** What players and plugins are told when the database can't save, and changes made while a player's homes are being read. */
class SavingTest extends ServerTest {

    boolean hasHome(String name) {
        return plugin.homes().cached(steve.getUniqueId()).orElseThrow().home(name).isPresent();
    }

    @Test
    void aHomeThatCouldntBeSavedIsntKept() {
        plugin.flakyStore().failWrites = true;
        steve.performCommand("sethome base");
        told(steve, "Couldn't save that");
        assertFalse(hasHome("base"), "taken back out of memory");
        assertTrue(drain(steve).stream().noneMatch(line -> line.contains("Set home")), "never told it was set");

        plugin.flakyStore().failWrites = false;
        steve.performCommand("sethome base");
        told(steve, "Set home base");
        plugin.flakyStore().failWrites = true;
        steve.teleport(floor(10, 10));
        steve.performCommand("sethome base");
        told(steve, "Couldn't save that");
        Home kept = plugin.homes().cached(steve.getUniqueId()).orElseThrow().home("base").orElseThrow();
        assertEquals(0, (int) Math.floor(kept.x()), "moving it failed, so it's where it was");
        steve.performCommand("delhome base");
        told(steve, "Couldn't save that");
        assertTrue(hasHome("base"), "deleting it failed, so it's still there");

        plugin.flakyStore().failWrites = false;
        plugin.homes().forget(steve.getUniqueId());
        await(() -> plugin.homes().of(steve.getUniqueId()).isDone());
        assertEquals(0, (int) Math.floor(plugin.homes().cached(steve.getUniqueId()).orElseThrow().home("base").orElseThrow().x()),
            "and the database agrees");
    }

    @Test
    void tptoggleThatCouldntBeSavedChangesNothing() {
        plugin.flakyStore().failWrites = true;
        steve.performCommand("tptoggle");
        told(steve, "Couldn't save that");
        assertFalse(plugin.homes().tpaOff(steve.getUniqueId()));
        alex.performCommand("tpa Steve");
        told(steve, "Alex wants to teleport to you");
    }

    @Test
    void warpsAndTheSpawnThatCouldntBeSavedArentKept() {
        steve.setOp(true);
        plugin.flakyStore().failWrites = true;
        steve.performCommand("setwarp shop");
        told(steve, "Couldn't save that");
        assertTrue(plugin.places().warp("shop").isEmpty());
        steve.performCommand("setspawn");
        told(steve, "Couldn't save that");
        assertNull(plugin.places().setSpawn());

        plugin.flakyStore().failWrites = false;
        steve.performCommand("setwarp shop");
        told(steve, "Set warp shop");
        plugin.flakyStore().failWrites = true;
        steve.performCommand("delwarp shop");
        told(steve, "Couldn't save that");
        assertTrue(plugin.places().warp("shop").isPresent(), "deleting it failed, so it's still there");
    }

    @Test
    void pluginsGetAnErrorWhenTheDatabaseCantSave() {
        XTeleportApi api = XTeleportApi.get();
        plugin.flakyStore().failWrites = true;
        CompletableFuture<Boolean> home = api.setHome(steve.getUniqueId(), "fromApi", new Location(world, 0.5, 64, 0.5));
        assertThrows(CompletionException.class, home::join);
        assertTrue(api.cachedHomes(steve.getUniqueId()).orElseThrow().isEmpty());
        CompletableFuture<Boolean> warp = api.setWarp("arena", new Location(world, 0.5, 64, 0.5));
        assertThrows(CompletionException.class, warp::join);
        assertTrue(api.warp("arena").isEmpty());
        assertThrows(CompletionException.class, () -> done(api.deleteHome(steve.getUniqueId(), "anything")));
    }

    @Test
    void aHomeSetWhileTheirHomesAreBeingReadIsKept() {
        // Steve rejoins: his homes are being read, and the read has finished but not been
        // stored yet, when a plugin sets a home for him.
        plugin.homes().forget(steve.getUniqueId());
        CompletableFuture<Void> hold = new CompletableFuture<>();
        plugin.flakyStore().holdReads = hold;
        plugin.homes().of(steve.getUniqueId());
        CompletableFuture<Boolean> set = XTeleportApi.get().setHome(steve.getUniqueId(), "early", new Location(world, 0.5, 64, 0.5));
        plugin.flakyStore().holdReads = null;
        hold.complete(null);
        assertTrue(done(set));
        assertTrue(hasHome("early"), "the older read didn't wipe it out");

        // And one deleted while being read stays deleted.
        plugin.homes().forget(steve.getUniqueId());
        CompletableFuture<Void> again = new CompletableFuture<>();
        plugin.flakyStore().holdReads = again;
        plugin.homes().of(steve.getUniqueId());
        CompletableFuture<Boolean> deleted = XTeleportApi.get().deleteHome(steve.getUniqueId(), "early");
        plugin.flakyStore().holdReads = null;
        again.complete(null);
        assertTrue(done(deleted));
        assertFalse(hasHome("early"));
    }
}
