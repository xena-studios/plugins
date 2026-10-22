package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.Completer;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xutilities.config.OthersToggle;
import co.xenastudios.xutilities.config.Settings;
import co.xenastudios.xutilities.config.XConfig;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Commands that change a player: /fly, /speed, /setrotation, /ping, /heal, /feed, /repair, /clear, /enderchest. */
final class PlayerCommands {

    /** Main inventory slots: the hotbar and the 27 above it. */
    private static final int MAIN_SLOTS = 36;

    private PlayerCommands() {}

    static void register(Setup setup, Tasks tasks, XConfig config) {
        XConfig.Commands commands = config.commands();

        Settings.Fly fly = commands.fly();
        if (fly.enabled()) {
            declareOthers(setup, fly);
            setup.command(Specs.named("fly", fly)
                .description("Turn flight on or off.")
                .usage("/fly [player]")
                .completes(playerAt(0, fly))
                .executes(invocation -> {
                    Player target = invocation.target(0, fly.permissionOthers());
                    tasks.entity(target, () -> {
                        boolean on = !target.getAllowFlight();
                        target.setAllowFlight(on);
                        if (!on) {
                            target.setFlying(false);
                        }
                        var state = Placeholders.text("state", on ? config.messages().state().enabled() : config.messages().state().disabled());
                        (on ? fly.messageOnSelf() : fly.messageOffSelf()).send(target, state);
                        if (invocation.isOther(target)) {
                            (on ? fly.messageOnOthers() : fly.messageOffOthers()).send(invocation.sender(), Specs.withPlayer(target, state));
                        }
                    });
                }));
        }

        Settings.Speed speed = commands.speed();
        if (speed.enabled()) {
            declareOthers(setup, speed);
            setup.command(Specs.named("speed", speed)
                .description("Set your fly or walk speed.")
                .usage("/speed <" + speed.min() + "-" + speed.max() + "> [player]")
                .completes((sender, args) -> switch (args.length) {
                    case 1 -> Completions.matching(args[0], List.of("1", "2", "3", "5", "10"));
                    case 2 -> Completions.players(sender, args[1], speed.permissionOthers());
                    default -> List.of();
                })
                .executes(invocation -> {
                    double value = parseSpeed(invocation, speed);
                    Player target = invocation.target(1, speed.permissionOthers());
                    tasks.entity(target, () -> {
                        // 1 to 10 maps to Minecraft's 0.1 to 1.0.
                        float minecraft = (float) (value / 10);
                        boolean flying = target.isFlying();
                        if (flying) {
                            target.setFlySpeed(minecraft);
                        } else {
                            target.setWalkSpeed(minecraft);
                        }
                        TagResolver[] placeholders = {
                            Placeholders.text("speed", Specs.number(value)),
                            Placeholders.text("type", flying ? speed.typeFlying() : speed.typeWalking())};
                        speed.messageSelf().send(target, placeholders);
                        if (invocation.isOther(target)) {
                            speed.messageOthers().send(invocation.sender(), Specs.withPlayer(target, placeholders));
                        }
                    });
                }));
        }

        Settings.PlayerCommand rotation = commands.setrotation();
        if (rotation.enabled()) {
            declareOthers(setup, rotation);
            setup.command(Specs.named("setrotation", rotation)
                .description("Set which way a player faces.")
                .usage("/setrotation <yaw> <pitch> [player]")
                .completes((sender, args) -> args.length == 3 ? Completions.players(sender, args[2], rotation.permissionOthers()) : List.of())
                .executes(invocation -> {
                    float yaw = (float) invocation.decimal(0, -360, 360);
                    float pitch = (float) invocation.decimal(1, -90, 90);
                    Player target = invocation.target(2, rotation.permissionOthers());
                    Specs.change(tasks, invocation, target, player -> player.setRotation(yaw, pitch),
                        rotation.messageSelf(), rotation.messageOthers(),
                        Placeholders.text("yaw", Specs.number(yaw)), Placeholders.text("pitch", Specs.number(pitch)));
                }));
        }

        Settings.PlayerCommand ping = commands.ping();
        if (ping.enabled()) {
            declareOthers(setup, ping);
            setup.command(Specs.named("ping", ping)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Show network latency.")
                .usage("/ping [player]")
                .completes(playerAt(0, ping))
                .executes(invocation -> {
                    Player target = invocation.target(0, ping.permissionOthers());
                    var latency = Placeholders.text("ping", target.getPing());
                    if (invocation.isOther(target)) {
                        ping.messageOthers().send(invocation.sender(), Specs.withPlayer(target, latency));
                    } else {
                        ping.messageSelf().send(target, latency);
                    }
                }));
        }

        Settings.Heal heal = commands.heal();
        if (heal.enabled()) {
            declareOthers(setup, heal);
            setup.command(Specs.named("heal", heal)
                .description("Restore health.")
                .usage("/heal [player]")
                .completes(playerAt(0, heal))
                .executes(invocation -> Specs.changeIf(tasks, invocation, invocation.target(0, heal.permissionOthers()),
                    player -> heal(player, heal), heal.messageSelf(), heal.messageOthers())));
        }

        Settings.PlayerCommand feed = commands.feed();
        if (feed.enabled()) {
            declareOthers(setup, feed);
            setup.command(Specs.named("feed", feed)
                .description("Restore hunger.")
                .usage("/feed [player]")
                .completes(playerAt(0, feed))
                .executes(invocation -> Specs.change(tasks, invocation, invocation.target(0, feed.permissionOthers()),
                    PlayerCommands::feed, feed.messageSelf(), feed.messageOthers())));
        }

        Settings.Repair repair = commands.repair();
        if (repair.enabled()) {
            declareOthers(setup, repair);
            setup.command(Specs.named("repair", repair)
                .description("Repair the held item, or with all, the whole inventory.")
                .usage("/repair [all] [player]")
                .completes((sender, args) -> switch (args.length) {
                    case 1 -> {
                        List<String> options = new ArrayList<>(Completions.matching(args[0], List.of("all")));
                        options.addAll(Completions.players(sender, args[0], repair.permissionOthers()));
                        yield options;
                    }
                    case 2 -> "all".equalsIgnoreCase(args[0]) ? Completions.players(sender, args[1], repair.permissionOthers()) : List.of();
                    default -> List.of();
                })
                .executes(invocation -> {
                    boolean all = "all".equalsIgnoreCase(invocation.arg(0));
                    Player target = invocation.target(all ? 1 : 0, repair.permissionOthers());
                    tasks.entity(target, () -> {
                        if (repair(target.getInventory(), all) == 0) {
                            repair.messageNothing().send(invocation.sender());
                            return;
                        }
                        repair.messageSelf().send(target);
                        if (invocation.isOther(target)) {
                            repair.messageOthers().send(invocation.sender(), Specs.withPlayer(target));
                        }
                    });
                }));
        }

        Settings.Clear clear = commands.clear();
        if (clear.enabled()) {
            declareOthers(setup, clear);
            setup.command(Specs.named("clear", clear)
                .description("Empty an inventory.")
                .usage("/clear [player]")
                .completes(playerAt(0, clear))
                .executes(invocation -> Specs.change(tasks, invocation, invocation.target(0, clear.permissionOthers()), player -> {
                    PlayerInventory inventory = player.getInventory();
                    if (clear.includeArmor()) {
                        inventory.clear();
                    } else {
                        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                            inventory.setItem(slot, null);
                        }
                    }
                }, clear.messageSelf(), clear.messageOthers())));
        }

