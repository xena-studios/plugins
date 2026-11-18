package co.xenastudios.xmoney.vaultconsumer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Never released. Uses Vault's and VaultUnlocked's APIs the ways real plugins do, including
 * the bad ones: from other threads, for players who never joined, by name, and with NaN,
 * negative and enormous amounts. {@code /vaulttest} logs {@code VAULT TEST PASS} or what failed.
 *
 * <p>It also registers a second, tiny economy at the lowest priority, as an old economy
 * plugin left installed would, so {@code /xmoney import vault} has something to find.
 */
@SuppressWarnings("deprecation")
public final class VaultConsumer extends JavaPlugin {

    private final List<String> failures = new ArrayList<>();

    @Override
    public void onEnable() {
        getServer().getServicesManager().register(Economy.class, new OldEconomy(), this, ServicePriority.Lowest);
        Economy economy = v1();
        getLogger().info("VAULT CONSUMER found " + (economy == null ? "no economy" : economy.getName()));
    }

    private static Economy v1() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    private static net.milkbowl.vault2.economy.Economy v2() {
        RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> provider =
            Bukkit.getServicesManager().getRegistration(net.milkbowl.vault2.economy.Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    private void check(boolean ok, String what) {
        if (!ok) {
            failures.add(what);
        }
    }

    private static boolean near(double value, double expected) {
        return Math.abs(value - expected) < 1e-9;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("vaultperms")) {
            permissions(sender);
            return true;
        }
        failures.clear();
        try {
            legacy();
            unlocked();
        } catch (RuntimeException | InterruptedException e) {
            failures.add("threw " + e);
        }
        getLogger().info(failures.isEmpty() ? "VAULT TEST PASS" : "VAULT TEST FAIL: " + String.join("; ", failures));
        return true;
    }

    private void legacy() throws InterruptedException {
        Economy economy = v1();
        check(economy != null && economy.getName().equals("xMoney"), "v1 provider is " + (economy == null ? "missing" : economy.getName()));
        if (economy == null) {
            return;
        }
        // A player who never joined: no account, nothing to take, nothing made by asking.
        OfflinePlayer stranger = Bukkit.getOfflinePlayer(UUID.randomUUID());
        check(!economy.hasAccount(stranger), "a stranger has an account");
        check(economy.getBalance(stranger) == 0, "a stranger has money");
        check(!economy.withdrawPlayer(stranger, 5).transactionSuccess(), "took money from a stranger");
        check(!economy.hasAccount(stranger), "asking made an account");

        // Amounts that aren't amounts change nothing.
        for (double bad : new double[] {Double.NaN, -5, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300}) {
            check(!economy.depositPlayer(stranger, bad).transactionSuccess(), "deposited " + bad);
            check(!economy.withdrawPlayer(stranger, bad).transactionSuccess(), "withdrew " + bad);
        }
        check(economy.getBalance(stranger) == 0, "bad amounts changed a balance");
        check(!economy.has(stranger, Double.NaN), "has(NaN) was true");

        // Paying a stranger makes their account; 0.1 + 0.2 is exactly 0.30.
        check(economy.depositPlayer(stranger, 100).transactionSuccess(), "deposit to a stranger failed");
        check(economy.withdrawPlayer(stranger, 30).transactionSuccess(), "withdrawal failed");
        check(!economy.withdrawPlayer(stranger, 1000).transactionSuccess(), "overdrew");
        economy.depositPlayer(stranger, 0.1);
        economy.depositPlayer(stranger, 0.2);
        check(near(economy.getBalance(stranger), 70.30), "balance is " + economy.getBalance(stranger) + ", not 70.30");
        check(economy.has(stranger, 70.30) && !economy.has(stranger, 70.31), "has() is off");

        // From other threads at once: every change counted, none lost.
        int threads = 8;
        int each = 250;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                try {
                    for (int i = 0; i < each; i++) {
                        economy.depositPlayer(stranger, 1.25);
                        economy.withdrawPlayer(stranger, 0.25);
                    }
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        check(done.await(30, TimeUnit.SECONDS), "threads didn't finish");
        check(errors.get() == 0, errors.get() + " threads threw");
        check(near(economy.getBalance(stranger), 70.30 + threads * each), "after threads the balance is " + economy.getBalance(stranger));

        // The old name-based calls: a town's bank, made by name.
        check(economy.depositPlayer("VaultTestBank", 50).transactionSuccess(), "deposit by name failed");
        check(economy.hasAccount("VaultTestBank") && near(economy.getBalance("VaultTestBank"), 50), "account by name is missing");

        // Banks aren't supported, and say so.
        check(economy.createBank("bank", stranger).type == EconomyResponse.ResponseType.NOT_IMPLEMENTED, "banks answered");
        check(economy.fractionalDigits() == 2, "fractional digits are " + economy.fractionalDigits());
        check(economy.format(1234.5).equals("$1,234.50"), "format gave " + economy.format(1234.5));
    }

    private void unlocked() {
        net.milkbowl.vault2.economy.Economy economy = v2();
        check(economy != null && economy.getName().equals("xMoney"), "v2 provider is " + (economy == null ? "missing" : economy.getName()));
        if (economy == null) {
            return;
        }
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        check(economy.deposit(getName(), payer, new BigDecimal("10.005")).transactionSuccess(), "v2 deposit failed");
        // Rounded half up to the currency's 2 decimals.
        check(economy.getBalance(getName(), payer).compareTo(new BigDecimal("10.01")) == 0, "v2 balance is " + economy.getBalance(getName(), payer));
        check(!economy.withdraw(getName(), payer, new BigDecimal("-1")).transactionSuccess(), "v2 withdrew a negative");
        check(!economy.withdraw(getName(), payer, new BigDecimal("1000")).transactionSuccess(), "v2 overdrew");
        var moved = economy.transfer(getName(), payer, payee, new BigDecimal("4"));
        check(moved.type() == net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS, "v2 transfer failed: " + moved.errorMessage());
        check(economy.getBalance(getName(), payee).compareTo(new BigDecimal("4")) == 0, "v2 transfer didn't arrive");
        check(!economy.transfer(getName(), payer, payee, new BigDecimal("100")).type().equals(
            net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS), "v2 transfer overdrew");
        check(economy.getUUIDNameMap().containsKey(payee), "v2 account list is missing an account");
        check(!economy.hasCurrency("gems") && economy.getBalance(getName(), payer, "world", "gems").signum() == 0, "v2 knows gems");
    }

    // Who answers Vault's other two APIs: a permissions plugin such as LuckPerms, or nobody.
    private void permissions(CommandSender sender) {
        RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> permission =
            Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
        RegisteredServiceProvider<net.milkbowl.vault.chat.Chat> chat =
            Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
        String groups = permission == null ? "-" : String.join(",", permission.getProvider().getGroups());
        getLogger().info("VAULT PERMISSION " + (permission == null ? "none" : permission.getProvider().getName() + " groups=" + groups)
            + ", CHAT " + (chat == null ? "none" : chat.getProvider().getName()));
        if (permission != null) {
            getLogger().info("VAULT PERMISSION console has vaulttest.check: " + permission.getProvider().has(sender, "vaulttest.check"));
        }
    }

    /** An old economy plugin left installed: one player, by name. */
    private static final class OldEconomy extends AbstractEconomy {
        @Override public boolean isEnabled() { return true; }
        @Override public String getName() { return "OldEconomy"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double amount) { return String.valueOf(amount); }
        @Override public String currencyNamePlural() { return "coins"; }
        @Override public String currencyNameSingular() { return "coin"; }
        @Override public boolean hasAccount(String name) { return true; }
        @Override public boolean hasAccount(String name, String world) { return true; }
        @Override public double getBalance(String name) { return 42; }
        @Override public double getBalance(String name, String world) { return 42; }
        @Override public boolean has(String name, double amount) { return amount <= 42; }
        @Override public boolean has(String name, String world, double amount) { return amount <= 42; }
        @Override public EconomyResponse withdrawPlayer(String name, double amount) { return none(); }
        @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return none(); }
        @Override public EconomyResponse depositPlayer(String name, double amount) { return none(); }
        @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return none(); }
        @Override public EconomyResponse createBank(String name, String player) { return none(); }
        @Override public EconomyResponse deleteBank(String name) { return none(); }
        @Override public EconomyResponse bankBalance(String name) { return none(); }
        @Override public EconomyResponse bankHas(String name, double amount) { return none(); }
        @Override public EconomyResponse bankWithdraw(String name, double amount) { return none(); }
        @Override public EconomyResponse bankDeposit(String name, double amount) { return none(); }
        @Override public EconomyResponse isBankOwner(String name, String player) { return none(); }
        @Override public EconomyResponse isBankMember(String name, String player) { return none(); }
        @Override public List<String> getBanks() { return List.of(); }
        @Override public boolean createPlayerAccount(String name) { return false; }
        @Override public boolean createPlayerAccount(String name, String world) { return false; }

        private static EconomyResponse none() {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "read only");
        }
    }
}
