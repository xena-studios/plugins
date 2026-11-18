package co.xenastudios.xbans.importer;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.model.Entry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * The server's own lists, {@code banned-players.json} and {@code banned-ips.json}, which
 * vanilla's /ban and EssentialsX's /ban both write.
 */
final class VanillaSource implements Source {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    @Override
    public String name() {
        return "vanilla";
    }

    @Override
    public Haul read(Path server, Path plugins, String scope) throws ImportException {
        Haul haul = new Haul();
        Path players = server.resolve("banned-players.json");
        Path ips = server.resolve("banned-ips.json");
        if (!Files.exists(players) && !Files.exists(ips)) {
            throw new ImportException("there's no banned-players.json or banned-ips.json in the server's folder.");
        }
        long now = System.currentTimeMillis();
        int index = 0;
        for (JsonObject ban : list(players)) {
            index++;
            UUID id = uuid(text(ban, "uuid"));
            if (id == null) {
                continue;
            }
            String name = text(ban, "name");
            add(haul, ban, id, name == null ? id.toString() : name, null, scope, now, "vanilla:player:" + id + ":" + text(ban, "created"));
        }
        for (JsonObject ban : list(ips)) {
            index++;
            String ip = text(ban, "ip");
            if (ip == null || !IpKeys.punishable(ip)) {
                continue;
            }
            add(haul, ban, null, IpKeys.label(ip), ip, scope, now, "vanilla:ip:" + ip + ":" + text(ban, "created"));
        }
        if (index == 0) {
            throw new ImportException("the server's ban lists are empty.");
        }
        return haul;
    }

    private static void add(Haul haul, JsonObject ban, UUID id, String name, String ip, String scope, long now, String key) {
        Instant created = date(text(ban, "created"), Instant.ofEpochMilli(now));
        String expiresText = text(ban, "expires");
        Instant expires = expiresText == null || expiresText.equalsIgnoreCase("forever") ? null : date(expiresText, null);
        if (expires != null && expires.toEpochMilli() <= now) {
            return; // Ended: vanilla forgets these too.
        }
        String source = text(ban, "source");
        String reason = text(ban, "reason");
        Punishment punishment = new Punishment(0, PunishmentType.BAN, id, name, ip != null,
            reason == null ? "Banned by an operator." : reason, null, source == null ? "Server" : source, created, expires,
            scope, false, null, null);
        haul.add(new Entry(punishment, ip), key);
    }

    private static Iterable<JsonObject> list(Path file) throws ImportException {
        if (!Files.exists(file)) {
            return java.util.List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                throw new ImportException(file.getFileName() + " isn't a list.");
            }
            JsonArray array = root.getAsJsonArray();
            java.util.List<JsonObject> objects = new java.util.ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    objects.add(element.getAsJsonObject());
                }
            }
            return objects;
        } catch (IOException | RuntimeException e) {
            throw new ImportException("couldn't read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static UUID uuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant date(String text, Instant fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return ZonedDateTime.parse(text, DATE).toInstant();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
