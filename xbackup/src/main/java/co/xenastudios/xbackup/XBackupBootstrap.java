package co.xenastudios.xbackup;

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
 * Paper and Folia, 1.21.11 and 26.x). Only reads a journal file when there's nothing to do.
 */
public final class XBackupBootstrap implements PluginBootstrap {

    /** Left for the plugin to report once it's enabled. */
    static final String DONE = "last-restore.txt";

    @Override
    public void bootstrap(BootstrapContext context) {
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
        } catch (Exception e) {
            context.getLogger().error("Couldn't finish the restore waiting in " + restore + ": " + e.getMessage()
                + ". Nothing more was changed; it will try again on the next start. The journal is "
                + restore.resolve(Swap.PENDING) + ".", e);
        }
    }
}
