package co.xenastudios.xbans.importer;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.model.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * EssentialsX's mutes, and each player's last name and IP, from
 * {@code plugins/Essentials/userdata/<uuid>.yml}. EssentialsX keeps bans in the server's
 * own lists: import those with {@code vanilla}.
 */
final class EssentialsSource implements Source {

    @Override
    public String name() {
        return "essentials";
    }

    @Override
    public Haul read(Path server, Path plugins, String scope) throws ImportException {
        Path folder = plugins.resolve("Essentials/userdata");
        if (!Files.isDirectory(folder)) {
            throw new ImportException("there's no Essentials/userdata folder in plugins/.");
        }
        Haul haul = new Haul();
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".yml")) {
                    continue;
                }
                UUID id = VanillaSource.uuid(fileName.substring(0, fileName.length() - 4));
                if (id == null) {
                    continue;
                }
                read(haul, YamlConfiguration.loadConfiguration(file.toFile()), id, scope, now);
            }
        } catch (IOException e) {
            throw new ImportException("couldn't read plugins/Essentials/userdata: " + e.getMessage(), e);
        }
        return haul;
    }

    static void read(Haul haul, YamlConfiguration data, UUID id, String scope, long now) {
        String name = first(data, "last-account-name", "lastAccountName");
        if (name == null) {
            name = id.toString();
        }
        long login = data.getLong("timestamps.login", 0);
        long logout = data.getLong("timestamps.logout", 0);
        long last = Math.max(login, logout);
        String ip = first(data, "ip-address", "ipAddress");
        haul.seen(id, name, ip == null || ip.isBlank() ? null : ip, last > 0 ? last : now, last > 0 ? last : now);
        if (!data.getBoolean("muted", false)) {
            return;
        }
        long until = data.getLong("timestamps.mute", 0);
        if (until > 0 && until <= now) {
            return;
        }
        String reason = first(data, "mute-reason", "muteReason");
        Punishment mute = new Punishment(0, PunishmentType.MUTE, id, name, false,
            reason == null || reason.isBlank() ? "Muted" : reason, null, "EssentialsX", Instant.ofEpochMilli(now),
            until > 0 ? Instant.ofEpochMilli(until) : null, scope, false, null, null);
        haul.add(new Entry(mute, null), "essentials:mute:" + id);
    }

    private static String first(YamlConfiguration data, String... keys) {
        for (String key : keys) {
            String value = data.getString(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
