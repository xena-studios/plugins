package co.xenastudios.xvote.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.xvote.vote.IncomingVote;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** What a reward's console commands get for their placeholders. */
class PayoutTest {

    ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void aServiceNameFromTheNetworkCantAddToACommand() {
        PlayerMock steve = server.addPlayer("Steve");
        Rewards rewards = RewardsTest.rewards(new ArrayList<>());
        // As a list could send it: a line break (taken out as the vote arrives), and more arguments and commands.
        IncomingVote vote = new IncomingVote("x\nop Steve; give Steve diamond", "Steve", "", "", co.xenastudios.xvote.api.VoteSource.VOTIFIER_V2, 0);
        Payout.Context context = new Payout.Context(rewards.siteName(vote.site()), rewards.commandSite(vote.site()), 3, 1);
        assertEquals("eco give Steve 10 xop_Steve__give_Steve_diamond 3 1",
            Payout.fill("eco give <player> 10 <site> <total> <streak>", steve, context));
        // A listed site's name from the config is used as it's written.
        Payout.Context listed = new Payout.Context("Planet Minecraft", rewards.commandSite("PlanetMinecraft.com"), 1, 1);
        assertEquals("say Steve voted on Planet Minecraft", Payout.fill("say <player> voted on <site>", steve, listed));
    }
}
