package co.xenastudios.xrewind;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.xrewind.command.LookupCommands;
import co.xenastudios.xrewind.command.RollbackCommands;
import co.xenastudios.xrewind.command.StatusCommand;
import co.xenastudios.xrewind.command.UpkeepCommands;
import co.xenastudios.xrewind.maintain.AutoPurge;
import co.xenastudios.xrewind.maintain.Upkeep;
import co.xenastudios.xrewind.config.XRConfig;
import co.xenastudios.xrewind.listen.BlockBreaks;
import co.xenastudios.xrewind.listen.BlockPlaces;
import co.xenastudios.xrewind.listen.Containers;
import co.xenastudios.xrewind.listen.Explosions;
import co.xenastudios.xrewind.listen.Fire;
import co.xenastudios.xrewind.listen.Hoppers;
import co.xenastudios.xrewind.listen.Kills;
import co.xenastudios.xrewind.listen.Liquids;
import co.xenastudios.xrewind.listen.Mobs;
import co.xenastudios.xrewind.listen.Pistons;
import co.xenastudios.xrewind.listen.RecentBreaks;
import co.xenastudios.xrewind.listen.Sessions;
import co.xenastudios.xrewind.listen.Small;
import co.xenastudios.xrewind.listen.Words;
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
    private Upkeep upkeep;
    private final RecentBreaks recent = new RecentBreaks();

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
        queue.excludeWorlds(java.util.Set.copyOf(log.excludedWorlds()));
        // Each is listened for only while it's on.
        module(setup, log.blockBreak(), "block-break", () -> new BlockBreaks(this, queue, recent));
        module(setup, log.blockPlace(), "block-place", () -> new BlockPlaces(this, queue));
        module(setup, log.explosions(), "explosions", () -> new Explosions(this, queue, recent));
        module(setup, log.fire(), "fire", () -> new Fire(this, queue));
        module(setup, log.liquids(), "liquids", () -> new Liquids(this, queue));
        module(setup, log.pistons(), "pistons", () -> new Pistons(this, queue));
        module(setup, log.mobs(), "mobs", () -> new Mobs(this, queue));
        module(setup, log.signs(), "signs", () -> new Words.Signs(this, queue));
        module(setup, log.containers(), "containers", () -> new Containers(this, queue));
        module(setup, log.hoppers(), "hoppers", () -> new Hoppers(this, queue));
        module(setup, log.kills(), "kills", () -> new Kills(this, queue));
        module(setup, log.clicks(), "clicks", () -> new Small.Clicks(this, queue));
        module(setup, log.leafDecay(), "leaf-decay", () -> new Small.LeafDecay(this, queue));
        module(setup, log.sessions(), "sessions", () -> new Sessions(this, queue));
        module(setup, log.chat(), "chat", () -> new Words.Chat(this, queue));
        module(setup, log.commands(), "commands", () -> new Words.Commands(this, queue));
        if (lookups == null) {
            lookups = new Lookups(this);
            inspector = new Inspector(this, lookups);
            rollbacks = new Rollbacks(this);
            upkeep = new Upkeep(this);
        }
        if (config.purge().keepDays() > 0) {
            setup.module("purge", () -> new AutoPurge(this));
        }
        UpkeepCommands.specs(this, upkeep).forEach(setup::subcommand);
        LookupCommands.specs(this, lookups, inspector).forEach(setup::subcommand);
        RollbackCommands.specs(this, rollbacks).forEach(setup::subcommand);
        setup.permission(Rollbacks.GLOBAL_PERMISSION, PermissionDefault.OP);
        setup.permission(LookupCommands.CHAT_PERMISSION, PermissionDefault.OP);
        setup.subcommand(StatusCommand.spec(this));
    }

    private static void module(Setup setup, boolean on, String name, java.util.function.Supplier<co.xenastudios.core.Module> factory) {
        if (on) {
            setup.module("log." + name, factory);
        }
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
