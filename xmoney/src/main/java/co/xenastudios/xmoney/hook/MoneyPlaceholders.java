package co.xenastudios.xmoney.hook;

import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.ledger.Account;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI's {@code %xmoney_...%}, answered from memory on any thread. Only loaded when
 * PlaceholderAPI is installed.
 *
 * <ul>
 *   <li>{@code balance}: formatted, {@code $1,234.50}; {@code balance_raw}: {@code 1234.50};
 *       {@code balance_short}: {@code 1.2k}</li>
 *   <li>{@code top_<rank>_name}, {@code top_<rank>_balance} (and {@code _raw}, {@code _short}):
 *       the top list, ranks 1 to 100. Blank past the end.</li>
 * </ul>
 */
public final class MoneyPlaceholders extends PlaceholderExpansion {

    private final XMoney plugin;

    public MoneyPlaceholders(XMoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return XMoney.ID;
    }

    @Override
    public String getAuthor() {
        return "Xena Studios";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return PlaceholderValues.resolve(plugin.bank(), player == null ? null : plugin.bank().accounts().get(player.getUniqueId()), params);
    }
}
