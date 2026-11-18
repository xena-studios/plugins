package co.xenastudios.xteleport.config;

import co.xenastudios.core.command.Toggle;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.Range;
import java.util.List;

/** The record for each kind of command's settings. */
public final class Settings {

    private Settings() {}

    /** A command that doesn't teleport. */
    public record Command(boolean enabled, List<String> aliases, String permission) implements Toggle {}

    /** A command that doesn't teleport, with a form that acts on another player. */
    public record OthersCommand(boolean enabled, List<String> aliases, String permission, String permissionOthers)
        implements Toggle {}

    /** What a teleporting command's settings have besides its switch. */
    public interface Travel extends Toggle {

        /** Seconds before it can be used again, after a teleport that arrived. */
        int cooldown();

        /** What it costs through Vault. */
        double cost();
    }

    /** A command that teleports. */
    public record TeleportCommand(
        boolean enabled, List<String> aliases, String permission,
        @Range(min = 0, max = 86_400) int cooldown,
        @Range(min = 0) double cost) implements Travel {}

    /** {@code /home}: teleports, and staff can use another player's homes. */
    public record HomeCommand(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        @Range(min = 0, max = 86_400) int cooldown,
        @Range(min = 0) double cost) implements Travel {}

    /**
     * A home limit for players with a permission.
     *
     * @param permission blank means {@code group.<name>}, which LuckPerms gives a group's members
     */
    public record HomeLimit(String permission, @Range(min = 0, max = 10_000) int homes) {}

    /** Where {@code /rtp} may send players in one world. */
    public record RtpWorld(
        @Comment("false keeps /rtp out of this world.") boolean enabled,
        @Comment("Needed to /rtp into this world. Blank lets anyone.") String permission,
        @Comment("Measure from the world's spawn point, or from center-x and center-z.") boolean centerOnSpawn,
        int centerX,
        int centerZ,
        @Comment("The nearest and farthest a spot may be from the centre, in blocks. The world border also limits it.")
        @Range(min = 0, max = 30_000_000) int minRadius,
        @Range(min = 1, max = 30_000_000) int maxRadius,
        @Comment("The End only: allow spots on the main island, where the dragon is.") boolean allowMainIsland) {

        public RtpWorld {
            if (minRadius > maxRadius) {
                throw new IllegalArgumentException("min-radius is above max-radius");
            }
        }
    }
}
