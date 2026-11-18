package co.xenastudios.xvote;

import co.xenastudios.core.Module;
import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.hook.VaultEconomy;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xvote.api.XVoteApi;
import co.xenastudios.xvote.api.event.VotePartyEvent;
import co.xenastudios.xvote.command.AdminCommands;
import co.xenastudios.xvote.command.VoteCommand;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.play.IntervalReminders;
import co.xenastudios.xvote.play.Players;
import co.xenastudios.xvote.receiver.NuVotifierImport;
import co.xenastudios.xvote.receiver.Receiver;
import co.xenastudios.xvote.receiver.ReceiverHost;
import co.xenastudios.xvote.receiver.Tokens;
import co.xenastudios.xvote.reward.Items;
import co.xenastudios.xvote.reward.Payout;
import co.xenastudios.xvote.reward.RewardTypes;
import co.xenastudios.xvote.reward.Rewards;
import co.xenastudios.xvote.storage.Ledger;
import co.xenastudios.xvote.storage.Store;
import co.xenastudios.xvote.vote.Facts;
import co.xenastudios.xvote.vote.Online;
import co.xenastudios.xvote.vote.Periods;
import co.xenastudios.xvote.vote.Voter;
import co.xenastudios.xvote.vote.Voters;
import co.xenastudios.xvote.vote.Votes;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.ServicePriority;

/** xVote: votes in, rewards out. Vote rewards with a built-in Votifier. Not final: MockBukkit subclasses it in tests. */
public class XVote extends XenaPlugin<XVConfig> {

    /** /vote, /vote top and /vote claim. Everyone has it. */
    public static final String USE = "xvote.use";
    /** /vote test. */
    public static final String TEST = "xvote.test";

    private static final Message STAFF = Message.of("<prefix><text>");

    private boolean started;
    private Store store;
    private Ledger ledger;
    private Voters voters;
    private Votes votes;
    private ChangeFeed feed = ChangeFeed.off();
    private ReceiverHost receiver;
    private ApiService api;
    private volatile Players players;
    private volatile Rewards rewards;
    private volatile Periods periods;
    private Payout payout;
    private final RewardTypes rewardTypes = new RewardTypes();
    private final VaultEconomy economy = new VaultEconomy();
    private Profiles profiles;

    @Override
    protected ConfigSpec<XVConfig> configSpec() {
        return XVConfig.SPEC;
    }

    @Override
    protected int bStatsId() {
        return 35422;
    }

    @Override
    protected void setup(Setup setup, XVConfig config) {
        rewards = Rewards.of(config, Items::parse, warning -> getLogger().warning("config.yml: " + warning));
        Periods next = new Periods(Periods.zone(config.counting().timeZone(), warning -> getLogger().warning("config.yml: " + warning)),
            config.counting().weekStarts());
        if (!started) {
            started = true;
            periods = next;
            start(config);
        }
        if (store == null) {
            return; // The database couldn't be opened; the console says why. Only reload and info run.
        }
        if (!next.sameAs(periods)) {
            periods = next;
            voters.periods(next);
        }
        payout.reloaded();
        votes.forgetUnknown(System.currentTimeMillis());
        XVConfig.Receiver settings = config.receiver();
        Receiver.Settings wanted = settings.enabled() ? new Receiver.Settings(settings.host().strip(), settings.port(), settings.v1(),
            settings.maxConnections(), settings.perIpConnections(), settings.timeoutSeconds(), settings.perIpPerMinute()) : null;
        tasks().async(() -> receiver.apply(wanted));

        setup.module("players", () -> players = new Players(this));
        int minutes = config.reminders().everyMinutes();
        if (minutes > 0) {
            setup.module("reminders:" + minutes, () -> new IntervalReminders(this, minutes));
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            setup.module("placeholders", PlaceholderModule::new);
        }
        setup.command(VoteCommand.spec(this));
        AdminCommands.specs(this).forEach(setup::subcommand);
    }

    // Opens the database and reads every voter, once. It blocks, on purpose: it runs while
    // the server starts, before anyone can join, so totals are there for the first /vote.
    private void start(XVConfig config) {
        Path folder = getDataFolder().toPath();
        try {
            store = Store.open(config.storage(), folder, getLogger());
            ledger = new Ledger(store.database());
            voters = new Voters(periods);
            profiles = new Profiles(tasks());
            payout = new Payout(() -> rewards, rewardTypes, economy, tasks(), getLogger(), this::hold);
            votes = new Votes(store, ledger, voters, new Online(), () -> rewards, this::config, new ServerEffects(),
                name -> profiles.cached(name).map(profile -> new Votes.Resolved(profile.id(), profile.name())).orElse(null),
                getLogger());
            votes.load(System.currentTimeMillis());
            feed = ChangeFeed.open(store.database(), config.network(), getLogger());
        } catch (DatabaseException | SQLException e) {
            getLogger().severe(e.getMessage());
            getLogger().severe("xVote can't count votes without its database. Fix storage in config.yml and restart.");
            if (store != null) {
                store.stop(1, TimeUnit.SECONDS);
            }
            store = null;
            return;
        }
        votes.network(feed);
        feed.start(tasks());
        getLogger().info("Loaded " + voters.size() + " voters from " + store.description()
            + (feed.on() ? ", sharing votes with the network." : "."));
        takeOverNuVotifier(folder);
        receiver = new ReceiverHost(folder, votes::accept, getLogger());
        api = new ApiService(this);
        Bukkit.getServicesManager().register(XVoteApi.class, api, this, ServicePriority.Normal);
    }

