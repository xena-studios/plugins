package co.xenastudios.xbans.importer;

import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;

/**
 * /xbans import: reads a source whole off the server's threads, counts it (a dry run, unless
 * confirmed), then writes it in batches of 500 on the database thread. A row imported before
 * is skipped, so importing twice adds nothing. What's in force is enforced at once, here and
 * (quietly) on the network's other servers.
 */
public final class Imports {

    static final int BATCH = 500;

    private static final Map<String, Source> SOURCES = List.<Source>of(new VanillaSource(), new EssentialsSource(),
        new LiteBansSource()).stream().collect(Collectors.toMap(Source::name, Function.identity(),
            (a, b) -> a, java.util.LinkedHashMap::new));

    private final XBans xbans;
    private final AtomicBoolean running = new AtomicBoolean();
    // The server's folder (its working directory, where vanilla keeps banned-players.json) and
    // the plugins folder. Tests point these elsewhere.
    private volatile Path server = Path.of("").toAbsolutePath();
    private volatile Path plugins;

    public Imports(XBans xbans) {
        this.xbans = xbans;
        this.plugins = xbans.getDataFolder().toPath().toAbsolutePath().getParent();
    }

    /** Where sources are read from, for tests. */
    public void folders(Path server, Path plugins) {
        this.server = server;
        this.plugins = plugins;
    }

    public static List<String> sources() {
        return List.copyOf(SOURCES.keySet());
    }

    /** Starts an import, or a dry run that only counts. The sender hears how it went. */
    public void start(String sourceName, boolean confirm, CommandSender sender) {
        XBConfig.ImportMessages messages = xbans.config().messages().imports();
        Source source = sourceName == null ? null : SOURCES.get(sourceName.toLowerCase(Locale.ROOT));
        if (source == null) {
            messages.unknownSource().send(sender, Placeholders.text("sources", String.join(", ", sources())));
            return;
        }
        if (xbans.store().backend() == null) {
            xbans.config().messages().notReady().send(sender);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            messages.running().send(sender);
            return;
        }
        if (confirm) {
            messages.started().send(sender, Placeholders.text("source", source.name()));
        }
        Path server = this.server;
        Path plugins = this.plugins;
        String scope = xbans.punisher().scope(null);
        xbans.tasks().async(() -> {
            try {
                Haul haul = source.read(server, plugins, scope);
                if (!confirm) {
                    reply(sender, () -> {
                        messages.counted().send(sender, Placeholders.text("source", source.name()),
                            Placeholders.text("bans", haul.count(PunishmentType.BAN)),
                            Placeholders.text("mutes", haul.count(PunishmentType.MUTE)),
                            Placeholders.text("warnings", haul.count(PunishmentType.WARN)),
                            Placeholders.text("kicks", haul.count(PunishmentType.KICK)),
                            Placeholders.text("players", haul.players()));
                        if (haul.wildcards() > 0) {
                            messages.wildcards().send(sender, Placeholders.text("count", haul.wildcards()));
                        }
                    });
                    running.set(false);
                    return;
                }
                write(haul).whenComplete((counts, failure) -> {
                    running.set(false);
                    if (failure != null) {
                        xbans.getLogger().log(Level.SEVERE, "The import from " + source.name() + " failed", failure);
                        reply(sender, () -> messages.failed().send(sender, Placeholders.text("reason", String.valueOf(
                            failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage()))));
                    } else {
                        xbans.getLogger().info("Imported " + counts[0] + " punishments from " + source.name()
                            + " (" + counts[1] + " already there).");
                        reply(sender, () -> messages.done().send(sender, Placeholders.text("source", source.name()),
                            Placeholders.text("added", counts[0]), Placeholders.text("skipped", counts[1])));
                    }
                });
            } catch (Source.ImportException e) {
                running.set(false);
                reply(sender, () -> messages.failed().send(sender, Placeholders.text("reason", e.getMessage())));
            } catch (RuntimeException e) {
                running.set(false);
                xbans.getLogger().log(Level.SEVERE, "The import from " + source.name() + " failed", e);
                reply(sender, () -> messages.failed().send(sender, Placeholders.text("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void reply(CommandSender sender, Runnable work) {
        xbans.tasks().sender(sender, work);
    }

    /**
     * Writes a haul in batches, each one transaction. Returns {added, skipped}. With
     * ips.enabled off, no IP is stored, and a punishment of an IP alone is skipped.
     */
    CompletableFuture<long[]> write(Haul haul) {
        long[] counts = new long[2];
        boolean ips = xbans.config().ips().enabled();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        List<Haul.Row> rows = haul.rows;
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<Haul.Row> batch = rows.subList(from, Math.min(rows.size(), from + BATCH));
            chain = chain.thenCompose(ignored -> xbans.store().write((c, b) -> {
                List<Entry> active = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Haul.Row row : batch) {
                    Entry entry = row.entry();
                    if (!ips && entry.punishment().target() == null) {
                        counts[1]++;
                        continue;
                    }
                    Entry keyed = keyed(entry, b, ips);
                    String key = keyed.ip() == null ? row.key() : row.key().replace(Haul.IP, keyed.ip());
                    long id = Store.insertImported(c, b, keyed, key);
                    if (id < 0) {
                        counts[1]++;
                        continue;
                    }
                    counts[0]++;
                    Entry stored = keyed.withId(id);
                    if (stored.active(now)) {
                        active.add(stored);
                        b.feed().publish(c, Store.TOPIC_PUNISH, id + Store.QUIET);
                    }
                }
                return active;
            }).thenAccept(active -> {
                Store.Backend backend = xbans.store().backend();
                if (backend != null) {
                    active.forEach(backend.active()::add);
                    // Players already online who are now banned are kicked, without a broadcast.
                    active.forEach(xbans.punisher()::enforce);
                }
            }));
        }
        List<Haul.Seen> seen = haul.seen;
        for (int from = 0; from < seen.size(); from += BATCH) {
            List<Haul.Seen> batch = seen.subList(from, Math.min(seen.size(), from + BATCH));
            chain = chain.thenCompose(ignored -> xbans.store().write((c, b) -> {
                for (Haul.Seen player : batch) {
                    if (player.name() != null) {
                        Store.importPlayer(c, b, player.id(), player.name(), player.first(), player.last());
                    }
                    if (player.ip() != null && ips) {
                        Store.touchIp(c, b, player.id(), b.keys().key(player.ip()), player.first(), player.last());
                    }
                }
                return null;
            }));
        }
        return chain.thenApply(ignored -> counts);
    }

    // The IP as this database keys it (or none, with IPs off), and an IP punishment of no
    // account named after it as anyone may see it: masked, or the start of its hash.
    private static Entry keyed(Entry entry, Store.Backend b, boolean ips) {
        String key = !ips || entry.ip() == null ? null : b.keys().key(entry.ip());
        Punishment p = entry.punishment();
        if (p.target() == null) {
            p = new Punishment(p.id(), p.type(), null, IpKeys.label(key), p.ipBased(), p.reason(), p.staff(), p.staffName(),
                p.created(), p.expires(), p.scope(), p.silent(), p.template(), p.removal());
        }
        return new Entry(p, key);
    }
}
