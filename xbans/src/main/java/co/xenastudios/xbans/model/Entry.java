package co.xenastudios.xbans.model;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import java.time.Instant;

/**
 * A punishment as xBans keeps it: the API's record, plus the IP key it was made on (the
 * address, or its salted hash), which the API never shows.
 *
 * @param ip the IP key, or null when it isn't known or the punishment isn't IP-based
 * @param staffWeight the weight of the staff member who made it, when they made it: who may
 *     lift it ({@link #CONSOLE} for the console, plugins and imports, 0 for staff without one)
 */
public record Entry(Punishment punishment, String ip, int staffWeight) {

    /** The weight recorded for the console and plugins: higher than any staff member's. */
    public static final int CONSOLE = Integer.MAX_VALUE;

    /** An entry with no recorded staff weight, as tests make. */
    public Entry(Punishment punishment, String ip) {
        this(punishment, ip, 0);
    }

    public long id() {
        return punishment.id();
    }

    public PunishmentType type() {
        return punishment.type();
    }

    /** In force now, ignoring scope. */
    public boolean active(long now) {
        Punishment p = punishment;
        return p.type().lasts() && p.removal() == null && (p.expires() == null || p.expires().toEpochMilli() > now);
    }

    public boolean appliesOn(String server) {
        return punishment.isGlobal() || punishment.scope().equalsIgnoreCase(server);
    }

    /** The same entry with its database id. */
    public Entry withId(long id) {
        Punishment p = punishment;
        return new Entry(new Punishment(id, p.type(), p.target(), p.targetName(), p.ipBased(), p.reason(), p.staff(),
            p.staffName(), p.created(), p.expires(), p.scope(), p.silent(), p.template(), p.removal()), ip, staffWeight);
    }

    /** The same entry, revoked. */
    public Entry revoked(co.xenastudios.xbans.api.Removal removal) {
        return new Entry(punishment.withRemoval(removal), ip, staffWeight);
    }

    /** How long is left, in milliseconds, or -1 when it's permanent. */
    public long left(long now) {
        Instant expires = punishment.expires();
        return expires == null ? -1 : Math.max(0, expires.toEpochMilli() - now);
    }
}