    // On the first start only (no keys or tokens of our own yet), NuVotifier's are taken
    // over, so the lists the owner already set up keep working.
    private void takeOverNuVotifier(Path folder) {
        if (Bukkit.getPluginManager().getPlugin("Votifier") != null) {
            getLogger().warning("NuVotifier is installed too. Remove it: xVote has its own receiver, and both can't listen on"
                + " one port. Its keys and tokens can be taken over with /xvote import nuvotifier.");
        }
        if (Files.exists(folder.resolve("rsa")) || Files.exists(folder.resolve(Tokens.FILE))) {
            return;
        }
        Path found = NuVotifierImport.find(folder.getParent());
        if (found == null) {
            return;
        }
        try {
            NuVotifierImport.Found read = NuVotifierImport.read(found);
            NuVotifierImport.write(read, folder);
            getLogger().info("Took over NuVotifier's " + (read.keys() != null ? "keys" : "") + (read.keys() != null && read.tokens() != null ? " and " : "")
                + (read.tokens() != null ? "tokens" : "") + " from plugins/" + found.getFileName() + ", so your lists keep working.");
            useNuVotifierPort(read);
        } catch (IOException e) {
            getLogger().warning("Couldn't take over NuVotifier's setup from plugins/" + found.getFileName() + ": " + e.getMessage());
        }
    }

    /** Sets the receiver to NuVotifier's port, address and v1 setting, if they differ. */
    public void useNuVotifierPort(NuVotifierImport.Found read) {
        String host = read.host().isBlank() || read.host().contains("%") ? null : read.host();
        editConfig(yaml -> {
            boolean changed = false;
            if (read.port() > 0 && yaml.getInt("receiver.port") != read.port()) {
                yaml.set("receiver.port", read.port());
                changed = true;
            }
            if (host != null && !host.equals(yaml.getString("receiver.host"))) {
                yaml.set("receiver.host", host);
                changed = true;
            }
            if (yaml.getBoolean("receiver.v1", true) != read.v1()) {
                yaml.set("receiver.v1", read.v1());
                changed = true;
            }
            return changed;
        }, problem -> {
            if (problem != null) {
                getLogger().warning("Couldn't set receiver.port to NuVotifier's " + read.port() + ": " + problem);
            }
        });
    }

    @Override
    protected void shutdown() {
        if (api != null) {
            Bukkit.getServicesManager().unregister(XVoteApi.class, api);
        }
        if (receiver != null) {
            receiver.close();
        }
        feed.close();
        if (votes != null && store != null) {
            // Votes taken to pay but not yet paid go back, so the next start pays them.
            votes.shutdown();
        }
        if (store != null) {
            store.stop(10, TimeUnit.SECONDS);
        }
    }

    // ---- what the commands, placeholders and API use ----

    /** A reply for staff, in the theme: {@code <prefix>} and the text, which may use the theme's tags. */
    public Component staffMessage(String miniMessage) {
        return STAFF.chat(Placeholders.component("text", Message.of(miniMessage).render()));
    }

    /** Whether the database opened: without it, only reload and info run. */
    public boolean running() {
        return store != null;
    }

    public Store store() {
        return store;
    }

    public Ledger ledger() {
        return ledger;
    }

    public Voters voters() {
        return voters;
    }

    public Votes votes() {
        return votes;
    }

    public Rewards rewards() {
        return rewards;
    }

    public Periods periods() {
        return periods;
    }

    public ReceiverHost receiver() {
        return receiver;
    }

    public RewardTypes rewardTypes() {
        return rewardTypes;
    }

    public ChangeFeed feed() {
        return feed;
    }

    public Payout payout() {
        return payout;
    }

    /** The sites a player can vote on now. */
    public List<Rewards.Site> available(UUID player, long now) {
        Voter voter = voters.get(player);
        List<Rewards.Site> open = new ArrayList<>();
        for (Rewards.Site site : rewards.sites()) {
            if (voter == null || voter.lastOn(site.service()) + site.cooldownMs() <= now) {
                open.add(site);
            }
        }
        return open;
    }

