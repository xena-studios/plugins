package co.xenastudios.xmoney.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.money.Money;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading other plugins' balances, and planning an import from them. */
class ImportTest {

    @TempDir
    Path plugins;

    @Test
    void readsEssentialsUserdata() throws Exception {
        Path userdata = Files.createDirectories(plugins.resolve("Essentials/userdata"));
        UUID steve = UUID.randomUUID();
        UUID town = UUID.randomUUID();
        Files.writeString(userdata.resolve(steve + ".yml"), """
            timestamps:
              login: 1700000000000
            money: '1234.56'
            last-account-name: Steve
            homes:
              base:
                money: '999'
            """);
        Files.writeString(userdata.resolve(town + ".yml"), "npc: true\nlast-account-name: town-Foo\nmoney: \"50\"\n");
        Files.writeString(userdata.resolve(UUID.randomUUID() + ".yml"), "last-account-name: NoMoney\n");
        Files.writeString(userdata.resolve("not-a-uuid.yml"), "money: '5'\n");
        List<Found> found = new EssentialsBalances(plugins, Runnable::run).readNow().stream()
            .sorted(Comparator.comparing(Found::name)).toList();
        assertEquals(2, found.size());
        assertEquals(new Found(steve, "Steve", true, "1234.56"), found.get(0));
        assertEquals(new Found(town, "town-Foo", false, "50"), found.get(1));
    }

    @Test
    void readsCmiUsers() throws Exception {
        Path file = Files.createDirectories(plugins.resolve("CMI")).resolve("cmi.sqlite.db");
        UUID alex = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, player_uuid TEXT, username TEXT, nickname TEXT, Balance DOUBLE)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('" + alex + "', 'Alex', 10000000.5)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('broken', 'Nope', 1)");
        }
        List<Found> found = new CmiBalances(plugins, Runnable::run).readNow();
        assertEquals(1, found.size());
        assertEquals(alex, found.getFirst().id());
        assertEquals("Alex", found.getFirst().name());
        Importer.Plan plan = Importer.plan("CMI", found, 2, 1_000_000_000 * Money.ONE);
        assertEquals(10_000_000_5000L, plan.total());
    }

    @Test
    void cmiWithoutTheColumnsSaysWhatItHas() throws Exception {
        Path file = Files.createDirectories(plugins.resolve("CMI")).resolve("cmi.sqlite.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, who TEXT)");
        }
        ImportException problem = assertThrows(ImportException.class, () -> new CmiBalances(plugins, Runnable::run).readNow());
        assertTrue(problem.getMessage().contains("id, who"));
        assertFalse(problem.missing());
    }

    @Test
    void nothingThereIsSaidPlainly() {
        assertTrue(assertThrows(ImportException.class, () -> new EssentialsBalances(plugins, Runnable::run).readNow()).missing());
        assertTrue(assertThrows(ImportException.class, () -> new CmiBalances(plugins, Runnable::run).readNow()).missing());
    }

    @Test
    void aPlanSkipsWhatCantBeImported() {
        UUID once = UUID.randomUUID();
        List<Found> found = List.of(
            new Found(once, "A", true, "10.004"),
            new Found(once, "A again", true, "3"),
            new Found(UUID.randomUUID(), "B", true, "-5"),
            new Found(UUID.randomUUID(), "C", true, "NaN"),
            new Found(UUID.randomUUID(), "D", true, "1e20"),
            new Found(UUID.randomUUID(), "E", true, " 2.5 "));
        Importer.Plan plan = Importer.plan("Test", found, 2, 1_000_000 * Money.ONE);
        assertEquals(2, plan.lines().size());
        assertEquals(4, plan.skipped());
        assertEquals(10_0000L + 2_5000L, plan.total(), "10.004 rounds to 10.00");
    }
}
