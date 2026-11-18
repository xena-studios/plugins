package co.xenastudios.xteleport.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.Warp;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store on a real SQLite file: names keyed in any case, written in order, imports that keep what's there. */
class StoreTest {

    @TempDir
    Path folder;
    Store store;
    final UUID steve = UUID.randomUUID();

    @BeforeEach
    void open() {
        store = Store.open(StorageSettings.defaults("xteleport_"), folder, Logger.getLogger("test"));
        store.opened().join();
    }

    @AfterEach
    void close() {
        store.close(5, TimeUnit.SECONDS);
    }

    Home home(String name, double x) {
        return new Home(steve, name, "world", x, 64, 0, 90, 0);
    }

    @Test
    void homesAreKeptByNameInAnyCase() {
        store.saveHome(home("Base", 1));
        store.saveHome(home("farm", 2));
        store.saveHome(home("base", 3));
        Store.PlayerData data = store.loadPlayer(steve).join();
        assertEquals(List.of("base", "farm"), data.homes().stream().map(Home::name).toList());
        assertEquals(3, data.homes().getFirst().x());
        assertTrue(store.deleteHome(steve, "FARM").join());
        assertFalse(store.deleteHome(steve, "farm").join());
        assertEquals(1, store.loadPlayer(steve).join().homes().size());
    }

    @Test
    void tpaSettingIsKept() {
        assertFalse(store.loadPlayer(steve).join().tpaOff());
        store.setTpaOff(steve, true);
        assertTrue(store.loadPlayer(steve).join().tpaOff());
        store.setTpaOff(steve, false);
        assertFalse(store.loadPlayer(steve).join().tpaOff());
    }

    @Test
    void warpsAndSpawn() {
        assertNull(store.loadServer().join().spawn());
        store.saveWarp(new Warp("Shop", "world", 1, 2, 3, 0, 0));
        store.saveSpawn(new Warp("ignored", "world", 0.5, 65, 0.5, 180, 0));
        Store.ServerData data = store.loadServer().join();
        assertEquals("Shop", data.warps().getFirst().name());
        assertEquals("spawn", data.spawn().name());
        assertTrue(store.deleteWarp("shop").join());
        assertTrue(store.loadServer().join().warps().isEmpty());
    }

    @Test
    void importsKeepWhatIsAlreadyThere() {
        store.saveHome(home("base", 1));
        store.saveSpawn(new Warp("spawn", "world", 0, 64, 0, 0, 0));
        Store.Imported imported = store.importAll(
            List.of(home("Base", 9), home("farm", 2), home("farm", 3)),
            List.of(new Warp("shop", "world", 1, 2, 3, 0, 0)),
            new Warp("spawn", "world", 100, 64, 100, 0, 0)).join();
        assertEquals(1, imported.homes());
        assertEquals(1, imported.warps());
        assertFalse(imported.spawn());
        assertEquals(2, imported.skipped());
        assertEquals(1, store.loadPlayer(steve).join().homes().getFirst().x(), "the home already here is kept");
        assertEquals(0, store.loadServer().join().spawn().x());
    }

    @Test
    void reopensWhatWasWritten() {
        store.saveHome(home("base", 1));
        store.close(5, TimeUnit.SECONDS);
        store = Store.open(StorageSettings.defaults("xteleport_"), folder, Logger.getLogger("test"));
        assertEquals(1, store.loadPlayer(steve).join().homes().size());
    }
}
