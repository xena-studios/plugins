package co.xenastudios.xvote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xvote.api.Period;
import co.xenastudios.xvote.api.VoteResult;
import co.xenastudios.xvote.api.XVoteApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** xVote on a mock server: the commands, a vote through the API, and the leaderboard. */
class XVoteTest {

    ServerMock server;
    XVote plugin;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(XVote.class);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private void await(BooleanSupplier condition) {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) {
            lines.add(line.replaceAll("§.", ""));
        }
        return lines;
    }

    @Test
    void aVoteIsCountedPaidAndRanked() throws Exception {
        assertTrue(plugin.running());
        PlayerMock steve = server.addPlayer("Steve");
        XVoteApi api = XVoteApi.get();
        assertNotNull(api);
        assertEquals(VoteResult.COUNTED, api.submitVote("PlanetMinecraft.com", "Steve", "").get(10, TimeUnit.SECONDS));
        await(() -> api.stats(steve.getUniqueId()).total() == 1);
        assertEquals(1, api.stats(steve.getUniqueId()).today());
        assertEquals(1, api.stats(steve.getUniqueId()).streak());
        assertEquals("Steve", api.top(Period.MONTH, 5).get(0).name());

        // The thank-you message reaches Steve on his own thread.
        await(() -> said(steve).stream().anyMatch(line -> line.contains("Thanks for voting")));

        steve.performCommand("vote");
        List<String> list = said(steve);
        assertTrue(list.stream().anyMatch(line -> line.contains("Planet Minecraft") && line.contains("again in")), list.toString());
        assertTrue(list.stream().anyMatch(line -> line.contains("Minecraft-MP") && line.contains("click to vote")), list.toString());
        assertTrue(list.stream().anyMatch(line -> line.contains("1 of 2 sites are open")), list.toString());

        steve.performCommand("vote top");
        List<String> top = said(steve);
        assertTrue(top.stream().anyMatch(line -> line.contains("#1") && line.contains("Steve")), top.toString());

        assertEquals(VoteResult.DUPLICATE, api.submitVote("PlanetMinecraft.com", "Steve", "").get(10, TimeUnit.SECONDS));
        assertEquals(VoteResult.WAITING, api.submitVote("Some List", "Nobody", "").get(10, TimeUnit.SECONDS));
    }

    @Test
    void rewardTypesFromOtherPlugins() throws Exception {
        PlayerMock alex = server.addPlayer("Alex");
        List<String> given = new ArrayList<>();
        XVoteApi.get().registerRewardType(plugin, "crate-key", (player, argument) -> given.add(player.getName() + " " + argument));
        assertEquals("Alex", alex.getName());
        // Reward types are only used through the config; this checks registering and ids.
        try {
            XVoteApi.get().registerRewardType(plugin, "bad id", (player, argument) -> {});
            throw new AssertionError("a bad id was taken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bad id"));
        }
        assertTrue(given.isEmpty());
    }

    @Test
    void staffCommands() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        admin.performCommand("vote top week");
        assertTrue(said(admin).stream().anyMatch(line -> line.contains("No votes this week yet")));
        admin.performCommand("xvote party");
        await(() -> said(admin).stream().anyMatch(line -> line.contains("The vote party is off")));
        PlayerMock player = server.addPlayer("Player");
        player.performCommand("vote test Steve");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("permission")));
    }
}
