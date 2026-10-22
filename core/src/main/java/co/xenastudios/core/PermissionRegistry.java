package co.xenastudios.core;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Declares the permissions the config names, with their defaults. Because they're
 * declared from the config, a renamed permission keeps its default: a renamed
 * {@code /ping} permission is still everyone's.
 */
final class PermissionRegistry {

    /** The permissions we declared, so we only ever remove our own. */
    private final Map<String, PermissionDefault> declared = new HashMap<>();

    void sync(Map<String, PermissionDefault> wanted) {
        PluginManager manager = Bukkit.getPluginManager();
        for (String name : Map.copyOf(declared).keySet()) {
            if (!wanted.containsKey(name)) {
                manager.removePermission(name);
                declared.remove(name);
            }
        }
        wanted.forEach((name, permissionDefault) -> {
            Permission existing = manager.getPermission(name);
            if (existing == null) {
                manager.addPermission(new Permission(name, permissionDefault));
                declared.put(name, permissionDefault);
            } else if (declared.containsKey(name) && existing.getDefault() != permissionDefault) {
                existing.setDefault(permissionDefault);
                declared.put(name, permissionDefault);
            }
            // Otherwise another plugin declared it, and its default stands.
        });
    }

    void clear() {
        sync(Map.of());
    }
}
