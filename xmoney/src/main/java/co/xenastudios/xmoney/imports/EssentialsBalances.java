package co.xenastudios.xmoney.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * EssentialsX's balances, from {@code plugins/Essentials/userdata/<uuid>.yml}. Only three
 * top-level keys matter ({@code money}, {@code last-account-name} and {@code npc}), so each
 * file is scanned line by line rather than parsed whole: fast for tens of thousands of
 * players. Files that aren't named for a uuid, or have no {@code money}, are left out.
 */
public final class EssentialsBalances implements Source {

    private final Path userdata;
    private final Executor executor;

    /**
     * @param plugins the server's plugins folder
     * @param executor where to read, off the server's threads
     */
    public EssentialsBalances(Path plugins, Executor executor) {
        this.userdata = plugins.resolve("Essentials").resolve("userdata");
        this.executor = executor;
    }

    @Override
    public String name() {
        return "EssentialsX";
    }

    @Override
    public CompletableFuture<List<Found>> read() {
        return CompletableFuture.supplyAsync(this::readNow, executor);
    }

    List<Found> readNow() {
        if (!Files.isDirectory(userdata)) {
            throw new ImportException("there's no plugins/Essentials/userdata folder.", true);
        }
        List<Found> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(userdata)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Found one = file(file);
                if (one != null) {
                    found.add(one);
                }
            }
        } catch (IOException e) {
            throw new ImportException("couldn't list plugins/Essentials/userdata: " + e.getMessage(), false);
        }
        return found;
    }

    private static Found file(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".yml")) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(fileName.substring(0, fileName.length() - ".yml".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String money = null;
        String name = null;
        boolean npc = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                    continue; // Only top-level keys: homes and the rest are indented.
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon);
                String value = unquote(line.substring(colon + 1).strip());
                switch (key) {
                    case "money" -> money = value;
                    case "last-account-name", "lastAccountName" -> name = value;
                    case "npc" -> npc = value.equalsIgnoreCase("true");
                    default -> { }
                }
            }
        } catch (IOException e) {
            return new Found(id, null, true, "unreadable");
        }
        return money == null ? null : new Found(id, name == null || name.isEmpty() ? null : name, !npc, money);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"') && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
