package co.xenastudios.xmoney.hookconsumer;

import co.xenastudios.core.hook.VaultEconomy;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Never released. {@code /hooktest} charges and pays a player through the core's
 * {@link VaultEconomy}, as xPlots and xTeleport do, and logs {@code HOOK TEST PASS} or what
 * failed: proof the hook finds Vault, and an economy, when xMoney stands in for Vault.
 */
public final class HookConsumer extends JavaPlugin {

    @Override
    public void onEnable() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register("hooktest", "Charges and pays through the core's Vault hook.", new BasicCommand() {
                @Override
                public void execute(CommandSourceStack source, String[] args) {
                    run();
                }
            }));
    }

    private void run() {
        List<String> failures = new ArrayList<>();
        VaultEconomy economy = new VaultEconomy();
        if (!economy.installed()) {
            failures.add("Vault isn't seen as installed");
        }
        if (!economy.ready()) {
            failures.add("no economy is ready");
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString("00000000-0000-4000-8000-00000000beef"));
        double start = economy.balance(player);
        if (!economy.give(player, 25)) {
            failures.add("give failed");
        }
        if (!economy.has(player, start + 20)) {
            failures.add("has is false after give");
        }
        if (!economy.take(player, 20)) {
            failures.add("take failed");
        }
        if (economy.take(player, 1_000_000)) {
            failures.add("took more than the balance");
        }
        if (Math.abs(economy.balance(player) - (start + 5)) > 1e-9) {
            failures.add("balance is " + economy.balance(player));
        }
        String shown = economy.format(5);
        if (!"$5.00".equals(shown)) {
            failures.add("format gave " + shown);
        }
        getLogger().info(failures.isEmpty() ? "HOOK TEST PASS" : "HOOK TEST FAIL: " + String.join("; ", failures));
    }
}
