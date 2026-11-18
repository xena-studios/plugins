package co.xenastudios.xbans.model;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import java.time.Instant;

/**
 * A punishment as xBans keeps it: the API's record, plus the IP key it was made on (the
 * address, or its salted hash), which the API never shows.
 *
 * @param ip the IP key, or null when it isn't known or the punishment isn't IP-based
 */
public record Entry(Punishment punishment, String ip) {

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
            p.staffName(), p.created(), p.expires(), p.scope(), p.silent(), p.template(), p.removal()), ip);
    }

    /** How long is left, in milliseconds, or -1 when it's permanent. */
    public long left(long now) {
        Instant expires = punishment.expires();
        return expires == null ? -1 : Math.max(0, expires.toEpochMilli() - now);
    }
}
