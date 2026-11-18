package co.xenastudios.xplots.menu;

import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.config.XPConfig;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Places;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.rent.Info;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The plot menus. Each is drawn from the plot as it is when opened, and each button
 * checks again when clicked, since someone else may have changed the plot meanwhile.
 */
public final class Menus {

    private final XPlots plugin;

    public Menus(XPlots plugin) {
        this.plugin = plugin;
    }

    private XPConfig.Menus config() {
        return plugin.config().menus();
    }

    /** A free plot: its details, and rent or cancel. */
    public void rent(Player player, Plot plot) {
        XPConfig.RentMenu rent = config().rent();
        TagResolver[] info = plugin.rentals().info(plot).placeholders();
        Menu menu = new Menu(3, rent.title(), info);
        menu.set(11, Menu.item(rent.confirm(), info), p -> {
            p.closeInventory();
            plugin.rentals().rent(p, plot);
        });
        menu.set(13, Menu.item(rent.info(), info), null);
        menu.set(15, Menu.item(rent.cancel(), info), Player::closeInventory);
        open(player, menu);
    }

    /** /plots: the plot menu with one plot, a list with several. */
    public void mine(Player player) {
        List<Plot> owned = plugin.plots().owned(player.getUniqueId());
        if (owned.isEmpty()) {
            plugin.config().messages().noPlots().send(player);
        } else if (owned.size() == 1) {
            plot(player, owned.get(0));
        } else {
            list(player, owned);
        }
    }

    private void list(Player player, List<Plot> owned) {
        XPConfig.ListMenu list = config().list();
        int shown = Math.min(owned.size(), 54);
        Menu menu = new Menu((shown + 8) / 9, list.title());
        for (int i = 0; i < shown; i++) {
            Plot plot = owned.get(i);
            menu.set(i, Menu.item(list.plot(), plugin.rentals().info(plot).placeholders()), p -> plot(p, plot));
        }
        open(player, menu);
    }

    /** The owner's menu: pay rent, members, slots, teleport, abandon. */
    public void plot(Player player, Plot plot) {
        if (!plugin.rentals().owns(player, plot)) {
            plugin.config().messages().notYours().send(player);
            player.closeInventory();
            return;
        }
        XPConfig.PlotMenu menuConfig = config().plot();
        XPConfig.PlotSettings settings = plugin.rentals().settings(plot);
        Info info = plugin.rentals().info(plot);
        TagResolver[] placeholders = info.placeholders();
        Menu menu = new Menu(3, menuConfig.title(), placeholders);
        menu.set(4, Menu.item(menuConfig.info(), placeholders), null);
        for (int periods = 1; periods <= 3; periods++) {
            int n = periods;
            TagResolver[] pay = info.placeholders(
                Placeholders.text("periods", n),
                Placeholders.text("price", plugin.wallet().format(settings.price() * n)),
                Placeholders.text("time", Info.time(settings.period().toMillis() * n)));
            boolean fits = plugin.rentals().canPay(plot, n);
            menu.set(9 + n, Menu.item(fits ? menuConfig.pay() : menuConfig.payFull(), pay), p -> {
                plugin.rentals().payRent(p, plot, n);
                plot(p, plot);
            });
        }
        menu.set(14, Menu.item(menuConfig.members(), placeholders), p -> members(p, plot));
        boolean slotsFull = plot.lease().slots() >= settings.maxSlots();
        TagResolver[] slot = info.placeholders(Placeholders.text("price", info.slotPrice()),
            Placeholders.text("max-slots", settings.maxSlots()));
        menu.set(15, Menu.item(slotsFull ? menuConfig.slotsFull() : menuConfig.buySlot(), slot), p -> {
            plugin.rentals().buySlot(p, plot);
            plot(p, plot);
        });
        menu.set(16, Menu.item(menuConfig.home(), placeholders), p -> {
            p.closeInventory();
            home(p, plot);
        });
        menu.set(22, Menu.item(menuConfig.abandon(), placeholders), p -> abandon(p, plot));
        open(player, menu);
    }

    private void members(Player player, Plot plot) {
        if (!plugin.rentals().owns(player, plot)) {
            player.closeInventory();
            return;
        }
        XPConfig.MembersMenu config = config().members();
        TagResolver[] info = plugin.rentals().info(plot).placeholders();
        Menu menu = new Menu(4, config.title(), info);
        int slot = 0;
        for (Map.Entry<UUID, String> member : plot.lease().members().entrySet()) {
            if (slot >= 27) {
                break;
            }
            UUID id = member.getKey();
            ItemStack head = head(Menu.item(config.member(), Placeholders.text("player", member.getValue())), id, member.getValue());
            menu.set(slot++, head, p -> {
                if (plugin.rentals().owns(p, plot)) {
                    plugin.rentals().removeMember(p, plot, id);
                }
                members(p, plot);
            });
        }
        menu.set(30, Menu.item(config.add(), info), p -> picker(p, plot, 0));
        menu.set(32, Menu.item(config.back(), info), p -> plot(p, plot));
        open(player, menu);
    }

