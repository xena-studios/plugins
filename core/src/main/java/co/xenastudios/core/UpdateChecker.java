package co.xenastudios.core;

import co.xenastudios.core.text.Placeholders;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Asks Modrinth for the newest version at startup and every 24 hours, off the server
 * threads. It sends the plugin version (in the User-Agent), the platform and the
 * Minecraft version, and nothing else.
 */
final class UpdateChecker {

    private static final Duration PERIOD = Duration.ofHours(24);

    private final XenaPlugin<?> plugin;
    private final String project;
    private final String current;
    private volatile String latest;
    private String announced;
    private ScheduledTask task;
    private HttpClient client;

    UpdateChecker(XenaPlugin<?> plugin, String project) {
        this.plugin = plugin;
        this.project = project;
        this.current = plugin.getPluginMeta().getVersion();
    }

    /** The newer version, if one has been found. */
    String latest() {
        return latest;
    }

    /** Starts or stops checking to match the config. */
    synchronized void sync(boolean enabled) {
        // Tests run with -Dxena.offline=true so they never reach Modrinth.
        enabled &= !Boolean.getBoolean("xena.offline");
        if (enabled && task == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            task = plugin.tasks().asyncRepeating(this::check, Duration.ZERO, PERIOD);
        } else if (!enabled && task != null) {
            task.cancel();
            task = null;
            // Not close(): that waits for a check in flight, stalling the thread that turned us off.
            client.shutdownNow();
            client = null;
            latest = null;
        }
    }

    /** A module that tells admins about a newer version when they join. */
    Module joinNotice() {
        return new JoinNotice(plugin);
    }

    private final class JoinNotice extends ListenerModule {

        JoinNotice(XenaPlugin<?> owner) {
            super(owner);
        }

        @EventHandler
        void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            String newer = latest;
            if (newer != null && player.hasPermission(UpdateChecker.this.plugin.adminPermission())) {
                player.sendMessage(notice(newer));
            }
        }
    }

    private void check() {
        HttpClient http;
        synchronized (this) {
            http = client;
        }
        if (http == null) {
            return;
        }
        try {
            String query = "?loaders=" + encode("[\"" + (plugin.isFolia() ? "folia" : "paper") + "\"]")
                + "&game_versions=" + encode("[\"" + Bukkit.getMinecraftVersion() + "\"]")
                + "&include_changelog=false";
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + project + "/version" + query))
                .header("User-Agent", "xena-studios/" + project + "/" + current + " (contact@xenastudios.co)")
                .timeout(Duration.ofSeconds(20))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                plugin.getLogger().fine("Update check got HTTP " + response.statusCode());
                return;
            }
            String newest = newest(JsonParser.parseString(response.body()).getAsJsonArray());
            if (newest != null && Versions.compare(newest, current) > 0) {
                latest = newest;
                if (!newest.equals(announced)) {
                    announced = newest;
                    plugin.getComponentLogger().info(notice(newest));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Offline, Modrinth down, or an unexpected reply: try again next time, quietly.
            plugin.getLogger().fine("Update check failed: " + e);
        }
    }

    // The newest release; betas count only for servers already running a beta.
    private String newest(JsonArray versions) {
        boolean betas = Versions.isPrerelease(current);
        String newest = null;
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            String type = version.get("version_type").getAsString();
            String number = version.get("version_number").getAsString();
            if (("release".equals(type) || (betas && "beta".equals(type)))
                && (newest == null || Versions.compare(number, newest) > 0)) {
                newest = number;
            }
        }
        return newest;
    }

    private Component notice(String version) {
        return plugin.config().updateChecker().message().render(
            Placeholders.text("plugin", plugin.getName()),
            Placeholders.text("current", current),
            Placeholders.text("latest", version),
            Placeholders.text("url", "https://modrinth.com/plugin/" + project + "/version/" + version));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
