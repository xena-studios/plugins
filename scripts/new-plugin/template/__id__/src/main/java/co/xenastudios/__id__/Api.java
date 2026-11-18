package co.xenastudios.{{id}};

import co.xenastudios.core.Module;
import co.xenastudios.{{id}}.api.{{Class}}Api;
import org.bukkit.plugin.ServicePriority;

/**
 * {{display}}'s developer API, registered with the services manager while the plugin is
 * enabled. It adds no listeners and no tasks of its own.
 */
final class Api implements {{Class}}Api, Module {

    private final {{Class}} plugin;

    Api({{Class}} plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        plugin.getServer().getServicesManager().register({{Class}}Api.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        plugin.getServer().getServicesManager().unregister({{Class}}Api.class, this);
    }

    @Override
    public String version() {
        return plugin.getPluginMeta().getVersion();
    }
}
