package co.xenastudios.xbans.storage;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.Removal;
import co.xenastudios.xbans.model.Entry;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/** Reading and writing punishment rows. */
final class Rows {

    private Rows() {}

    /** A punishment with its removal, if any: the columns {@link #read} expects, in order. */
    static final String SELECT = "SELECT p.id, p.type, p.uuid, p.name, p.ip, p.ip_based, p.reason, p.staff_uuid,"
        + " p.staff_name, p.created, p.expires, p.scope, p.silent, p.template,"
        + " r.staff_uuid, r.staff_name, r.removed, r.reason, p.staff_weight"
        + " FROM {p}punishments p LEFT JOIN {p}removals r ON r.punishment = p.id";

    static final String INSERT = "INSERT INTO {p}punishments (type, uuid, name, ip, ip_based, reason, staff_uuid,"
        + " staff_name, created, expires, scope, origin, silent, template, imported, staff_weight)"
        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static int code(PunishmentType type) {
        return switch (type) {
            case BAN -> 1;
            case MUTE -> 2;
            case WARN -> 3;
            case KICK -> 4;
        };
    }

    static PunishmentType type(int code) {
        return switch (code) {
            case 1 -> PunishmentType.BAN;
            case 2 -> PunishmentType.MUTE;
            case 3 -> PunishmentType.WARN;
            default -> PunishmentType.KICK;
        };
    }

    static Entry read(ResultSet row) throws SQLException {
        Removal removal = null;
        long removed = row.getLong(17);
        if (!row.wasNull()) {
            removal = new Removal(uuid(row.getString(15)), row.getString(16), Instant.ofEpochMilli(removed), row.getString(18));
        }
        long expires = row.getLong(11);
        Punishment punishment = new Punishment(
            row.getLong(1),
            type(row.getInt(2)),
            uuid(row.getString(3)),
            row.getString(4),
            row.getInt(6) != 0,
            row.getString(7),
            uuid(row.getString(8)),
            row.getString(9),
            Instant.ofEpochMilli(row.getLong(10)),
            expires <= 0 ? null : Instant.ofEpochMilli(expires),
            row.getString(12),
            row.getInt(13) != 0,
            row.getString(14),
            removal);
        return new Entry(punishment, row.getString(5), row.getInt(19));
    }

    /** Fills {@link #INSERT}'s parameters. */
    static void bind(PreparedStatement insert, Entry entry, String origin, String imported) throws SQLException {
        Punishment p = entry.punishment();
        insert.setInt(1, code(p.type()));
        setUuid(insert, 2, p.target());
        insert.setString(3, clip(p.targetName(), 64));
        if (entry.ip() == null) {
            insert.setNull(4, Types.VARCHAR);
        } else {
            insert.setString(4, entry.ip());
        }
        insert.setInt(5, p.ipBased() ? 1 : 0);
        insert.setString(6, clip(p.reason(), 1024));
        setUuid(insert, 7, p.staff());
        insert.setString(8, clip(p.staffName(), 64));
        insert.setLong(9, p.created().toEpochMilli());
        insert.setLong(10, p.expires() == null ? 0 : p.expires().toEpochMilli());
        insert.setString(11, clip(p.scope(), 64));
        insert.setString(12, clip(origin, 64));
        insert.setInt(13, p.silent() ? 1 : 0);
        if (p.template() == null) {
            insert.setNull(14, Types.VARCHAR);
        } else {
            insert.setString(14, clip(p.template(), 64));
        }
        if (imported == null) {
            insert.setNull(15, Types.VARCHAR);
        } else {
            insert.setString(15, clip(imported, 96));
        }
        insert.setInt(16, entry.staffWeight());
    }

    static void setUuid(PreparedStatement statement, int index, UUID id) throws SQLException {
        if (id == null) {
            statement.setNull(index, Types.CHAR);
        } else {
            statement.setString(index, id.toString());
        }
    }

    static UUID uuid(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
