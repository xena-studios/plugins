package co.xenastudios.core;

import co.xenastudios.core.command.CommandRegistry;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Subcommands;
import co.xenastudios.core.config.ConfigException;
import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The base of every plugin. It loads the config, keeps the server's commands,
 * permissions and modules matching it, and adds {@code /<plugin> reload} and
 * {@code /<plugin> info}, and the update checker.
 *
 * <p>A plugin supplies its {@link #configSpec() config} and says what should run for
 * a given config in {@link #setup}. Reloading calls {@code setup} again with the new
 * config, and the core applies the difference.
 *
 * @param <C> the plugin's config record
 */
public abstract class XenaPlugin<C extends Record & XenaConfig> extends JavaPlugin {

    private static final String CONFIG_FILE = "config.yml";

    // The admin command's replies. Not in the config, but in the plugin's theme.
    private static final Message RELOADED = Message.of("<prefix><success>Reloaded</success> <plugin> in <highlight><ms> ms</highlight>.");
    private static final Message RELOAD_WARNINGS = Message.of(
        "<prefix><error><settings> fell back to the default.</error> The console says which.");
    private static final Message RELOAD_FAILED = Message.of("<prefix><error><reason></error> Nothing changed.");
    private static final Message INFO = Message.of("""
        <prefix><highlight><plugin> <version></highlight> on <server>
        <highlight>Commands</highlight> (<count>): <commands>
        <highlight>Updates</highlight>: <updates>
        <highlight>Config</highlight>: <config>""");

    private volatile C config;
    private volatile int configWarnings;
    private int carriedFrom;
    private ConfigSpec<C> spec;
    private Tasks tasks;
    private CommandRegistry commands;
    private PermissionRegistry permissions;
    private ModuleSet modules;
    private UpdateChecker updates;
    private Metrics metrics;

    /** The plugin's config: its settings and their defaults. */
    protected abstract ConfigSpec<C> configSpec();

    /** Declares the commands, permissions and modules this config wants running. */
    protected abstract void setup(Setup setup, C config);

    /**
     * The config version this run carried the file forward from, such as 1 for a plugin
     * just updated from 1.x; 0 if it was already current.
     */
    protected final int configCarriedFrom() {
        return carriedFrom;
    }

    /** Extra names for {@code /<plugin>}, such as {@code zc} for {@code /zchat}. */
    protected List<String> adminAliases() {
        return List.of();
    }

    /**
     * The plugin's bStats ID, or 0 for none. Owners turn bStats off for every plugin
     * at once in {@code plugins/bStats/config.yml}.
     */
    protected int bStatsId() {
        return 0;
    }

    /** The plugin's lowercase name: its command namespace, Modrinth slug and permission prefix. */
    public final String id() {
        return getName().toLowerCase(Locale.ROOT);
    }

    /** The live config. Swapped whole on reload, so read it once per use. */
    public final C config() {
        return config;
    }

    public final Tasks tasks() {
        return tasks;
    }

    public final String adminPermission() {
        return id() + ".admin";
    }

    public final boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public final void onEnable() {
        spec = configSpec();
        tasks = new Tasks(this);
        commands = new CommandRegistry(this, () -> config.errorMessages(), tasks);
        permissions = new PermissionRegistry();
        modules = new ModuleSet(getLogger());
        updates = new UpdateChecker(this, id());

        try {
            accept(ConfigLoader.load(spec, configFile()));
        } catch (ConfigException e) {
            // Never refuse to start over config: run on the defaults until it's fixed.
            getLogger().severe(e.getMessage());
            getLogger().severe("Running on the default settings until " + CONFIG_FILE + " is fixed and reloaded.");
            config = spec.defaults();
            configWarnings = 1;
            Message.theme(config.theme());
        }
        apply();

        // Tests run with -Dxena.offline=true so they never report.
        if (bStatsId() > 0 && !Boolean.getBoolean("xena.offline")) {
            metrics = new Metrics(this, bStatsId());
        }
    }

    @Override
    public final void onDisable() {
        if (metrics != null) {
            metrics.shutdown();
        }
        if (updates != null) {
            updates.sync(false);
        }
        if (modules != null) {
            modules.clear();
            commands.clear();
            permissions.clear();
        }
        shutdown();
    }

    /**
     * Runs last when the plugin is disabled, after its modules stop: for anything else it
     * must let go of, so a plugin reloaded in place doesn't leave the old one in use.
     */
    protected void shutdown() {}

    /**
     * Re-reads the config and applies it. If the file can't be read, nothing changes.
     * Call on the global thread.
     *
     * @return what to tell whoever asked for the reload
     */
    public final Component reload() {
        long start = System.nanoTime();
        try {
            accept(ConfigLoader.load(spec, configFile()));
        } catch (ConfigException e) {
            getLogger().severe(e.getMessage());
            return RELOAD_FAILED.chat(Placeholders.text("reason", e.getMessage()));
        }
        apply();
        long millis = (System.nanoTime() - start) / 1_000_000;
        Component done = RELOADED.chat(Placeholders.text("plugin", getName()), Placeholders.text("ms", millis));
        int warnings = configWarnings;
        if (warnings > 0) {
            done = done.appendNewline().append(RELOAD_WARNINGS.chat(
                Placeholders.text("settings", warnings + (warnings == 1 ? " setting" : " settings"))));
        }
        return done;
    }

    /**
     * Changes config.yml and applies it, for commands that edit a setting in game. Runs on
     * the next tick on the global thread, outside the command (a reload re-registers
     * commands), then calls {@code done} there: with null once it's applied (or when
     * {@code change} found nothing to change), or with the reason it couldn't be (the file
     * isn't valid YAML, or couldn't be written), in which case nothing changed.
     */
    public final void editConfig(java.util.function.Predicate<org.bukkit.configuration.file.YamlConfiguration> change,
                                 java.util.function.Consumer<String> done) {
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            try {
                if (!ConfigLoader.edit(configFile(), change)) {
                    done.accept(null);
                    return;
                }
            } catch (ConfigException e) {
                getLogger().severe(e.getMessage());
                done.accept(e.getMessage());
                return;
            }
            try {
                accept(ConfigLoader.load(spec, configFile()));
            } catch (ConfigException e) {
                getLogger().severe(e.getMessage());
                done.accept(e.getMessage());
                return;
            }
            apply();
            done.accept(null);
        });
    }

    private Path configFile() {
        return getDataFolder().toPath().resolve(CONFIG_FILE);
    }

    private void accept(ConfigLoader.Result<C> result) {
        result.notices().forEach(getLogger()::info);
        result.warnings().forEach(warning -> getLogger().warning(CONFIG_FILE + ": " + warning));
        configWarnings = result.warnings().size();
        if (result.carriedFrom() > 0) {
            carriedFrom = result.carriedFrom();
        }
        config = result.config();
        Message.theme(config.theme());
    }

    private void apply() {
        C current = config;
        Setup setup = new Setup();
        try {
            setup(setup, current);
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Couldn't set up every feature; the ones before the error are running", e);
        }
        // First, with the subcommands the plugin added.
        CommandSpec admin = adminCommand(setup.subcommands);
        setup.commands.add(0, admin);
        setup.permission(adminPermission(), admin.permissionDefault());
        updates.sync(current.updateChecker().enabled());
        if (current.updateChecker().enabled()) {
            setup.module("update-notice", updates::joinNotice);
        }
        permissions.sync(setup.permissions);
        modules.sync(setup.modules);
        commands.sync(setup.commands).forEach(getLogger()::warning);
    }

    private CommandSpec adminCommand(List<CommandSpec> extra) {
        List<CommandSpec> subcommands = new java.util.ArrayList<>();
        subcommands.add(CommandSpec.named("reload")
            .permission(adminPermission())
            .usage("/" + id() + " reload")
            .executes(invocation -> {
                CommandSender sender = invocation.sender();
                // On the next tick, outside this command, since reloading re-registers commands.
                Bukkit.getGlobalRegionScheduler().run(this, task -> sender.sendMessage(reload()));
            }));
        subcommands.add(CommandSpec.named("info")
            .permission(adminPermission())
            .usage("/" + id() + " info")
            .executes(invocation -> invocation.sender().sendMessage(info())));
        subcommands.addAll(extra);
        List<CommandSpec> all = List.copyOf(subcommands);
        return CommandSpec.named(id())
            .aliases(adminAliases())
            // With subcommands of its own, staff who aren't admins may use some of them:
            // each subcommand checks its own permission.
            .permission(extra.isEmpty() ? adminPermission() : "")
            .description(extra.isEmpty() ? "Reload " + getName() + " or show its status." : getName() + "'s commands.")
            .usage("/" + id() + " " + Subcommands.names(all))
            .completes(Subcommands.completer(all))
            .executes(Subcommands.executor(all));
    }

    private Component info() {
        String latest = updates.latest();
        String update = !config.updateChecker().enabled() ? "off"
            : latest == null ? "up to date" : latest + " is out";
        List<String> names = commands.names();
        int warnings = configWarnings;
        return INFO.chat(
            Placeholders.text("plugin", getName()),
            Placeholders.text("version", getPluginMeta().getVersion()),
            Placeholders.text("server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion()),
            Placeholders.text("count", names.size()),
            Placeholders.text("commands", "/" + String.join(" /", names)),
            Placeholders.text("updates", update),
            Placeholders.text("config", warnings == 0 ? "no problems"
                : warnings + (warnings == 1 ? " setting" : " settings") + " fell back to the default. Reload to see which."));
    }
}
