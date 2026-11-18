package co.xenastudios.xteleport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** A mock server with xTeleport, a world and two players, Steve and Alex, standing on stone. */
abstract class ServerTest {

    ServerMock server;
    WorldMock world;
    TestXTeleport plugin;
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
        // Steve and Alex skip warmups; a TickingPlayer waits them out.
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

}
