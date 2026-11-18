package co.xenastudios.xmoney;

import co.xenastudios.core.Module;
import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.xmoney.api.XMoneyApi;
import co.xenastudios.xmoney.command.BalanceCommand;
import co.xenastudios.xmoney.command.BaltopCommand;
import co.xenastudios.xmoney.command.EcoCommand;
import co.xenastudios.xmoney.command.ImportCommand;
import co.xenastudios.xmoney.command.PayCommand;
import co.xenastudios.xmoney.command.Players;
import co.xenastudios.xmoney.command.StatusCommand;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.hook.BukkitEvents;
import co.xenastudios.xmoney.hook.MoneyService;
import co.xenastudios.xmoney.ledger.Accounts;
import co.xenastudios.xmoney.ledger.Ledger;
import co.xenastudios.xmoney.ledger.Store;
import co.xenastudios.xmoney.ledger.Writer;
import co.xenastudios.xmoney.listen.Joins;
import co.xenastudios.xmoney.vault.VaultServices;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.ServicePriority;

/** xMoney: the economy, and Vault, in one plugin. Not final: MockBukkit subclasses it in tests. */
public class XMoney extends XenaPlugin<XMConfig> {

    /**
     * The plugin's name, as players and owners see it. "xMoney" is a working name (#22): a
     * rename changes this, {@code pluginName} in build.gradle.kts, and the docs.
     */
    public static final String NAME = "xMoney";
    /** Its lowercase id: the admin command, permission prefix and placeholder prefix. */
    public static final String ID = "xmoney";

    private boolean started;
    private Store store;
    private Writer writer;
    private Ledger ledger;
    private ChangeFeed feed = ChangeFeed.off();
    private Bank bank;
    private Players players;
    private VaultServices vault;
    private MoneyService service;
    private BukkitEvents events;

    @Override
    protected ConfigSpec<XMConfig> configSpec() {
        return XMConfig.SPEC;
    }

    @Override
    protected void setup(Setup setup, XMConfig config) {
        if (!started) {
            started = true;
            start(config);
        }
        if (ledger == null) {
            return; // The database couldn't be opened; the console says why. Only reload and info run.
        }
        setup.module("accounts", () -> new Joins(this));
        if (config.ledger().keepDays() > 0) {
            setup.module("prune", Prune::new);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            setup.module("placeholders", PlaceholderModule::new);
        }
        XMConfig.Commands commands = config.commands();
        if (commands.balance().enabled()) {
            setup.command(BalanceCommand.spec(this, commands.balance()));
            setup.permission(commands.balance().permissionOthers(), PermissionDefault.OP);
        }
        if (commands.pay().enabled()) {
            setup.command(PayCommand.spec(this, commands.pay()));
        }
        if (commands.baltop().enabled()) {
            setup.command(BaltopCommand.spec(this, commands.baltop()));
        }
        if (commands.eco().enabled()) {
            EcoCommand.declare(setup, this, commands.eco());
        }
        setup.subcommand(ImportCommand.spec(this));
        setup.subcommand(StatusCommand.spec(this));
    }

    // Opens the database and reads every balance, once. It blocks, on purpose: it runs while
    // the server starts, before anyone joins and before any plugin can ask Vault, so a
    // balance is never read or changed before it's loaded.
    private void start(XMConfig config) {
        Accounts accounts = new Accounts();
        try {
            store = Store.open(config.storage(), getDataFolder().toPath());
            store.load(accounts);
            feed = ChangeFeed.open(store.database(), config.network(), getLogger());
        } catch (DatabaseException | SQLException e) {
            getLogger().severe(e.getMessage());
            getLogger().severe(NAME + " can't run without its database. Fix storage in config.yml and restart."
                + " Until then there's no economy: plugins that use Vault will find none.");
            store = null;
            return;
        }
        writer = new Writer(store.database(), accounts, feed, feed.on(), getLogger());
        writer.start();
        ledger = new Ledger(accounts, writer, feed.on(), () -> bank.max());
        bank = new Bank(ledger, this::config, getLogger(), Bukkit::isPrimaryThread);
        events = new BukkitEvents(getLogger());
        ledger.events(events);
        players = new Players(this, new Profiles(tasks()));
        ledger.listen(feed);
        feed.start(tasks());
        service = new MoneyService(this);
        Bukkit.getServicesManager().register(XMoneyApi.class, service, this, ServicePriority.Normal);
        vault = new VaultServices(this);
        vault.register();
        getLogger().info("Loaded " + accounts.size() + " accounts from " + store.description()
            + (feed.on() ? ", shared with the other servers on it." : "."));
        // Players already online, after /reload confirm, get their accounts as if they'd joined.
        for (Player player : Bukkit.getOnlinePlayers()) {
            ledger.open(player.getUniqueId(), player.getName(), bank.start());
        }
        tasks().async(vault::warnAboutVault);
    }

    /** Deletes old ledger rows at startup and once a day: the only repeating task, and only while keep-days is set. */
    private final class Prune implements Module {
        private ScheduledTask task;

        @Override
        public void enable() {
            task = tasks().asyncRepeating(() -> {
                int days = config().ledger().keepDays();
                if (days > 0) {
                    ledger.prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
                }
            }, Duration.ofMinutes(1), Duration.ofDays(1));
        }

        @Override
        public void disable() {
            task.cancel();
        }
    }

    private final class PlaceholderModule implements Module {
        private co.xenastudios.xmoney.hook.MoneyPlaceholders expansion;

        @Override
        public void enable() {
            expansion = new co.xenastudios.xmoney.hook.MoneyPlaceholders(XMoney.this);
            expansion.register();
        }

        @Override
        public void disable() {
            expansion.unregister();
        }
    }

    @Override
    protected void shutdown() {
        if (vault != null) {
            vault.unregister();
        }
        Bukkit.getServicesManager().unregisterAll(this);
        feed.close();
        if (events != null) {
            events.close();
        }
        if (writer != null) {
            writer.stop(10, TimeUnit.SECONDS);
        }
    }

    public Bank bank() {
        return bank;
    }

    public Ledger ledger() {
        return ledger;
    }

    public Store store() {
        return store;
    }

    public Players players() {
        return players;
    }

    public ChangeFeed feed() {
        return feed;
    }

    public MoneyService service() {
        return service;
    }
}
