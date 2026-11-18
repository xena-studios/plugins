package co.xenastudios.xvote.reward;

import co.xenastudios.core.hook.VaultEconomy;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xvote.api.event.VoteRewardEvent;
import co.xenastudios.xvote.vote.Facts;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Gives rewards. Runs on the player's own thread (the main thread on Paper), so items,
 * messages, sounds and money reach them there; console commands go to the global thread,
 * where commands run. Items that don't fit are handed to {@code hold} to wait for
 * {@code /vote claim}, never dropped.
 */
public final class Payout {

    /** What a reward's text can mention. */
    public record Context(String site, int total, int streak) {}

    private final Supplier<Rewards> rewards;
    private final RewardTypes types;
    private final VaultEconomy economy;
    private final Tasks tasks;
    private final Logger logger;
    private final Hold hold;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Where items that didn't fit go. */
    @FunctionalInterface
    public interface Hold {
        void hold(Player player, List<ItemStack> items);
    }

    public Payout(Supplier<Rewards> rewards, RewardTypes types, VaultEconomy economy, Tasks tasks, Logger logger, Hold hold) {
        this.rewards = rewards;
        this.types = types;
        this.economy = economy;
        this.tasks = tasks;
        this.logger = logger;
        this.hold = hold;
    }

    /** Warnings about missing economies and reward types are given again after a reload. */
    public void reloaded() {
        warned.clear();
    }

    /** Pays a counted vote's rewards. On the player's thread. */
    public void vote(Player player, Facts facts) {
        Rewards current = rewards.get();
        List<String> names = new ArrayList<>(current.earned(facts));
        if (VoteRewardEvent.getHandlerList().getRegisteredListeners().length > 0) {
            VoteRewardEvent event = new VoteRewardEvent(player, facts.site(), names);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
            names = event.getRewards();
        }
        give(player, current, names, new Context(current.siteName(facts.site()), facts.number(), facts.streak()));
    }

    /** Gives named rewards, as for the vote party. On the player's thread. */
    public void give(Player player, Rewards current, List<String> names, Context context) {
        List<ItemStack> leftover = new ArrayList<>();
        for (String name : names) {
            Rewards.Reward reward = current.reward(name);
            if (reward == null) {
                continue;
            }
            try {
                give(player, reward, context, leftover);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Couldn't give " + player.getName() + " the reward " + name, e);
            }
        }
        if (!leftover.isEmpty()) {
            hold.hold(player, leftover);
        }
    }

    private void give(Player player, Rewards.Reward reward, Context context, List<ItemStack> leftover) {
        if (reward.chance() < 100 && ThreadLocalRandom.current().nextDouble(100) >= reward.chance()) {
            return;
        }
        TagResolver placeholders = placeholders(player, context);
        if (reward.money() > 0) {
            if (!economy.ready()) {
                once("money", "Reward " + reward.name() + " gives money, but there's no economy: install Vault and an"
                    + " economy plugin. Money rewards are skipped until then.");
            } else if (!economy.give(player, reward.money())) {
                once("money-refused", "The economy refused a money reward for " + player.getName() + ".");
            }
        }
        for (ItemStack item : reward.items()) {
            for (ItemStack stack : split(item)) {
                leftover.addAll(player.getInventory().addItem(stack).values());
            }
        }
        if (!reward.commands().isEmpty()) {
            List<String> commands = new ArrayList<>(reward.commands().size());
            for (String command : reward.commands()) {
                commands.add(fill(command, player, context));
            }
            tasks.global(() -> {
                for (String command : commands) {
                    String line = command.startsWith("/") ? command.substring(1) : command;
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "Reward " + reward.name() + "'s command failed: " + line, e);
                    }
                }
            });
        }
        reward.message().send(player, placeholders);
        if (!reward.broadcast().isEmpty()) {
            broadcast(reward.broadcast().chat(placeholders));
        }
        if (!reward.sound().isNone()) {
            reward.sound().play(player);
        }
        for (String custom : reward.custom()) {
            String[] parts = fill(custom, player, context).strip().split("\\s+", 2);
            RewardTypes.Registered type = types.get(parts[0]);
            if (type == null || !type.plugin().isEnabled()) {
                once("type:" + parts[0], "Reward " + reward.name() + " uses the reward type \"" + parts[0]
                    + "\", which no enabled plugin has added; it's skipped.");
                continue;
            }
            type.type().give(player, parts.length > 1 ? parts[1] : "");
        }
    }

    // An item written with more than a stack goes in as full stacks.
    private static List<ItemStack> split(ItemStack item) {
        int max = Math.max(1, item.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = item.getAmount(); left > 0; left -= max) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(max, left));
            stacks.add(stack);
        }
        return stacks;
    }

    public static TagResolver placeholders(Player player, Context context) {
        return TagResolver.resolver(
            Placeholders.text("player", player.getName()),
            Placeholders.text("uuid", player.getUniqueId()),
            Placeholders.text("site", context.site()),
            Placeholders.text("total", context.total()),
            Placeholders.text("streak", context.streak()));
    }

    /** A command or custom reward with its placeholders filled in, as plain text. */
    static String fill(String text, Player player, Context context) {
        String name = player.getName();
        UUID id = player.getUniqueId();
        return text.replace("<player>", name).replace("%player%", name)
            .replace("<uuid>", id.toString()).replace("<site>", context.site())
            .replace("<total>", Integer.toString(context.total())).replace("<streak>", Integer.toString(context.streak()));
    }

    private void once(String key, String message) {
        if (warned.add(key)) {
            logger.warning(message);
        }
    }

    /** Sends a message to everyone online and the console. Any thread: sending a message is safe from any. */
    public static void broadcast(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** Sends a message on the player's thread. For replies from the database thread. */
    public static void tell(Tasks tasks, Player player, Message message, TagResolver... placeholders) {
        tasks.entity(player, () -> message.send(player, placeholders));
    }

    /** Runs {@code work} for each player online, each on their own thread. */
    public static void forEachOnline(Tasks tasks, Consumer<Player> work) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            tasks.entity(player, () -> work.accept(player));
        }
    }
}
