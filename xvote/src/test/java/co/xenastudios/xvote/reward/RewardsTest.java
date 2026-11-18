package co.xenastudios.xvote.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.vote.Facts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which rewards a vote earns, from the config's triggers. */
class RewardsTest {

    static XVConfig.Reward reward() {
        return new XVConfig.Reward(100, 0, List.of(), List.of(), Message.empty(), Message.empty(), SoundEffect.NONE, List.of());
    }

    static Rewards rewards(List<String> warnings) {
        XVConfig d = XVConfig.DEFAULTS;
        Map<String, XVConfig.Reward> rewards = new LinkedHashMap<>();
        for (String name : List.of("vote", "pmc", "daily", "tenth", "week", "hundred")) {
            rewards.put(name, reward());
        }
        Map<String, XVConfig.Site> sites = Map.of("pmc", new XVConfig.Site("Planet Minecraft", "PlanetMinecraft.com", "https://x", 24,
            List.of("pmc", "missing")));
        XVConfig.Triggers triggers = new XVConfig.Triggers(List.of("vote"), List.of("daily"),
            Map.of("10", new XVConfig.Milestone(List.of("tenth")), "zero", new XVConfig.Milestone(List.of("vote"))),
            Map.of("7", new XVConfig.Milestone(List.of("week"))), Map.of("100", new XVConfig.Milestone(List.of("hundred"))));
        XVConfig config = new XVConfig(d.theme(), d.messages(), d.receiver(), sites, rewards, triggers, d.party(), d.reminders(),
            d.counting(), d.storage(), d.network(), d.updateChecker());
        return Rewards.of(config, item -> {
            throw new IllegalArgumentException("no items in tests");
        }, warnings::add);
    }

    static Facts facts(String site, int number, int streak, boolean first) {
        return new Facts(1, UUID.randomUUID(), "Steve", site, number, streak, first, 0);
    }

    @Test
    void triggers() {
        List<String> warnings = new ArrayList<>();
        Rewards rewards = rewards(warnings);
        assertEquals(List.of("vote"), rewards.earned(facts("Other", 3, 1, false)));
        assertEquals(List.of("vote", "pmc", "daily"), rewards.earned(facts("planetminecraft.com", 3, 1, true)));
        assertEquals(List.of("vote", "tenth"), rewards.earned(facts("Other", 20, 2, false)));
        // A streak milestone is reached by the day's first vote, not by later ones that day.
        assertEquals(List.of("vote", "daily", "week"), rewards.earned(facts("Other", 7, 7, true)));
        assertEquals(List.of("vote"), rewards.earned(facts("Other", 8, 7, false)));
        assertEquals(List.of("vote", "tenth", "hundred"), rewards.earned(facts("Other", 100, 1, false)));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("\"missing\"")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("triggers.every.zero")), warnings.toString());
    }

    @Test
    void sites() {
        Rewards rewards = rewards(new ArrayList<>());
        assertEquals("Planet Minecraft", rewards.siteName("PLANETMINECRAFT.COM"));
        assertEquals("Some List", rewards.siteName("Some List"));
        assertEquals(24 * 3_600_000L, rewards.cooldown("PlanetMinecraft.com"));
        assertEquals(0, rewards.cooldown("Some List"));
        assertEquals("PlanetMinecraft.com", rewards.siteByKey("PMC").service());
    }

    @Test
    void theDefaultsHaveNothingToWarnAbout() {
        List<String> warnings = new ArrayList<>();
        // Items and sounds need a server to check; boot-checks.txt covers those on real ones.
        Rewards.of(XVConfig.DEFAULTS, item -> {
            throw new IllegalArgumentException("no items in tests");
        }, warnings::add);
        assertEquals(List.of(), warnings.stream().filter(w -> !w.contains(".items[") && !w.contains(".sound")).toList());
    }
}
