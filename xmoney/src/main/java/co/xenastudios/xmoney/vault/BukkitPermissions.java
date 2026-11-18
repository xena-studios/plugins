package co.xenastudios.xmoney.vault;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Vault's {@code Permission}, for servers with no permissions plugin: what real Vault's
 * built-in fallback did, so plugins that look up a Permission provider still find one. It
 * answers from Bukkit's own permissions for online players (ops, and permissions plugins
 * give), says no for offline players, and has no groups. Registered at the lowest
 * priority, so LuckPerms or any permissions plugin that hooks Vault is used instead.
 */
@SuppressWarnings("deprecation") // Vault 1's name-based methods, which this class must implement.
final class BukkitPermissions extends net.milkbowl.vault.permission.Permission {

    BukkitPermissions(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "SuperPerms";
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public boolean hasSuperPermsCompat() {
        return true;
    }

    @Override
    public boolean playerHas(String world, String player, String permission) {
        Player online = player == null ? null : Bukkit.getPlayerExact(player);
        return online != null && online.hasPermission(permission);
    }

    @Override
    public boolean playerAdd(String world, String player, String permission) {
        return false;
    }

    @Override
    public boolean playerRemove(String world, String player, String permission) {
        return false;
    }

    @Override
    public boolean groupHas(String world, String group, String permission) {
        return false;
    }

    @Override
    public boolean groupAdd(String world, String group, String permission) {
        return false;
    }

    @Override
    public boolean groupRemove(String world, String group, String permission) {
        return false;
    }

    @Override
    public boolean playerInGroup(String world, String player, String group) {
        return false;
    }

    @Override
    public boolean playerAddGroup(String world, String player, String group) {
        return false;
    }

    @Override
    public boolean playerRemoveGroup(String world, String player, String group) {
        return false;
    }

    @Override
    public String[] getPlayerGroups(String world, String player) {
        return new String[0];
    }

    @Override
    public String getPrimaryGroup(String world, String player) {
        return null;
    }

    @Override
    public String[] getGroups() {
        return new String[0];
    }

    @Override
    public boolean hasGroupSupport() {
        return false;
    }
}
