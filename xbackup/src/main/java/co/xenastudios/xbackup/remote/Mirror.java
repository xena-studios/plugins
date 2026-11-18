package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.store.Backend;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Keeps a copy of a repository on another backend. Files never change, so a copy is
 * incremental by nature: anything the remote already has (same name, same size) is
 * skipped, so an interrupted copy picks up where it stopped. Packs go first, then index
 * files, then snapshots, so a remote never lists a snapshot whose data isn't there yet; and
 * deletions run the other way round.
 */
public final class Mirror {

    private static final List<String> ORDER = List.of("config", "data/", "index/", "keep/", "snapshots/");

    private Mirror() {}

    /** What a copy did. */
    public record Result(int filesSent, long bytesSent, int filesDeleted) {}

    public static Result push(Backend from, Backend to, BooleanSupplier cancelled, Consumer<String> progress)
        throws IOException {
        Map<String, Long> source = new HashMap<>();
        for (Backend.FileInfo file : from.list("")) {
            if (rank(file.name()) >= 0) {
                source.put(file.name(), file.size());
            }
        }
        Map<String, Long> target = new HashMap<>();
        for (Backend.FileInfo file : to.list("")) {
            target.put(file.name(), file.size());
        }
        List<String> send = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Long> file : source.entrySet()) {
            if (!file.getValue().equals(target.get(file.getKey()))) {
                send.add(file.getKey());
                total += file.getValue();
            }
        }
        send.sort(Comparator.comparingInt(Mirror::rank).thenComparing(Comparator.naturalOrder()));
        int sent = 0;
        long bytes = 0;
        for (String name : send) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("stopped");
            }
            progress.accept(sent + " of " + send.size() + " files, " + percent(bytes, total));
            Path local = from.localFile(name);
            if (local != null) {
                to.put(name, local);
            } else {
                Path temp = Files.createTempFile("xbackup", ".part");
                try (InputStream in = from.get(name)) {
                    Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    to.put(name, temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            sent++;
            bytes += source.get(name);
        }
        List<String> remove = new ArrayList<>();
        for (String name : target.keySet()) {
            if (rank(name) > 0 && !source.containsKey(name)) {
                remove.add(name);
            }
        }
        remove.sort(Comparator.comparingInt(Mirror::rank).reversed());
        for (String name : remove) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("stopped");
            }
            to.delete(name);
        }
        return new Result(sent, bytes, remove.size());
    }

    // Where a file goes in the order, or -1 if it isn't a repository file.
    private static int rank(String name) {
        for (int i = 0; i < ORDER.size(); i++) {
            String prefix = ORDER.get(i);
            if (prefix.endsWith("/") ? name.startsWith(prefix) && name.indexOf(".part-") < 0 : name.equals(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String percent(long done, long total) {
        return total == 0 ? "100%" : (done * 100 / total) + "%";
    }
}
