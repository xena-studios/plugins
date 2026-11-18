package co.xenastudios.xplots.setup;

import co.xenastudios.xplots.model.Box;
import co.xenastudios.xplots.model.Layout;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Shows a field before it's made, to the player making it alone: each plot's outline in
 * particles, and a mark where each sign will go. Only plots near the player are drawn,
 * twice a second, until the field is confirmed or cancelled.
 */
final class Preview {

    private static final double RANGE = 48;
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(0xA9D750), 1.0f);
    private static final Particle.DustOptions SIGN = new Particle.DustOptions(Color.fromRGB(0x5EE08A), 1.6f);

    private final BukkitTask task;

    Preview(Plugin plugin, Player player, String world, Layout layout) {
        List<Layout.Spot> spots = layout.spots();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> draw(player, world, layout, spots), 0, 10);
    }

    void stop() {
        task.cancel();
    }

    private static void draw(Player player, String world, Layout layout, List<Layout.Spot> spots) {
        if (!player.isOnline() || !player.getWorld().getName().equals(world)) {
            return;
        }
        Location eye = player.getLocation();
        double y = layout.area().minY() + 1.1;
        for (Layout.Spot spot : spots) {
            Box box = spot.box();
            double cx = (box.minX() + box.maxX() + 1) / 2.0;
            double cz = (box.minZ() + box.maxZ() + 1) / 2.0;
            if (Math.abs(cx - eye.getX()) > RANGE || Math.abs(cz - eye.getZ()) > RANGE) {
                continue;
            }
            for (int x = box.minX(); x <= box.maxX() + 1; x++) {
                player.spawnParticle(Particle.DUST, x, y, box.minZ(), 1, EDGE);
                player.spawnParticle(Particle.DUST, x, y, box.maxZ() + 1, 1, EDGE);
            }
            for (int z = box.minZ() + 1; z <= box.maxZ(); z++) {
                player.spawnParticle(Particle.DUST, box.minX(), y, z, 1, EDGE);
                player.spawnParticle(Particle.DUST, box.maxX() + 1, y, z, 1, EDGE);
            }
            player.spawnParticle(Particle.DUST, spot.signX() + 0.5, spot.signY() + 0.5, spot.signZ() + 0.5, 3, 0.15, 0.3, 0.15, SIGN);
        }
    }

}
