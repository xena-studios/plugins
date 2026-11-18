package co.xenastudios.xbackup;

import co.xenastudios.xbackup.restore.ServerCheck;
import co.xenastudios.xbackup.restore.Swap;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finishes a restore staged with {@code /backup restore}: runs while the server starts,
 * before it loads any world, and swaps the staged files in (M0 showed this is early enough on
 * Paper and Folia, 1.21.11 and 26.x). Otherwise it only locks {@link ServerCheck#MARKER} and
 * looks for a journal file.
 */
public final class XBackupBootstrap implements PluginBootstrap {

    /** Left for the plugin to report once it's enabled. */
    static final String DONE = "last-restore.txt";

    @Override
    public void bootstrap(BootstrapContext context) {
        // From now until the process exits, the command-line restore sees the server running.
        ServerCheck.Hold hold = ServerCheck.hold(context.getDataDirectory().toAbsolutePath());
        if (hold == ServerCheck.Hold.BUSY) {
            // The command-line restore holds it while it stages and swaps: loading the worlds
            // now would load them while their folders are swapped, or run its journal twice.
            context.getLogger().error("xBackup stopped the server from starting: another process holds "
                + context.getDataDirectory().resolve(ServerCheck.MARKER) + ". A command-line restore is running on"
                + " this server folder (or another server is using it). Let it finish, then start the server.");
            System.err.flush();
            Runtime.getRuntime().halt(1);
        }
        Path restore = context.getDataDirectory().toAbsolutePath().resolve("restore");
        if (!Files.exists(restore.resolve(Swap.PENDING))) {
            return;
        }
        // Paths in the journal are relative to the server folder, the working directory.
        Path root = Path.of("").toAbsolutePath().normalize();
        List<String> lines = new ArrayList<>();
        try {
            Swap.Pending pending = Swap.pending(restore);
            context.getLogger().info("Putting the restore of backup {} in place ({} paths)...",
                pending.snapshot().substring(0, Math.min(8, pending.snapshot().length())), pending.entries().size());
            long start = System.nanoTime();
            Path aside = Swap.apply(restore, root, line -> context.getLogger().info(line));
            String done = "Restored backup " + pending.snapshot().substring(0, Math.min(8, pending.snapshot().length()))
                + " on this start, in " + (System.nanoTime() - start) / 1_000_000 + " ms. What was there before is in "
                + aside + "; delete it once you're happy.";
            context.getLogger().info(done);
            lines.add(done);
            Files.write(restore.resolve(DONE), lines, StandardCharsets.UTF_8);
        } catch (Swap.StuckException e) {
            // Half a world swapped in: starting would load it, or generate a new one beside it,
            // and the next start would think it was finished. Stop here instead.
            context.getLogger().error("xBackup stopped the server from starting: " + e.getMessage(), e);
            System.err.flush();
            Runtime.getRuntime().halt(1);
        } catch (Exception e) {
            context.getLogger().error("Couldn't put the restore waiting in " + restore + " in place: " + e.getMessage()
                + (e instanceof Swap.FailedException ? "" : " Nothing was changed."), e);
        }
    }
}