    private void picker(Player player, Plot plot, int page) {
        if (!plugin.rentals().owns(player, plot)) {
            player.closeInventory();
            return;
        }
        XPConfig.PickerMenu config = config().picker();
        Lease lease = plot.lease();
        if (lease.members().size() >= lease.slots()) {
            plugin.config().messages().slotsFull().send(player, Placeholders.text("plot", plot.id()));
            members(player, plot);
            return;
        }
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != player && player.canSee(online) && !lease.trusts(online.getUniqueId())) {
                candidates.add(online);
            }
        }
        candidates.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int pages = Math.max(1, (candidates.size() + 44) / 45);
        int current = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, config.title());
        for (int i = 0; i < 45 && current * 45 + i < candidates.size(); i++) {
            Player candidate = candidates.get(current * 45 + i);
            UUID id = candidate.getUniqueId();
            ItemStack head = head(Menu.item(config.player(), Placeholders.text("player", candidate.getName())), id, candidate.getName());
            menu.set(i, head, p -> {
                if (plugin.rentals().owns(p, plot)) {
                    plugin.rentals().addMember(p, plot, Bukkit.getOfflinePlayer(id), false);
                }
                members(p, plot);
            });
        }
        if (current > 0) {
            menu.set(45, Menu.item(config.previous()), p -> picker(p, plot, current - 1));
        }
        menu.set(49, Menu.item(config.back()), p -> members(p, plot));
        if (current + 1 < pages) {
            menu.set(53, Menu.item(config.next()), p -> picker(p, plot, current + 1));
        }
        open(player, menu);
    }

    private void abandon(Player player, Plot plot) {
        XPConfig.AbandonMenu config = config().abandon();
        TagResolver[] info = plugin.rentals().info(plot).placeholders();
        Menu menu = new Menu(3, config.title(), info);
        menu.set(11, Menu.item(config.confirm(), info), p -> {
            p.closeInventory();
            plugin.rentals().abandon(p, plot);
        });
        menu.set(15, Menu.item(config.cancel(), info), p -> plot(p, plot));
        open(player, menu);
    }

    /** Staff's menu for any plot. */
    public void admin(Player player, Plot plot) {
        XPConfig.AdminMenu config = config().admin();
        TagResolver[] info = plugin.rentals().info(plot).placeholders();
        Menu menu = new Menu(3, config.title(), info);
        menu.set(4, Menu.item(config.info(), info), null);
        menu.set(11, Menu.item(config.home(), info), p -> {
            p.closeInventory();
            World world = Bukkit.getWorld(plot.field().world());
            if (world != null) {
                p.teleportAsync(Places.front(world, plot));
            }
        });
        menu.set(13, Menu.item(config.evict(), info), p -> {
            p.closeInventory();
            if (!plot.isFree()) {
                plugin.rentals().end(plot);
            }
            p.sendMessage(plugin.staffMessage("Evicted <highlight>" + plot.id() + "</highlight>."));
        });
        menu.set(15, Menu.item(config.reset(), info), p -> {
            p.closeInventory();
            plugin.resets().reset(plot, () -> plugin.signs().update(plot));
            p.sendMessage(plugin.staffMessage("Putting <highlight>" + plot.id() + "</highlight> back as it was made."));
        });
        open(player, menu);
    }

    /** Teleports the player to the plot's front, after the warmup. */
    public void home(Player player, Plot plot) {
        World world = Bukkit.getWorld(plot.field().world());
        if (world != null) {
            plugin.teleports().teleport(player, Places.front(world, plot));
        }
    }

    private void open(Player player, Menu menu) {
        menu.fill(config().filler());
        player.openInventory(menu.getInventory());
    }

    private static ItemStack head(ItemStack item, UUID id, String name) {
        if (item.getItemMeta() instanceof SkullMeta meta) {
            Player online = Bukkit.getPlayer(id);
            meta.setPlayerProfile(online != null ? online.getPlayerProfile() : Bukkit.createProfile(id, name));
            item.setItemMeta(meta);
        }
        return item;
    }
}
