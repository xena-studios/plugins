package co.xenastudios.xteleport;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /rtp's two cooldowns: a short one each search starts, found or not, and the full one on arrival. */
class RtpCommandTest extends ServerTest {

    @BeforeEach
    void nearby() {
        // The mock world is grass at y 4 everywhere, and plains.
        set("rtp.worlds.world.min-radius", 10);
        set("rtp.worlds.world.max-radius", 100);
    }

    @Test
    void aSearchThatFindsNothingStillStartsTheSearchCooldown() {
        set("rtp.blocked-biomes", List.of("plains"));
        steve.performCommand("rtp");
        told(steve, "Looking for a safe spot");
        told(steve, "Couldn't find a safe spot");
        steve.performCommand("rtp");
        told(steve, "You can do that again in 30s");
    }

    @Test
    void movingDuringTheWarmupStillStartsTheSearchCooldown() {
        PlayerMock sam = new TickingPlayer(server, "Sam");
        server.addPlayer(sam);
        sam.teleport(floor(-5, -5));
        sam.performCommand("rtp");
        told(sam, "Teleporting in 3s");
        sam.teleport(floor(-60, -60));
        told(sam, "you moved");
        sam.performCommand("rtp");
        told(sam, "You can do that again in 30s");
    }

    @Test
    void arrivingStartsTheFullCooldown() {
        set("rtp.search-cooldown", 0);
        steve.performCommand("rtp");
        told(steve, "Teleported to");
        steve.performCommand("rtp");
        told(steve, "You can do that again in 5m");
    }

    @Test
    void theBypassSkipsBoth() {
        steve.addAttachment(plugin, "xteleport.cooldown.bypass", true);
        set("rtp.blocked-biomes", List.of("plains"));
        steve.performCommand("rtp");
        told(steve, "Couldn't find a safe spot");
        steve.performCommand("rtp");
        told(steve, "Looking for a safe spot");
    }
}
