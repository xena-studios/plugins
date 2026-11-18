package co.xenastudios.xplots.rent;

import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.config.XPConfig;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Plot;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Everything that changes who has a plot: renting, paying, member slots, members,
 * abandoning, and rent running out. Each change is made in memory, saved in the
 * background, and shown on the sign at once. Main thread.
 */
public final class Rentals {

    private static final String LIMIT_PREFIX = "xplots.limit.";

    private final XPlots plugin;
    private String lastProblem;

    public Rentals(XPlots plugin) {
        this.plugin = plugin;
    }

    private XPConfig config() {
        return plugin.config();
    }

    public XPConfig.PlotSettings settings(Plot plot) {
        return plot.field().settings(config().plots());
    }

    public Info info(Plot plot) {
        return Info.of(plot, settings(plot), plugin.wallet(), System.currentTimeMillis(), config().messages().noMembers().template());
    }

    /** How many plots the player may rent at once. */
    public int limit(Player player) {
        int limit = config().maxPlots();
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            String name = permission.getPermission();
            if (permission.getValue() && name.startsWith(LIMIT_PREFIX)) {
                try {
                    limit = Math.max(limit, Integer.parseInt(name.substring(LIMIT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // Not a number: not a limit.
                }
            }
        }
        return limit;
    }

    /** Whether the wallet can take this amount now; if not, tells the player and the console why. */
    private boolean walletReady(Player player, double amount) {
        if (amount <= 0 || plugin.wallet().ready()) {
            return true;
        }
        String problem = plugin.wallet().problem();
        if (problem != null && !problem.equals(lastProblem)) {
            plugin.getLogger().warning(problem);
        }
        lastProblem = problem;
        config().messages().unavailable().send(player);
        return false;
    }

    private boolean pay(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (!plugin.wallet().take(player, amount)) {
            config().messages().cantAfford().send(player, Placeholders.text("price", plugin.wallet().format(amount)));
            return false;
        }
        return true;
    }

    /** Rents a free plot to the player for one period. */
    public boolean rent(Player player, Plot plot) {
        XPConfig.Messages messages = config().messages();
        if (!plot.isFree() || plugin.resets().isBusy(plot)) {
            (plot.isFree() ? messages.unavailable() : messages.taken()).send(player);
            return false;
        }
        int limit = limit(player);
        if (plugin.plots().owned(player.getUniqueId()).size() >= limit) {
            messages.limitReached().send(player, Placeholders.text("max", limit + (limit == 1 ? " plot" : " plots")));
            return false;
        }
        XPConfig.PlotSettings settings = settings(plot);
        if (!walletReady(player, settings.price()) || !pay(player, settings.price())) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expires = now + settings.period().toMillis();
        Lease lease = new Lease(player.getUniqueId(), player.getName(), expires, settings.startingSlots(),
            Lease.warningFor(expires - now));
        plugin.plots().lease(plot, lease);
        saved(plot);
        messages.rented().send(player, info(plot).placeholders());
        return true;
    }

    /** How much paying this many periods would add, or 0 if it would go past max-prepaid. */
    public boolean canPay(Plot plot, int periods) {
        XPConfig.PlotSettings settings = settings(plot);
        long now = System.currentTimeMillis();
        long after = Math.max(plot.lease().expires(), now) + periods * settings.period().toMillis();
        // A minute's grace, so paying right at the cap isn't refused by the second it took to click.
        return after - now <= settings.maxPrepaid().toMillis() + 60_000;
    }

    /** Pays rent ahead: the owner, for this many periods. */
    public boolean payRent(Player player, Plot plot, int periods) {
        XPConfig.Messages messages = config().messages();
        if (!owns(player, plot)) {
            messages.notYours().send(player);
            return false;
        }
        XPConfig.PlotSettings settings = settings(plot);
        if (!canPay(plot, periods)) {
            messages.prepaidFull().send(player, Placeholders.text("max", Info.time(settings.maxPrepaid().toMillis())));
            return false;
        }
        double price = settings.price() * periods;
        if (!walletReady(player, price) || !pay(player, price)) {
            return false;
        }
        Lease lease = plot.lease();
        long now = System.currentTimeMillis();
        lease.expires(Math.max(lease.expires(), now) + periods * settings.period().toMillis());
        lease.warned(Lease.warningFor(lease.expires() - now));
        saved(plot);
        messages.paid().send(player, info(plot).placeholders(Placeholders.text("price", plugin.wallet().format(price))));
        return true;
    }

    public boolean buySlot(Player player, Plot plot) {
        XPConfig.Messages messages = config().messages();
        if (!owns(player, plot)) {
            messages.notYours().send(player);
            return false;
        }
        XPConfig.PlotSettings settings = settings(plot);
        if (plot.lease().slots() >= settings.maxSlots()) {
            messages.maxSlots().send(player, Placeholders.text("plot", plot.id()));
            return false;
        }
        if (!walletReady(player, settings.slotPrice()) || !pay(player, settings.slotPrice())) {
            return false;
        }
        plot.lease().slots(plot.lease().slots() + 1);
        saved(plot);
        messages.slotBought().send(player, Placeholders.text("plot", plot.id()), Placeholders.text("slots", plot.lease().slots()));
        return true;
    }

    /**
     * Adds a member. Staff skip the slot check. {@code by} is told how it went; the new
     * member is told if they're online.
     */
    public boolean addMember(org.bukkit.command.CommandSender by, Plot plot, OfflinePlayer target, boolean staff) {
        XPConfig.Messages messages = config().messages();
        Lease lease = plot.lease();
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        if (lease == null) {
            messages.notYours().send(by);
            return false;
        }
        if (lease.owner().equals(target.getUniqueId())) {
            messages.notYourself().send(by);
            return false;
        }
        if (lease.members().containsKey(target.getUniqueId())) {
            messages.alreadyMember().send(by, Placeholders.text("player", name));
            return false;
        }
        if (!staff && lease.members().size() >= lease.slots()) {
            messages.slotsFull().send(by, Placeholders.text("plot", plot.id()));
            return false;
        }
        lease.members().put(target.getUniqueId(), name);
        saved(plot);
        messages.memberAdded().send(by, Placeholders.text("player", name), Placeholders.text("plot", plot.id()));
        Player online = target.getPlayer();
        if (online != null && online != by) {
            messages.addedYou().send(online, Placeholders.text("owner", lease.ownerName()), Placeholders.text("plot", plot.id()));
        }
        return true;
    }

    public boolean removeMember(org.bukkit.command.CommandSender by, Plot plot, UUID member) {
        XPConfig.Messages messages = config().messages();
        Lease lease = plot.lease();
        String name = lease == null ? null : lease.members().get(member);
        if (name == null) {
            String typed = Bukkit.getOfflinePlayer(member).getName();
            messages.notMember().send(by, Placeholders.text("player", typed == null ? member.toString() : typed));
            return false;
        }
        lease.members().remove(member);
        saved(plot);
        messages.memberRemoved().send(by, Placeholders.text("player", name), Placeholders.text("plot", plot.id()));
        Player online = Bukkit.getPlayer(member);
        if (online != null && online != by) {
            messages.removedYou().send(online, Placeholders.text("plot", plot.id()));
        }
        return true;
    }

    /** The owner gives the plot up, getting back the refund the field allows. */
    public void abandon(Player player, Plot plot) {
        XPConfig.Messages messages = config().messages();
        if (!owns(player, plot)) {
            messages.notYours().send(player);
            return;
        }
        XPConfig.PlotSettings settings = settings(plot);
        double unused = plot.lease().remaining(System.currentTimeMillis()) / (double) settings.period().toMillis();
        double refund = Math.floor(unused * settings.price() * settings.abandonRefund() / 100.0 * 100) / 100;
        end(plot);
        messages.abandoned().send(player, Placeholders.text("plot", plot.id()));
        if (refund > 0 && plugin.wallet().ready()) {
            plugin.wallet().give(player, refund);
            messages.refunded().send(player, Placeholders.text("price", plugin.wallet().format(refund)));
        }
    }

    /** Ends the plot's rent now: it's free, its members are gone, and it's put back if the field says so. */
    public void end(Plot plot) {
        if (plot.isFree()) {
            return;
        }
        plugin.plots().lease(plot, null);
        saved(plot);
        if (settings(plot).reset()) {
            plugin.resets().reset(plot, () -> plugin.signs().update(plot));
        }
    }

    /** Rent ran out: ends it and tells the owner, if they're online. */
    public void expire(Plot plot) {
        UUID owner = plot.lease().owner();
        end(plot);
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            config().messages().expired().send(online, Placeholders.text("plot", plot.id()));
        }
    }

    /**
     * Staff give a plot to a player. A free plot is rented to them for one period, at no
     * cost; a rented one is handed over with its time, slots and members.
     */
    public void setOwner(Plot plot, OfflinePlayer owner) {
        String name = owner.getName() == null ? owner.getUniqueId().toString() : owner.getName();
        Lease old = plot.lease();
        Lease lease;
        if (old == null) {
            XPConfig.PlotSettings settings = settings(plot);
            long now = System.currentTimeMillis();
            long expires = now + settings.period().toMillis();
            lease = new Lease(owner.getUniqueId(), name, expires, settings.startingSlots(), Lease.warningFor(expires - now));
        } else {
            lease = new Lease(owner.getUniqueId(), name, old.expires(), old.slots(), old.warned());
            for (Map.Entry<UUID, String> member : old.members().entrySet()) {
                if (!member.getKey().equals(owner.getUniqueId())) {
                    lease.members().put(member.getKey(), member.getValue());
                }
            }
        }
        plugin.plots().lease(plot, lease);
        saved(plot);
    }

    public boolean owns(Player player, Plot plot) {
        return plot.lease() != null && plot.lease().owner().equals(player.getUniqueId());
    }

    /** After any change to a lease: save it, show it, and time the next expiry. */
    public void saved(Plot plot) {
        plugin.store().saveLease(plot);
        plugin.signs().update(plot);
        plugin.expiry().reschedule();
    }
}
