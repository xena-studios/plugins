package co.xenastudios.xvote.reward;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.vote.Facts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.inventory.ItemStack;

/**
 * The sites, rewards and triggers from the config, read once per load: items parsed,
 * names checked, so a vote only looks things up. Never changed; a reload makes a new one.
 */
public final class Rewards {

    /** A site from the config. */
    public record Site(String key, String name, String service, String link, long cooldownMs, List<String> rewards) {}

    /** A reward from the config, ready to give. Items are copied each time they're given. */
    public record Reward(String name, double chance, double money, List<ItemStack> items, List<String> commands,
                         Message message, Message broadcast, SoundEffect sound, List<String> custom) {}

    private final List<Site> sites;
    private final Map<String, Site> byService;
    private final Map<String, Site> byKey;
    private final Map<String, Reward> rewards;
    private final List<String> everyVote;
    private final List<String> firstOfDay;
    private final Map<Integer, List<String>> every;
    private final Map<Integer, List<String>> streak;
    private final Map<Integer, List<String>> total;
    private final List<String> party;
    private final long longestCooldown;
    private final long unlistedCooldown;

    private Rewards(List<Site> sites, Map<String, Reward> rewards, XVConfig.Triggers triggers, List<String> party,
                    long unlistedCooldown, Consumer<String> warn) {
        this.sites = List.copyOf(sites);
        Map<String, Site> services = new LinkedHashMap<>();
        Map<String, Site> keys = new LinkedHashMap<>();
        long longest = 0;
        for (Site site : sites) {
            if (services.putIfAbsent(site.service().toLowerCase(Locale.ROOT), site) != null) {
                warn.accept("sites." + site.key() + " has the same service as another site, so it's never matched.");
            }
            keys.put(site.key().toLowerCase(Locale.ROOT), site);
            longest = Math.max(longest, site.cooldownMs());
        }
        this.byService = services;
        this.byKey = keys;
        this.unlistedCooldown = unlistedCooldown;
        this.longestCooldown = Math.max(longest, unlistedCooldown);
        this.rewards = rewards;
        this.everyVote = known(triggers.everyVote(), "triggers.every-vote", warn);
        this.firstOfDay = known(triggers.firstVoteOfDay(), "triggers.first-vote-of-day", warn);
        this.every = numbered(triggers.every(), "triggers.every", warn);
        this.streak = numbered(triggers.streak(), "triggers.streak", warn);
        this.total = numbered(triggers.total(), "triggers.total", warn);
        this.party = known(party, "party.rewards", warn);
        for (Site site : sites) {
            known(site.rewards(), "sites." + site.key() + ".rewards", warn);
        }
    }

