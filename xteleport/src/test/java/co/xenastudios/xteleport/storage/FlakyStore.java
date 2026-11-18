package co.xenastudios.xteleport.storage;

import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.Warp;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * A real store whose writes can be made to fail, as a full disk or a lost MySQL would, and
 * whose player reads can be held on the store's thread, to test what happens around them.
 */
public final class FlakyStore extends Store {

    /** While true, every write fails, off the calling thread, without touching the database. */
    public volatile boolean failWrites;
    /** While set, a player read that has finished waits on the store's thread for this to complete. */
    public volatile CompletableFuture<Void> holdReads;

    public FlakyStore(StorageSettings settings, Path folder, Logger logger) {
        super(logger);
        start(settings, folder);
    }

    // Only when failing: the real write is never queued then.
    private <T> CompletableFuture<T> failing() {
        return CompletableFuture.supplyAsync(() -> {
            throw new CompletionException(new SQLException("the disk is full"));
        });
    }

    @Override
    public CompletableFuture<PlayerData> loadPlayer(UUID player) {
        CompletableFuture<Void> hold = holdReads;
        CompletableFuture<PlayerData> read = super.loadPlayer(player);
        if (hold == null) {
            return read;
        }
        CompletableFuture<PlayerData> held = new CompletableFuture<>();
        read.whenComplete((data, error) -> {
            hold.join();
            if (error != null) {
                held.completeExceptionally(error);
            } else {
                held.complete(data);
            }
        });
        return held;
    }

    @Override
    public CompletableFuture<Boolean> saveHome(Home home) {
        return failWrites ? failing() : super.saveHome(home);
    }

    @Override
    public CompletableFuture<Boolean> deleteHome(UUID owner, String name) {
        return failWrites ? failing() : super.deleteHome(owner, name);
    }

    @Override
    public CompletableFuture<Boolean> setTpaOff(UUID player, boolean off) {
        return failWrites ? failing() : super.setTpaOff(player, off);
    }

    @Override
    public CompletableFuture<Boolean> saveWarp(Warp warp) {
        return failWrites ? failing() : super.saveWarp(warp);
    }

    @Override
    public CompletableFuture<Boolean> deleteWarp(String name) {
        return failWrites ? failing() : super.deleteWarp(name);
    }

    @Override
    public CompletableFuture<Boolean> saveSpawn(Warp spawn) {
        return failWrites ? failing() : super.saveSpawn(spawn);
    }
}
