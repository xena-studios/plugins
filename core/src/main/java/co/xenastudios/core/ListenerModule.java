package co.xenastudios.core;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** A module whose event handlers are registered only while it's on. */
public abstract class ListenerModule implements Module, Listener {

    protected final Plugin plugin;

    protected ListenerModule(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }
}
