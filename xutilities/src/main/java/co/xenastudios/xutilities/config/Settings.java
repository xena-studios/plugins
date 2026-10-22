package co.xenastudios.xutilities.config;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import java.util.List;

/** The record for each kind of command's settings. */
public final class Settings {

    private Settings() {}

    /** A command that acts on you or, with {@code [player]}, someone else, and says so. */
    public record PlayerCommand(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        Message messageSelf, Message messageOthers) implements OthersToggle {}

    public record Gamemode(boolean enabled, List<String> aliases, String permission, String permissionOthers)
        implements OthersToggle {}

    public record Fly(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        Message messageOnSelf, Message messageOffSelf, Message messageOnOthers, Message messageOffOthers)
        implements OthersToggle {}

    public record Speed(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        @Range(min = 1, max = 10) int min,
        @Range(min = 1, max = 10) int max,
        @Comment("The words filled in for <type>: which speed changed.") String typeFlying,
        String typeWalking,
        Message messageSelf, Message messageOthers, Message messageInvalid) implements OthersToggle {

        public Speed {
            if (min > max) {
                throw new IllegalArgumentException("min is above max");
            }
        }
    }

    public record Top(boolean enabled, List<String> aliases, String permission, Message messageSuccess, Message messageUnsafe)
        implements Toggle {}

    public record Back(boolean enabled, List<String> aliases, String permission, Message messageSuccess, Message messageNone)
        implements Toggle {}

    public record Heal(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        boolean restoreHunger, boolean extinguishFire, boolean clearNegativeEffects,
        Message messageSelf, Message messageOthers) implements OthersToggle {}

    public record Repair(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        Message messageSelf, Message messageOthers, Message messageNothing) implements OthersToggle {}

    public record Clear(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        boolean includeArmor, Message messageSelf, Message messageOthers) implements OthersToggle {}

    public record EnderChest(
        boolean enabled, List<String> aliases, String permission, String permissionOthers,
        @Comment("Played as the chest opens.") SoundEffect sound,
        Message messageOthers,
        @Comment("Folia only: the other player is too far away to share their chest safely.") Message messageTooFar)
        implements OthersToggle {}

    public record Workstation(boolean enabled, List<String> aliases, String permission, Message message, SoundEffect sound)
        implements Toggle {}

    public record Trash(boolean enabled, List<String> aliases, String permission, Message title, Message message, SoundEffect sound)
        implements Toggle {}

    /**
     * A command defined in the config that sends a message.
     *
     * @param permission null when left out, meaning {@code xutilities.command.<name>}
     */
    public record LinkCommand(boolean enabled, List<String> aliases, String permission, Message message) {}
}
