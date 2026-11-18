package co.xenastudios.xutilities;

import co.xenastudios.core.Module;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.xutilities.api.XUtilitiesApi;
import co.xenastudios.xutilities.command.BackTracker;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * xUtilities' developer API, registered with the services manager while the plugin is
 * enabled. Everything it answers is already in memory, so it adds nothing to run.
 */
final class Api implements XUtilitiesApi, Module {

    private final XUtilities plugin;
    private final BackTracker tracker;
    // Swapped whole on every reload.
    private volatile Set<String> commands = Set.of();

    Api(XUtilities plugin, BackTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /** The commands this config turned on: names and aliases, lowercase. */
    void commands(List<CommandSpec> specs) {
        Set<String> labels = new HashSet<>();
        for (CommandSpec spec : specs) {
            labels.add(spec.name().toLowerCase(Locale.ROOT));
            spec.aliases().forEach(alias -> labels.add(alias.toLowerCase(Locale.ROOT)));
        }
        commands = Set.copyOf(labels);
    }

    @Override
    public void enable() {
        plugin.getServer().getServicesManager().register(XUtilitiesApi.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        plugin.getServer().getServicesManager().unregister(XUtilitiesApi.class, this);
    }

    @Override
    public boolean isBackEnabled() {
        return tracker.enabled();
    }

    @Override
    public Optional<Location> getBackLocation(Player player) {
        Location location = tracker.enabled() ? tracker.previous(player) : null;
        return location == null ? Optional.empty() : Optional.of(location.clone());
    }

    @Override
    public boolean setBackLocation(Player player, Location location) {
        if (!location.isWorldLoaded()) {
            throw new IllegalArgumentException("A /back location must be in a loaded world");
        }
        return tracker.remember(player, location.clone());
    }

    @Override
    public void clearBackLocation(Player player) {
        tracker.forget(player);
    }

    @Override
    public boolean isCommandEnabled(String label) {
        String name = label.startsWith("/") ? label.substring(1) : label;
        return commands.contains(name.toLowerCase(Locale.ROOT));
    }
}