        Settings.EnderChest enderChest = commands.enderchest();
        if (enderChest.enabled()) {
            declareOthers(setup, enderChest);
            setup.command(Specs.named("enderchest", enderChest)
                .description("Open your Ender Chest, or another player's.")
                .usage("/enderchest [player]")
                .completes(playerAt(0, enderChest))
                .executes(invocation -> {
                    Player viewer = invocation.player();
                    Player owner = invocation.target(0, enderChest.permissionOthers());
                    tasks.entity(viewer, () -> {
                        // On Folia, an inventory may only be touched from its owner's region,
                        // so another player's chest opens only when they're in this one.
                        if (invocation.isOther(owner) && !Bukkit.isOwnedByCurrentRegion(owner)) {
                            enderChest.messageTooFar().send(viewer, Specs.withPlayer(owner));
                            return;
                        }
                        viewer.openInventory(owner.getEnderChest());
                        enderChest.sound().play(viewer);
                        if (invocation.isOther(owner)) {
                            enderChest.messageOthers().send(viewer, Specs.withPlayer(owner));
                        }
                    });
                }));
        }
    }

    private static void declareOthers(Setup setup, OthersToggle settings) {
        setup.permission(settings.permissionOthers(), PermissionDefault.OP);
    }

    private static Completer playerAt(int index, OthersToggle settings) {
        return (sender, args) -> args.length == index + 1
            ? Completions.players(sender, args[index], settings.permissionOthers()) : List.of();
    }

    private static double parseSpeed(Invocation invocation, Settings.Speed speed) {
        String raw = invocation.arg(0);
        if (raw == null) {
            throw invocation.usage();
        }
        try {
            double value = Double.parseDouble(raw);
            if (value >= speed.min() && value <= speed.max()) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the invalid-speed message.
        }
        throw new CommandFailure(speed.messageInvalid(),
            Placeholders.text("min", speed.min()), Placeholders.text("max", speed.max()));
    }

    /** Heals a player; false if they're dead, so there was nothing to heal. */
    static boolean heal(Player player, Settings.Heal settings) {
        if (player.isDead()) {
            return false;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
        if (settings.restoreHunger()) {
            feed(player);
        }
        if (settings.extinguishFire()) {
            player.setFireTicks(0);
        }
        if (settings.clearNegativeEffects()) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                    player.removePotionEffect(effect.getType());
                }
            }
        }
        return true;
    }

    static void feed(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setExhaustion(0);
    }

    /** Repairs the held item, or every item; returns how many it repaired. */
    static int repair(PlayerInventory inventory, boolean all) {
        int repaired = 0;
        if (!all) {
            ItemStack held = inventory.getItemInMainHand();
            if (repair(held)) {
                inventory.setItemInMainHand(held);
                repaired++;
            }
            return repaired;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (repair(item)) {
                inventory.setItem(slot, item);
                repaired++;
            }
        }
        return repaired;
    }

    private static boolean repair(ItemStack item) {
        if (item == null || item.isEmpty() || !(item.getItemMeta() instanceof Damageable meta) || !meta.hasDamage()) {
            return false;
        }
        meta.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }
}
