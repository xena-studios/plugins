package co.xenastudios.xmoney.vault;

import co.xenastudios.xmoney.XMoney;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

/**
 * xMoney's stand-in for Vault. The jar carries Vault's and VaultUnlocked's API classes
 * (unmodified, LGPL-3.0) and its {@code paper-plugin.yml} says {@code provides: [Vault]}, so
 * plugins that depend on Vault load and find those classes here. This registers the economy
 * under both APIs, at the highest priority, so plugins pick it over any other economy still
 * installed (one an import reads from, say). It also registers a Permission provider for
 * servers without a permissions plugin, as real Vault did ({@link BukkitPermissions}).
 *
 * <p>A real Vault or VaultUnlocked jar can't run beside it: Paper loads xMoney first, as the
 * provider of "Vault", and then refuses the real one as a duplicate, with an error of its own
 * that can't be silenced. Other plugins keep working through xMoney. {@link #warnAboutVault}
 * tells the owner which jar to delete.
 */
@SuppressWarnings("deprecation") // Vault 1's Economy, which most plugins still use.
public final class VaultServices {

    private final XMoney plugin;
    private LegacyEconomy legacy;
    private UnlockedEconomy unlocked;
    private BukkitPermissions permissions;

    public VaultServices(XMoney plugin) {
        this.plugin = plugin;
    }

    public void register() {
        VaultBridge bridge = new VaultBridge(plugin);
        legacy = new LegacyEconomy(bridge);
        unlocked = new UnlockedEconomy(bridge);
        Bukkit.getServicesManager().register(net.milkbowl.vault.economy.Economy.class, legacy, plugin, ServicePriority.Highest);
        Bukkit.getServicesManager().register(net.milkbowl.vault2.economy.Economy.class, unlocked, plugin, ServicePriority.Highest);
        // Real Vault's fallback for servers without a permissions plugin; any that hooks Vault wins.
        permissions = new BukkitPermissions(plugin);
        Bukkit.getServicesManager().register(net.milkbowl.vault.permission.Permission.class, permissions, plugin, ServicePriority.Lowest);
    }

    public void unregister() {
        if (legacy != null) {
            Bukkit.getServicesManager().unregister(net.milkbowl.vault.economy.Economy.class, legacy);
            Bukkit.getServicesManager().unregister(net.milkbowl.vault2.economy.Economy.class, unlocked);
            Bukkit.getServicesManager().unregister(net.milkbowl.vault.permission.Permission.class, permissions);
            legacy = null;
            unlocked = null;
            permissions = null;
        }
    }

    /** Whether this economy is xMoney's own, so an import from Vault can skip it. */
    public static boolean ours(Object economy) {
        return economy instanceof LegacyEconomy || economy instanceof UnlockedEconomy;
    }

    /** Warns about each Vault or VaultUnlocked jar left in the plugins folder. Reads files: off the server's threads. */
    public void warnAboutVault() {
        for (String jar : vaultJars(plugin.getDataFolder().toPath().getParent())) {
            plugin.getLogger().warning(jar + " is in plugins/. " + XMoney.NAME + " provides the Vault and VaultUnlocked APIs itself,"
                + " so Paper didn't load it: that's the \"Could not load plugin '" + jar + "'\" error above. Other plugins"
                + " use " + XMoney.NAME + " instead. Delete " + jar + " to clear the error.");
        }
    }

    /** The names of jars in {@code folder} whose plugin is called Vault. */
    static List<String> vaultJars(Path folder) {
        List<String> found = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return found;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, "*.jar")) {
            for (Path jar : jars) {
                if (isVault(jar)) {
                    found.add(jar.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
            // A folder that can't be listed has nothing to warn about.
        }
        found.sort(null);
        return found;
    }

    private static boolean isVault(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String descriptor : List.of("plugin.yml", "paper-plugin.yml")) {
                ZipEntry entry = zip.getEntry(descriptor);
                if (entry == null) {
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("name:")) {
                            String name = line.substring(5).strip().replace("\"", "").replace("'", "");
                            return name.equals("Vault");
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Not a jar we can read: not Vault.
        }
        return false;
    }
}
