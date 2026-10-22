package co.xenastudios.xlimbo.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Asks the proxy (BungeeCord, Waterfall or Velocity) to move a player to another server. */
public final class Proxy {

    static final String CHANNEL = "BungeeCord";

    private Proxy() {}

    /** Opens the channel the proxy listens on. Safe to call again. */
    public static void open(Plugin plugin) {
        if (!Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, CHANNEL)) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        }
    }

    /** Sends the player to {@code server}, a name from the proxy's config. */
    public static void connect(Plugin plugin, Player player, String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
    }
}
