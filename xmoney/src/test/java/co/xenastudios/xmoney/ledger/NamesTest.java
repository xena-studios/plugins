package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.imports.Found;
import co.xenastudios.xmoney.imports.Importer;
import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A name finds the player who has it now, or nobody: never another player who once had it. */
class NamesTest {

    @TempDir
    Path folder;
    Bench bench;

    @BeforeEach
    void start() throws Exception {
        bench = new Bench(Bench.sqlite(folder.resolve("money.db")), false);
    }

    @AfterEach
    void stop() {
        bench.close();
    }

    // Two stored accounts with the same name: a name that changed hands while one was away.
    private Bench twoSteves(UUID first, UUID second) throws Exception {
        bench.ledger.open(first, "Steve", 0);
        bench.ledger.open(second, "Steve", 0);
        bench.close();
        return new Bench(bench.database, false);
    }

    @Test
    void aNameTwoStoredAccountsShareFindsNeitherUntilOneJoins() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (Bench again = twoSteves(first, second)) {
            assertNull(again.accounts.named("Steve"), "whichever loaded last isn't guessed at");
            assertTrue(again.accounts.ambiguous("steve"));
            assertNull(again.ledger.named("Steve"), "Vault's name-based calls are refused, not paid to a made-up account");
            // The first Steve joins: the name is theirs from now on, though it didn't change.
            Account joined = again.ledger.open(first, "Steve", 0);
            assertSame(joined, again.accounts.named("steve"));
            assertFalse(again.accounts.ambiguous("Steve"));
        }
    }

    @Test
    void aJoiningPlayerTakesTheirNameFromWhoeverHadIt() {
        Account old = bench.ledger.open(UUID.randomUUID(), "Steve", 0);
        Account now = bench.ledger.open(UUID.randomUUID(), "Steve", 0);
        assertSame(now, bench.accounts.named("Steve"));
        bench.ledger.open(old.id(), "Steve", 0); // the old one joins again before renaming
        assertSame(old, bench.accounts.named("Steve"), "rejoining under an unchanged name still points it at them");
        bench.ledger.open(old.id(), "Stevie", 0);
        assertSame(old, bench.accounts.named("Stevie"));
        assertNull(bench.accounts.named("Steve"), "a name nobody joined under since finds nobody");
    }

    @Test
    void aPluginCantRenameAnAccountToANameAnotherHas() {
        Account steve = bench.ledger.open(UUID.randomUUID(), "Steve", 0);
        Account town = bench.ledger.named("town-Foo");
        assertFalse(bench.ledger.rename(town, "Steve"));
        assertSame(steve, bench.accounts.named("steve"));
        assertTrue(bench.ledger.rename(town, "town-Bar"));
        assertSame(town, bench.accounts.named("town-bar"));
        assertNull(bench.accounts.named("town-foo"));
    }

    @Test
    void importsNeitherRenameAccountsNorTakeTheirNames() throws Exception {
        Account steve = bench.ledger.open(UUID.randomUUID(), "Steve", 0);
        Account alex = bench.ledger.open(UUID.randomUUID(), "Alex", 0);
        UUID stranger = UUID.randomUUID();
        Bank bank = new Bank(bench.ledger, () -> XMConfig.DEFAULTS, Logger.getAnonymousLogger(), () -> false);
        Importer.Plan plan = Importer.plan("Test", List.of(
            new Found(steve.id(), "OldSteveName", true, "10"),
            new Found(stranger, "Alex", true, "20")), Bench.MAX);
        assertEquals(2, Importer.apply(bank, plan).join());
        assertEquals("Steve", steve.name(), "an account xMoney has keeps its name");
        assertSame(steve, bench.accounts.named("steve"));
        assertNull(bench.accounts.named("OldSteveName"));
        assertSame(alex, bench.accounts.named("Alex"), "the new account didn't take a name Alex has");
        assertEquals(20 * Money.ONE, bench.accounts.get(stranger).balance());
        assertEquals(10 * Money.ONE, steve.balance());
    }
}
