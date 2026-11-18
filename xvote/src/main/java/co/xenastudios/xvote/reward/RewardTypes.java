package co.xenastudios.xvote.reward;

import co.xenastudios.xvote.api.RewardType;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.plugin.Plugin;

/** Reward types other plugins add through the API, by id in lower case. */
public final class RewardTypes {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** A registered type, and the plugin it belongs to. */
    public record Registered(Plugin plugin, RewardType type) {}

    private final Map<String, Registered> types = new ConcurrentHashMap<>();

    public void register(Plugin plugin, String id, RewardType type) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A reward type's id is 1 to 64 letters, digits, - and _; got \"" + id + "\".");
        }
        types.put(id.toLowerCase(Locale.ROOT), new Registered(java.util.Objects.requireNonNull(plugin), java.util.Objects.requireNonNull(type)));
    }

    public Registered get(String id) {
        return types.get(id.toLowerCase(Locale.ROOT));
    }
}
