package co.xenastudios.xplots;

import co.xenastudios.core.Module;
import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.command.AdminCommands;
import co.xenastudios.xplots.command.PlotsCommand;
import co.xenastudios.xplots.config.XPConfig;
import co.xenastudios.xplots.hook.Regions;
import co.xenastudios.xplots.menu.MenuListener;
import co.xenastudios.xplots.menu.Menus;
import co.xenastudios.xplots.model.Plots;
import co.xenastudios.xplots.play.PlayListener;
import co.xenastudios.xplots.play.Teleports;
import co.xenastudios.xplots.protect.Protection;
import co.xenastudios.xplots.rent.Expiry;
import co.xenastudios.xplots.rent.Rentals;
import co.xenastudios.xplots.rent.Wallets;
import co.xenastudios.xplots.reset.Resets;
import co.xenastudios.xplots.reset.Ticker;
import co.xenastudios.xplots.setup.Builder;
import co.xenastudios.xplots.setup.Pending;
import co.xenastudios.xplots.setup.Selections;
import co.xenastudios.xplots.sign.Signs;
import co.xenastudios.xplots.storage.Store;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.scheduler.BukkitTask;

/** xPlots: rentable plots, set up in a minute. Not final: MockBukkit subclasses it in tests. */
public class XPlots extends XenaPlugin<XPConfig> {

    /** Renting plots and /plots. Everyone has it. */
    public static final String USE = "xplots.use";

    private static final Message STAFF = Message.of("<prefix><text>");

    private boolean started;
    private Store store;
    private Plots plots;
    private Ticker ticker;
    private Resets resets;
    private Wallets wallet;
    private Signs signs;
    private Rentals rentals;
    private Expiry expiry;
    private Selections selections;
    private Pending pending;
    private Builder builder;
    private Menus menus;
    private Teleports teleports;
    private Regions regions;
    private Protection protection;
    private boolean protecting;

    @Override
    protected ConfigSpec<XPConfig> configSpec() {
        return XPConfig.SPEC;
    }

    @Override
    protected int bStatsId() {
        return 35419;
    }

    @Override
    protected void setup(Setup setup, XPConfig config) {
        if (!started) {
            started = true;
            start(config);
        }
        if (store == null) {
            return; // The database couldn't be opened; the console says why. Only reload and info run.
        }
        setup.module("play", () -> new PlayListener(this));
        setup.module("menus", () -> new MenuListener(this));
        setup.module("timers", Timers::new);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            setup.module("placeholders", PlaceholderModule::new);
        }
        setup.command(PlotsCommand.spec(this));
        AdminCommands.specs(this).forEach(setup::subcommand);
        setup.permission(Protection.BYPASS, PermissionDefault.OP);
        setup.permission(Teleports.BYPASS, PermissionDefault.OP);
    }

    // Opens the database and reads every plot, once. It blocks, on purpose: it runs while
    // the server starts, before anyone can join, so there's never a moment when plots
    // exist in the world but aren't protected yet.
    private void start(XPConfig config) {
        try {
            store = Store.open(config.storage(), getDataFolder().toPath(), getLogger());
            plots = store.load();
        } catch (DatabaseException e) {
            getLogger().severe(e.getMessage());
            getLogger().severe("xPlots can't run without its database. Fix storage in config.yml and restart.");
            if (store != null) {
                store.close(1, TimeUnit.SECONDS);
            }
            store = null;
            return;
        }
        ticker = new Ticker(this, () -> config().resetMsPerTick());
        resets = new Resets(this, store, ticker);
        wallet = new Wallets(this::config);
        signs = new Signs(plots, this::config, wallet);
        rentals = new Rentals(this);
        expiry = new Expiry(this);
        selections = new Selections(this);
        pending = new Pending(this);
        builder = new Builder(this, Store.maxFieldId(plots));
        menus = new Menus(this);
        teleports = new Teleports(this);
        regions = Regions.find(getLogger());
        protection = new Protection(this);
        protectionChanged();
        int count = plots.fields().stream().mapToInt(field -> field.plots().size()).sum();
        getLogger().info("Loaded " + plots.fields().size() + " fields with " + count + " plots from " + store.description() + ".");
        // Economy plugins often register after xPlots starts, so this is only a heads-up; the
        // real warning comes if someone tries to pay while it's still missing.
        if (config.currency().type() == XPConfig.Currency.Type.VAULT && !Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            getLogger().info("Rent is set to be paid through Vault, which isn't installed. Plots can't be rented until it is,"
                + " or until currency.type is item.");
        }
    }

    /** Protection listens only while there's a field to protect. Call after adding or deleting one. */
    public void protectionChanged() {
        boolean wanted = !plots.isEmpty();
        if (wanted && !protecting) {
            protection.enable();
        } else if (!wanted && protecting) {
            protection.disable();
        }
        protecting = wanted;
    }

    /** The sign refresh and the expiry timer: the only timers, and only while xPlots runs. */
    private final class Timers implements Module {
        private BukkitTask refresh;

        @Override
        public void enable() {
            // Signs in chunks loaded before xPlots started are checked a second in, then once a minute.
            refresh = Bukkit.getScheduler().runTaskTimer(XPlots.this, signs::refresh, 20, 1200);
            // Handles leases that ran out while the server was off, and sets the timer.
            expiry.fire();
        }

        @Override
        public void disable() {
            refresh.cancel();
            expiry.stop();
        }
    }

    private final class PlaceholderModule implements Module {
        private co.xenastudios.xplots.hook.PlotPlaceholders expansion;

        @Override
        public void enable() {
            expansion = new co.xenastudios.xplots.hook.PlotPlaceholders(XPlots.this);
            expansion.register();
        }

        @Override
        public void disable() {
            expansion.unregister();
        }
    }

    @Override
    protected void shutdown() {
        if (store == null) {
            return;
        }
        pending.clear();
        teleports.clear();
        // Finishes any plot being put back, then writes what's left.
        ticker.stop();
        if (protecting) {
            protection.disable();
            protecting = false;
        }
        store.close(10, TimeUnit.SECONDS);
    }

    /** A reply for staff, in the theme: {@code <prefix>} and the text, which may use the theme's tags. */
    public Component staffMessage(String miniMessage) {
        return STAFF.chat(Placeholders.component("text", Message.of(miniMessage).render()));
    }

    /** Forgets what's kept for a player who left. */
    public void forget(UUID player) {
        protection.forget(player);
    }

    public Store store() {
        return store;
    }

    public Plots plots() {
        return plots;
    }

    public Resets resets() {
        return resets;
    }

    public Wallets wallet() {
        return wallet;
    }

    public Signs signs() {
        return signs;
    }

    public Rentals rentals() {
        return rentals;
    }

    public Expiry expiry() {
        return expiry;
    }

    public Selections selections() {
        return selections;
    }

    public Pending pending() {
        return pending;
    }

    public Builder builder() {
        return builder;
    }

    public Menus menus() {
        return menus;
    }

    public Teleports teleports() {
        return teleports;
    }

    public Regions regions() {
        return regions;
    }
}
