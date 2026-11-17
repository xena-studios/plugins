package co.xenastudios.xrewind;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.xrewind.command.LookupCommands;
import co.xenastudios.xrewind.command.RollbackCommands;
import co.xenastudios.xrewind.command.StatusCommand;
import co.xenastudios.xrewind.config.XRConfig;
import co.xenastudios.xrewind.listen.BlockBreaks;
import co.xenastudios.xrewind.listen.BlockPlaces;
import co.xenastudios.xrewind.listen.Sessions;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.log.Writer;
import co.xenastudios.xrewind.query.Inspector;
import co.xenastudios.xrewind.query.Lookups;
import co.xenastudios.xrewind.rollback.Rollbacks;
import co.xenastudios.xrewind.storage.Storage;
import java.util.List;
import org.bukkit.permissions.PermissionDefault;
import java.util.concurrent.TimeUnit;

/** xRewind: every change, logged. Any of it, undone. Not final: MockBukkit subclasses it in tests. */
public class XRewind extends XenaPlugin<XRConfig> {

    private Storage storage;
    private LogQueue queue;
    private Writer writer;
    private Lookups lookups;
    private Inspector inspector;
    private Rollbacks rollbacks;

    @Override
    protected ConfigSpec<XRConfig> configSpec() {
        return XRConfig.SPEC;
    }

    @Override
    protected int bStatsId() {
        return 35410;
    }

    @Override
    protected List<String> adminAliases() {
        return List.of("xr");
    }

    @Override
    protected void setup(Setup setup, XRConfig config) {
        storage().sync(config.storage());
        writer().start();
        XRConfig.Logging log = config.log();
        // Each is listened for only while it's on.
        if (log.blockBreak()) {
            setup.module("log.block-break", () -> new BlockBreaks(this, queue));
        }
        if (log.blockPlace()) {
            setup.module("log.block-place", () -> new BlockPlaces(this, queue));
        }
        if (log.sessions()) {
            setup.module("log.sessions", () -> new Sessions(this, queue));
        }
        if (lookups == null) {
            lookups = new Lookups(this);
            inspector = new Inspector(this, lookups);
            rollbacks = new Rollbacks(this);
        }
        LookupCommands.specs(this, lookups, inspector).forEach(setup::subcommand);
        RollbackCommands.specs(this, rollbacks).forEach(setup::subcommand);
        setup.permission(Rollbacks.GLOBAL_PERMISSION, PermissionDefault.OP);
        setup.subcommand(StatusCommand.spec(this));
    }

    // Modules have stopped by now, so nothing new is queued: write what's left.
    @Override
    protected void shutdown() {
        if (inspector != null) {
            inspector.clear();
        }
        if (writer != null) {
            writer.stop(10, TimeUnit.SECONDS);
        }
    }

    public Storage storage() {
        if (storage == null) {
            storage = new Storage(tasks(), getLogger(), getDataFolder().toPath());
        }
        return storage;
    }

    public LogQueue queue() {
        writer();
        return queue;
    }

    public Writer writer() {
        if (writer == null) {
            queue = new LogQueue(getLogger());
            writer = new Writer(queue, () -> storage().database(), getLogger());
        }
        return writer;
    }
}
