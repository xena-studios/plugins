package co.xenastudios.xbackup;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.xbackup.api.XBackupApi;
import co.xenastudios.xbackup.command.BackupCommand;
import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.restore.ServerCheck;
import co.xenastudios.xbackup.server.Api;
import co.xenastudios.xbackup.server.Engine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;

/** xBackup: incremental backups that cost almost nothing, and a restore you can trust. Not final: MockBukkit subclasses it in tests. */
public class XBackup extends XenaPlugin<XBConfig> {

    private Engine engine;
    private Api api;

    @Override
    protected ConfigSpec<XBConfig> configSpec() {
        return XBConfig.SPEC;
    }

    @Override
    protected List<String> adminAliases() {
        return List.of("xb");
    }

    @Override
    protected void setup(Setup setup, XBConfig config) {
        if (engine == null) {
            ServerCheck.hold(getDataFolder().toPath().toAbsolutePath());
            engine = new Engine(this);
            api = new Api(engine);
            Bukkit.getServicesManager().register(XBackupApi.class, api, this, ServicePriority.Normal);
            reportRestore();
        }
        engine.configure(config);
        if (config.command().enabled()) {
            setup.command(BackupCommand.spec(engine, config.command()));
        }
        for (String permission : BackupCommand.permissions()) {
            setup.permission(permission, BackupCommand.permissionDefault());
        }
        if (config.schedule().skipWhenIdle()) {
            // Only to know whether anyone was on since the last backup.
            setup.module("idle-watch", () -> new ListenerModule(this) {
                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    engine.playerSeen();
                }
            });
        }
    }

    // The bootstrap swapped a staged restore in on this start: say so, once.
    private void reportRestore() {
        Path note = engine.restoreFolder().resolve(XBackupBootstrap.DONE);
        if (Files.exists(note)) {
            try {
                for (String line : Files.readAllLines(note)) {
                    getLogger().info(line);
                }
                Files.delete(note);
            } catch (java.io.IOException e) {
                getLogger().warning("Couldn't read " + note + ": " + e.getMessage());
            }
        }
    }

    @Override
    protected void shutdown() {
        if (engine != null) {
            Bukkit.getServicesManager().unregister(XBackupApi.class, api);
            engine.stop();
            engine = null;
            ServerCheck.release();
        }
    }

    /** The engine, for tests. */
    public Engine engine() {
        return engine;
    }
}