    /**
     * Reads the config's sites, rewards and triggers.
     *
     * @param items turns an item as written ("diamond 3") into an item, or throws
     *     {@link IllegalArgumentException} with the reason
     * @param warn told about anything that's skipped, for the console
     */
    public static Rewards of(XVConfig config, Function<String, ItemStack> items, Consumer<String> warn) {
        List<Site> sites = new ArrayList<>();
        config.sites().forEach((key, site) -> {
            String service = site.service().isBlank() ? key : site.service().strip();
            sites.add(new Site(key, site.name().isBlank() ? key : site.name(), service, site.link().strip(),
                site.cooldownHours() * 3_600_000L, site.rewards()));
        });
        Map<String, Reward> rewards = new LinkedHashMap<>();
        config.rewards().forEach((name, reward) -> {
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < reward.items().size(); i++) {
                String written = reward.items().get(i);
                try {
                    stacks.add(items.apply(written));
                } catch (IllegalArgumentException e) {
                    warn.accept("rewards." + name + ".items[" + i + "] (\"" + written + "\") " + e.getMessage() + ", so it's skipped.");
                }
            }
            if (!reward.sound().isKnown()) {
                warn.accept("rewards." + name + ".sound (\"" + reward.sound() + "\") isn't a sound this server knows;"
                    + " it plays only with a resource pack that adds it.");
            }
            rewards.put(name, new Reward(name, reward.chance(), reward.money(), List.copyOf(stacks), reward.commands(),
                reward.message(), reward.broadcast(), reward.sound(), reward.custom()));
        });
        return new Rewards(sites, rewards, config.triggers(), config.party().rewards(),
            config.counting().unlistedCooldownHours() * 3_600_000L, warn);
    }

    private List<String> known(List<String> names, String path, Consumer<String> warn) {
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            if (rewards.containsKey(name)) {
                kept.add(name);
            } else {
                warn.accept(path + " names \"" + name + "\", which isn't in rewards, so it's skipped.");
            }
        }
        return List.copyOf(kept);
    }

    private Map<Integer, List<String>> numbered(Map<String, XVConfig.Milestone> milestones, String path, Consumer<String> warn) {
        Map<Integer, List<String>> numbered = new TreeMap<>();
        milestones.forEach((key, milestone) -> {
            int n;
            try {
                n = Integer.parseInt(key.strip());
            } catch (NumberFormatException e) {
                n = 0;
            }
            if (n < 1) {
                warn.accept(path + "." + key + " should be a whole number above 0, so it's skipped.");
                return;
            }
            numbered.put(n, known(milestone.rewards(), path + "." + key, warn));
        });
        return Collections.unmodifiableMap(numbered);
    }

    /** The sites in the config's order, for /vote. */
    public List<Site> sites() {
        return sites;
    }

    /** The site a list's service name belongs to, ignoring case, or null for an unlisted one. */
    public Site site(String service) {
        return byService.get(service.toLowerCase(Locale.ROOT));
    }

    /** A site by its key in the config, ignoring case, or null. */
    public Site siteByKey(String key) {
        return byKey.get(key.toLowerCase(Locale.ROOT));
    }

    /** What players see for a site: its name if it's listed, otherwise what the list sent. */
    public String siteName(String service) {
        Site site = site(service);
        return site == null ? service : site.name();
    }

    /**
     * The name a console command or custom reward gets as {@code <site>}: a listed site's
     * name from the config, or for an unlisted one the service name the list sent with
     * everything but letters, digits, {@code .}, {@code _} and {@code -} made {@code _}, so
     * nothing from the network can add arguments or another command.
     */
    public String commandSite(String service) {
        Site site = site(service);
        return site != null ? site.name() : safe(service);
    }

    /** A service name from the network made safe to put in a command. */
    static String safe(String service) {
        String safe = service.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "_" : safe;
    }

    /** The cooldown for votes from a site: its own, or counting.unlisted-cooldown-hours for unlisted ones. */
    public long cooldown(String service) {
        Site site = site(service);
        return site == null ? unlistedCooldown : site.cooldownMs();
    }

    /** The longest cooldown of any site: how far back each player's last vote per site is kept. */
    public long longestCooldown() {
        return longestCooldown;
    }

    public Reward reward(String name) {
        return rewards.get(name);
    }

    public List<String> party() {
        return party;
    }

    /** The names of the rewards a counted vote earns, in the order they're given. */
    public List<String> earned(Facts facts) {
        List<String> earned = new ArrayList<>(everyVote);
        Site site = site(facts.site());
        if (site != null) {
            earned.addAll(known(site.rewards(), "", unused -> {}));
        }
        if (facts.firstToday()) {
            earned.addAll(firstOfDay);
        }
        every.forEach((n, names) -> {
            if (facts.number() % n == 0) {
                earned.addAll(names);
            }
        });
        if (facts.firstToday()) {
            List<String> milestone = streak.get(facts.streak());
            if (milestone != null) {
                earned.addAll(milestone);
            }
        }
        List<String> reached = total.get(facts.number());
        if (reached != null) {
            earned.addAll(reached);
        }
        return earned;
    }
}
