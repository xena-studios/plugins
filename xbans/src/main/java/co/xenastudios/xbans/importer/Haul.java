package co.xenastudios.xbans.importer;

import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.model.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What a source holds, read whole before anything is written, so a dry run can count it. */
public final class Haul {

    /** In a {@link Row#key}: the row's IP, as the database keys it (hashed, with hashing on), never the address. */
    static final String IP = "{ip}";

    /**
     * One punishment, as xBans will store it.
     *
     * @param entry its {@link Entry#ip()} is the raw address, keyed (or hashed) as it's written
     * @param key where it came from, such as {@code "litebans:bans:12"}, so importing twice
     *     adds nothing. {@link #IP} stands for its IP key.
     */
    public record Row(Entry entry, String key) {}

    /** An account seen on an IP, for /alts and /iphistory. {@code ip} is the raw address, or null. */
    public record Seen(UUID id, String name, String ip, long first, long last) {}

    final List<Row> rows = new ArrayList<>();
    final List<Seen> seen = new ArrayList<>();
    // LiteBans' wildcard IP bans (192.168.1.*), left out.
    long wildcards;

    void wildcard() {
        wildcards++;
    }

    long wildcards() {
        return wildcards;
    }

    void add(Entry entry, String key) {
        rows.add(new Row(entry, key));
    }

    void seen(UUID id, String name, String ip, long first, long last) {
        seen.add(new Seen(id, name, ip, first, last));
    }

    long count(PunishmentType type) {
        return rows.stream().filter(row -> row.entry().type() == type).count();
    }

    long players() {
        return seen.stream().map(Seen::id).distinct().count();
    }
}
