package co.xenastudios.xutilities.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xutilities.config.Toggle;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/** What the command classes share. */
final class Specs {

    private Specs() {}

    /** A command with its settings' name, aliases and permission. */
    static CommandSpec.Builder named(String name, Toggle settings) {
        return CommandSpec.named(name).aliases(settings.aliases()).permission(settings.permission());
    }

    /**
     * Changes a player on their own thread, then tells them with {@code self}. When
     * someone else did it, also tells the sender with {@code others}, which gets
     * {@code <player>} too. A change that returns false did nothing, and says nothing.
     */
    static void changeIf(Tasks tasks, Invocation invocation, Player target, Predicate<Player> change,
                       Message self, Message others, TagResolver... placeholders) {
        tasks.entity(target, () -> {
            if (!change.test(target)) {
                return;
            }
            self.send(target, placeholders);
            if (invocation.isOther(target)) {
                others.send(invocation.sender(), withPlayer(target, placeholders));
            }
        });
    }

    /** {@link #changeIf} for a change that always happens. */
    static void change(Tasks tasks, Invocation invocation, Player target, Consumer<Player> change,
                       Message self, Message others, TagResolver... placeholders) {
        changeIf(tasks, invocation, target, player -> {
            change.accept(player);
            return true;
        }, self, others, placeholders);
    }

    /** These placeholders plus {@code <player>}. */
    static TagResolver[] withPlayer(Player player, TagResolver... placeholders) {
        TagResolver[] all = Arrays.copyOf(placeholders, placeholders.length + 1);
        all[placeholders.length] = Placeholders.text("player", player.getName());
        return all;
    }

    /** A number as typed, without a needless ".0". */
    static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
