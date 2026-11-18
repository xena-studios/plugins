package co.xenastudios.xstats.profile;

import co.xenastudios.xstats.api.ProfileLine;
import co.xenastudios.xstats.api.ProfileSectionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * Sections other plugins add to profiles through the API: xVote's votes, xBans' history.
 * Each is asked on a virtual thread with half a second to answer, so a slow or broken one
 * never holds up a profile.
 */
public final class Sections {

    static final long TIMEOUT_MS = 500;

    /** A section as registered. {@code key} is {@code <plugin>_<id>}. */
    public record Section(Plugin plugin, String key, String title, ProfileSectionProvider provider) {}

    /** A section's lines for one profile. */
    public record Filled(String key, String title, List<ProfileLine> lines) {}

    private final List<Section> sections = new CopyOnWriteArrayList<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Logger logger;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    public Sections(Logger logger) {
        this.logger = logger;
    }

    public void add(Section section) {
        sections.removeIf(old -> old.key().equals(section.key()));
        sections.add(section);
        failed.remove(section.key());
    }

    public void remove(String key) {
        sections.removeIf(section -> section.key().equals(key));
    }

    public void removeAll(Plugin plugin) {
        sections.removeIf(section -> section.plugin().equals(plugin));
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** Every section's lines for a player. Blocks for up to half a second: never on a server thread. */
    public List<Filled> fill(UUID player, boolean staff) {
        List<Section> current = List.copyOf(sections);
        if (current.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<List<ProfileLine>>> asked = new ArrayList<>();
        for (Section section : current) {
            asked.add(CompletableFuture.supplyAsync(() -> section.plugin().isEnabled()
                ? section.provider().lines(player, staff) : List.<ProfileLine>of(), threads));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        List<Filled> filled = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            Section section = current.get(i);
            try {
                long left = Math.max(0, deadline - System.nanoTime());
                List<ProfileLine> lines = asked.get(i).get(left, TimeUnit.NANOSECONDS);
                if (lines != null && !lines.isEmpty()) {
                    filled.add(new Filled(section.key(), section.title(), List.copyOf(lines)));
                }
            } catch (java.util.concurrent.TimeoutException e) {
                asked.get(i).cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
                if (failed.add(section.key())) {
                    logger.warning(section.plugin().getName() + "'s profile section " + section.key() + " failed: "
                        + (e.getCause() != null ? e.getCause() : e) + ". It's skipped where it fails.");
                }
            }
        }
        return filled;
    }
}