    /** Reminds a player who can vote. On their thread. */
    public void remind(Player player) {
        if (!player.isOnline() || rewards.sites().isEmpty()) {
            return;
        }
        int open = available(player.getUniqueId(), System.currentTimeMillis()).size();
        if (open > 0) {
            config().reminders().message().send(player, Placeholders.text("available", open));
        }
    }

    // Items that didn't fit: kept in the database for /vote claim. On the player's thread.
    private void hold(Player player, List<ItemStack> items) {
        List<byte[]> bytes = new ArrayList<>(items.size());
        int count = 0;
        for (ItemStack item : items) {
            bytes.add(item.serializeAsBytes());
            count += item.getAmount();
        }
        UUID id = player.getUniqueId();
        int shown = count;
        store.submit(c -> {
            ledger.hold(c, id, bytes, System.currentTimeMillis());
            return null;
        }).whenComplete((done, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Couldn't keep " + player.getName() + "'s reward items that didn't fit", error);
                return;
            }
            Payout.tell(tasks(), player, config().messages().claim().held(), Placeholders.text("count", shown));
        });
    }

    /** Hands out held items; any that still don't fit go back. On the player's thread. */
    public void handOut(Player player, List<byte[]> items) {
        List<ItemStack> leftover = new ArrayList<>();
        int given = 0;
        for (byte[] bytes : items) {
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(bytes);
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "A held reward item of " + player.getName() + "'s couldn't be read, so it's dropped", e);
                continue;
            }
            var left = player.getInventory().addItem(item).values();
            leftover.addAll(left);
            given += item.getAmount() - left.stream().mapToInt(ItemStack::getAmount).sum();
        }
        XVConfig.Claim messages = config().messages().claim();
        if (given > 0) {
            messages.claimed().send(player, Placeholders.text("count", given));
        }
        if (!leftover.isEmpty()) {
            List<byte[]> back = new ArrayList<>(leftover.size());
            int count = 0;
            for (ItemStack item : leftover) {
                back.add(item.serializeAsBytes());
                count += item.getAmount();
            }
            UUID id = player.getUniqueId();
            store.submit(c -> {
                ledger.hold(c, id, back, System.currentTimeMillis());
                return null;
            });
            messages.stillFull().send(player, Placeholders.text("count", count));
        }
    }

    /** What votes need from the server: players' threads, the global thread, broadcasts. */
    private final class ServerEffects implements Votes.Effects {

        @Override
        public void pay(UUID id, java.util.function.Supplier<List<Facts>> take, Runnable left) {
            Players current = players;
            Player player = current == null ? null : current.get(id);
            if (player == null) {
                left.run();
                return;
            }
            ScheduledTask task = player.getScheduler().run(XVote.this, scheduled -> {
                for (Facts facts : take.get()) {
                    payout.vote(player, facts);
                }
            }, left);
            if (task == null) {
                left.run();
            }
        }

        @Override
        public void party(long since) {
            tasks().global(() -> {
                XVConfig.Party settings = config().party();
                List<Player> chosen = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Voter voter = voters.get(player.getUniqueId());
                    if (settings.who() == XVConfig.Party.Who.ONLINE || (voter != null && voter.lastVote() >= since)) {
                        chosen.add(player);
                    }
                }
                if (VotePartyEvent.getHandlerList().getRegisteredListeners().length > 0) {
                    VotePartyEvent event = new VotePartyEvent(chosen);
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) {
                        return;
                    }
                }
                getLogger().info("Vote party! Rewarding " + chosen.size() + " players.");
                if (!settings.started().isEmpty()) {
                    Payout.broadcast(settings.started().chat(Placeholders.text("votes", settings.votes())));
                }
                Rewards current = rewards;
                long now = System.currentTimeMillis();
                for (Player player : List.copyOf(chosen)) {
                    Voter voter = voters.get(player.getUniqueId());
                    Payout.Context context = new Payout.Context("party", "party", voter == null ? 0 : voter.total(),
                        voter == null ? 0 : voter.streakNow(periods, now));
                    tasks().entity(player, () -> payout.give(player, current, current.party(), context));
                }
            });
        }

        @Override
        public void progress(long left) {
            Message message = config().party().progress();
            if (!message.isEmpty()) {
                Payout.broadcast(message.chat(Placeholders.text("left", left)));
            }
        }

        @Override
        public boolean onServerThread() {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public void async(Runnable work) {
            tasks().async(work);
        }
    }

    private final class PlaceholderModule implements Module {
        private co.xenastudios.xvote.hook.VotePlaceholders expansion;

        @Override
        public void enable() {
            expansion = new co.xenastudios.xvote.hook.VotePlaceholders(XVote.this);
            expansion.register();
        }

        @Override
        public void disable() {
            expansion.unregister();
        }
    }

    /** For tests. */
    Players players() {
        return Objects.requireNonNull(players);
    }
}
