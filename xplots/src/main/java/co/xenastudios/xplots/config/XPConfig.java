package co.xenastudios.xplots.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Codec;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Theme;
import java.time.Duration;
import java.util.List;
import org.bukkit.Material;

/** xPlots' config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XPConfig(
    @Comment({
        "The look xPlots' messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#A9D750>, or <gold><bold>."})
    Theme theme,
    @Comment("What players are told.") Messages messages,
    @Comment({
        "Every field's settings, unless the field changes one with /xplots field set. Times",
        "are written like 3d, 12h or 1d12h."})
    PlotSettings plots,
    @Comment("What rent is paid with.") Currency currency,
    @Comment({
        "How many plots a player may rent at once. A player with xplots.limit.<n> may rent",
        "<n> instead, the highest they have."})
    @Range(min = 0, max = 1000) int maxPlots,
    @Comment({
        "What plot signs say, four lines each. <plot> is the plot's name, <price> and <period>",
        "its rent, <owner> who rents it, <time> how long is left, <slots> and <max-slots>",
        "its member slots, <size> its size."})
    Signs signs,
    @Comment("The plot menus. Each button has a material, a name and lines below the name.") Menus menus,
    @Comment({
        "Seconds to stand still before /plots home and /plots warp teleport. xplots.warmup.bypass",
        "skips it."})
    @Range(min = 0, max = 60) int teleportWarmup,
    @Comment({
        "How long putting a plot back may work each tick, in milliseconds. A tick is 50. A",
        "small plot takes a fraction of one."})
    @Range(min = 1, max = 40) int resetMsPerTick,
    @Comment({
        "Where plots are kept. sqlite needs nothing set up. A change here applies on restart:",
        "plots aren't copied across."})
    StorageSettings storage,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    /** The settings a field has unless it changes them. */
    public record PlotSettings(
        @Comment("What one period of rent costs.") @Range(min = 0) double price,
        @Comment("How long one payment of rent lasts.") Duration period,
        @Comment({
            "The most rent that can be paid ahead. A player can pay again while there's room,",
            "so a plot is never lost to a missed window."})
        Duration maxPrepaid,
        @Comment("Members a new rental can add, besides the owner, who doesn't take a slot.")
        @Range(min = 0, max = 45) int startingSlots,
        @Comment("The most member slots a plot can have, after buying more.")
        @Range(min = 0, max = 45) int maxSlots,
        @Comment("What one more member slot costs.") @Range(min = 0) double slotPrice,
        @Comment({
            "Put a plot back as it was when it was made, once its rent runs out or it's",
            "abandoned. Anything built in it is lost, items in chests included."})
        boolean reset,
        @Comment("How much of the unused rent abandoning a plot gives back, in percent.")
        @Range(min = 0, max = 100) int abandonRefund) {}

    public record Currency(
        @Comment({
            "vault uses the server's economy (Vault and a plugin that provides one, such as",
            "EssentialsX). item takes the item below from the player's inventory."})
        Type type,
        @Comment("For type: item.") ItemCurrency item) {

        public enum Type { VAULT, ITEM }
    }

    public record ItemCurrency(
        Material material,
        @Comment({
            "The item's name as players see it, without colours, such as \"Coin\". Blank takes",
            "any item of the material."})
        String name,
        @Comment("The item's custom model data, for a resource pack's coin. 0 takes any.") int customModelData,
        @Comment("How amounts are written: \"1 Coin\", \"5 Coins\".") String singular,
        String plural) {}

    public record Signs(
        @Comment("A plot nobody rents.") List<String> free,
        List<String> rented,
        @Comment("A rented plot whose rent runs out within expiring-below.") List<String> expiring,
        Duration expiringBelow,
        @Comment("The sign placed for each plot: any standing sign, such as dark_oak_sign.") Material material) {}

    public record Menus(
        @Comment("Fills the empty slots. air leaves them empty.") Material filler,
        @Comment("Clicking a free plot's sign.") RentMenu rent,
        @Comment("Clicking your own plot's sign, or /plots.") PlotMenu plot,
        @Comment("/plots with more than one plot.") ListMenu list,
        MembersMenu members,
        @Comment("Adding a member: heads of the players online.") PickerMenu picker,
        AbandonMenu abandon,
        @Comment("Staff clicking a plot with the wand.") AdminMenu admin) {}

    public record Button(Material material, String name, List<String> lore) {}

    public record RentMenu(String title, Button info, Button confirm, Button cancel) {}

    public record PlotMenu(
        String title,
        Button info,
        @Comment("<periods> is 1, 2 or 3; <price> what that costs; <time> how long it adds.") Button pay,
        @Comment("Shown instead when paying would go past max-prepaid.") Button payFull,
        Button members,
        Button buySlot,
        @Comment("Shown instead when the plot has max-slots.") Button slotsFull,
        Button home,
        Button abandon) {}

    public record ListMenu(String title, Button plot) {}

    public record MembersMenu(String title, @Comment("<player> is the member.") Button member, Button add, Button back) {}

    public record PickerMenu(String title, Button player, Button previous, Button next, Button back) {}

    public record AbandonMenu(String title, Button confirm, Button cancel) {}

    public record AdminMenu(String title, Button info, Button home, Button evict, Button reset) {}

    public record Messages(
        ErrorMessages error,
        @Comment("<plot>, <price>, <time>: what was rented, for what, and for how long.") Message rented,
        @Comment("<price> is what it costs.") Message cantAfford,
        @Comment("<max> is the player's limit.") Message limitReached,
        Message taken,
        @Comment("Rent paid. <price>, <plot>, <time> left now.") Message paid,
        @Comment("<max> is max-prepaid.") Message prepaidFull,
        Message abandoned,
        @Comment("<price> is what came back.") Message refunded,
        @Comment("<slots> is how many it has now.") Message slotBought,
        Message slotsFull,
        Message maxSlots,
        @Comment("<player> was added to <plot>.") Message memberAdded,
        Message memberRemoved,
        @Comment("To the player added. <owner>, <plot>.") Message addedYou,
        Message removedYou,
        Message alreadyMember,
        Message notMember,
        Message notYourself,
        @Comment("<player> has never joined this server.") Message neverJoined,
        Message noPlots,
        @Comment("Several plots and none chosen. <usage> is how to name one.") Message whichPlot,
        Message notYours,
        @Comment({
            "Clicking someone else's sign. <plot>, <owner>, <time>, and <members>: their names,",
            "or none."})
        Message rentedBy,
        Message noMembers,
        @Comment("<plot>'s rent runs out in <time>.") Message expiresSoon,
        @Comment("To the owner, if online.") Message expired,
        @Comment("<seconds> to wait.") Message teleporting,
        Message teleportCancelled,
        @Comment("/plots warp with no warp set. <field> is the field.") Message noWarp,
        @Comment("On the action bar, when a player can't build or open something there.") Message protectedHere,
        @Comment("Something xPlots needs isn't working, such as the economy. The console says what.") Message unavailable) {}

    public static final Codec<Duration> DURATION = new Codec<>() {
        @Override
        public Duration decode(Object raw) {
            try {
                Duration duration = Durations.parse(raw.toString());
                if (duration.isZero()) {
                    throw new IllegalArgumentException("must be longer than nothing");
                }
                return duration;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage().startsWith("must") ? e.getMessage()
                    : "isn't a length of time, such as 3d or 12h");
            }
        }

        @Override
        public Object encode(Duration value) {
            return Durations.format(value).replace(" ", "");
        }
    };

    public static final Codec<Material> MATERIAL = new Codec<>() {
        @Override
        public Material decode(Object raw) {
            Material material = Material.matchMaterial(raw.toString());
            if (material == null || material.isLegacy()) {
                throw new IllegalArgumentException("isn't a material, such as oak_sign");
            }
            return material;
        }

        @Override
        public Object encode(Material value) {
            return value.getKey().getKey();
        }
    };

    private static Button button(Material material, String name, String... lore) {
        return new Button(material, name, List.of(lore));
    }

    public static final XPConfig DEFAULTS = new XPConfig(
        new Theme(
            "<#A9D750>»</#A9D750> ",
            co.xenastudios.core.text.TextStyle.parse("<gray>"),
            co.xenastudios.core.text.TextStyle.parse("<#A9D750>"),
            co.xenastudios.core.text.TextStyle.parse("<#5EE08A>"),
            co.xenastudios.core.text.TextStyle.parse("<#FF6B6B>")),
        new Messages(
            ErrorMessages.DEFAULTS,
            Message.of("<prefix><success>You rented</success> <highlight><plot></highlight> for <price>. It's yours for <time>."),
            Message.of("<prefix><error>You need <highlight><price></highlight> for that."),
            Message.of("<prefix><error>You can't rent more than <highlight><max></highlight> at once."),
            Message.of("<prefix><error>Someone rented that plot first."),
            Message.of("<prefix><success>Paid</success> <highlight><price></highlight>. <highlight><plot></highlight> is yours for <time>."),
            Message.of("<prefix><error>Rent can be paid at most <highlight><max></highlight> ahead."),
            Message.of("<prefix><highlight><plot></highlight> is no longer yours."),
            Message.of("<prefix>You got back <highlight><price></highlight>."),
            Message.of("<prefix><success>Bought a member slot</success> for <highlight><plot></highlight>. It has <slots> now."),
            Message.of("<prefix><error><highlight><plot></highlight> has no free member slots. Buy one in its menu: <highlight>/plots</highlight>."),
            Message.of("<prefix><error><highlight><plot></highlight> has as many member slots as it can."),
            Message.of("<prefix><success>Added</success> <highlight><player></highlight> to <plot>."),
            Message.of("<prefix>Removed <highlight><player></highlight> from <plot>."),
            Message.of("<prefix><highlight><owner></highlight> added you to <highlight><plot></highlight>. You can build there now."),
            Message.of("<prefix>You were removed from <highlight><plot></highlight>."),
            Message.of("<prefix><error><highlight><player></highlight> is already a member."),
            Message.of("<prefix><error><highlight><player></highlight> isn't a member."),
            Message.of("<prefix><error>You own it, so you don't need a slot."),
            Message.of("<prefix><error><highlight><player></highlight> has never played here."),
            Message.of("<prefix><error>You don't rent a plot. Click a free plot's sign to rent one."),
            Message.of("<prefix><error>You have more than one plot: stand in the one you mean, or use <highlight>/plots</highlight>."),
            Message.of("<prefix><error>That plot isn't yours."),
            Message.of("<prefix><highlight><plot></highlight> is rented by <highlight><owner></highlight> for <time>. Members: <members>."),
            Message.of("none"),
            Message.of("<prefix><highlight><plot></highlight>'s rent runs out in <highlight><time></highlight>. Pay more with <highlight>/plots</highlight>, or it's reset and anything in it is lost, chests included."),
            Message.of("<prefix><highlight><plot></highlight>'s rent ran out, so it's free again."),
            Message.of("<prefix>Teleporting in <highlight><seconds>s</highlight>. Don't move."),
            Message.of("<prefix><error>Teleport cancelled: you moved."),
            Message.of("<prefix><error><highlight><field></highlight> has no warp."),
            Message.of("<error>You can't do that in this plot."),
            Message.of("<prefix><error>That isn't available right now.")),
        new PlotSettings(100, Duration.ofDays(3), Duration.ofDays(7), 1, 3, 50, true, 0),
        new Currency(Currency.Type.VAULT, new ItemCurrency(Material.GOLD_NUGGET, "Coin", 0, "Coin", "Coins")),
        1,
        new Signs(
            List.of("<dark_green><bold>FOR RENT", "<plot>", "<price>", "<period>"),
            List.of("<dark_red><bold>RENTED", "<owner>", "<time> left", "<slots>/<max-slots> members"),
            List.of("<gold><bold>RENTED", "<owner>", "<red><time> left", "<slots>/<max-slots> members"),
            Duration.ofDays(1),
            Material.OAK_SIGN),
        new Menus(
            Material.GRAY_STAINED_GLASS_PANE,
            new RentMenu("Rent <plot>?",
                button(Material.OAK_SIGN, "<highlight><plot>", "<gray>Size: <white><size>", "<gray>Rent: <white><price></white> per <period>",
                    "<gray>Member slots: <white><max-slots>", "", "<gray>Anything you build is reset", "<gray>when the rent runs out."),
                button(Material.LIME_CONCRETE, "<success>Rent for <price>", "<gray>Lasts <period>. Pay more any time", "<gray>from /plots."),
                button(Material.RED_CONCRETE, "<error>Cancel")),
            new PlotMenu("<plot>",
                button(Material.OAK_SIGN, "<highlight><plot>", "<gray>Time left: <white><time>", "<gray>Members: <white><slots>/<max-slots>",
                    "<gray>Size: <white><size>"),
                button(Material.CLOCK, "<success>Pay <periods>× rent", "<gray>Costs <white><price>", "<gray>Adds <white><time>"),
                button(Material.GRAY_DYE, "<gray>Pay <periods>× rent", "<gray>That's more than can be", "<gray>paid ahead."),
                button(Material.PLAYER_HEAD, "<highlight>Members", "<gray><slots> of <max-slots> slots used", "<gray>Click to add or remove."),
                button(Material.EMERALD, "<success>Buy a member slot", "<gray>Costs <white><price>", "<gray>Up to <max-slots>."),
                button(Material.GRAY_DYE, "<gray>Member slots full", "<gray>This plot has as many", "<gray>as it can."),
                button(Material.ENDER_PEARL, "<highlight>Teleport here"),
                button(Material.BARRIER, "<error>Abandon", "<gray>Give the plot up. It's reset,", "<gray>and what's in it is lost.")),
            new ListMenu("Your plots",
                button(Material.OAK_SIGN, "<highlight><plot>", "<gray>Time left: <white><time>", "<gray>Click to manage.")),
            new MembersMenu("Members of <plot>",
                button(Material.PLAYER_HEAD, "<highlight><player>", "<gray>Click to remove."),
                button(Material.LIME_DYE, "<success>Add a member", "<gray><slots> of <max-slots> slots used"),
                button(Material.ARROW, "<gray>Back")),
            new PickerMenu("Add a member",
                button(Material.PLAYER_HEAD, "<highlight><player>", "<gray>Click to add."),
                button(Material.ARROW, "<gray>Previous page"),
                button(Material.ARROW, "<gray>Next page"),
                button(Material.ARROW, "<gray>Back")),
            new AbandonMenu("Abandon <plot>?",
                button(Material.RED_CONCRETE, "<error>Abandon <plot>", "<gray>It's reset, and anything", "<gray>in it is lost."),
                button(Material.LIME_CONCRETE, "<success>Keep it")),
            new AdminMenu("<plot> (staff)",
                button(Material.OAK_SIGN, "<highlight><plot>", "<gray>Owner: <white><owner>", "<gray>Time left: <white><time>",
                    "<gray>Members: <white><members>"),
                button(Material.ENDER_PEARL, "<highlight>Teleport to the sign"),
                button(Material.IRON_DOOR, "<error>Evict", "<gray>End the rent now. The plot", "<gray>is reset if its field says so."),
                button(Material.TNT, "<error>Reset", "<gray>Put the plot back as it was", "<gray>made. The renter keeps it."))),
        3,
        5,
        StorageSettings.defaults("xplots_"),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XPConfig> SPEC = ConfigSpec.builder(XPConfig.class, DEFAULTS)
        .header(
            "xPlots: rentable plots, set up in a minute.",
            "Docs: https://docs.xenastudios.co/xplots/configuration/",
            "",
            "Apply changes with /xplots reload. A bad value falls back to its default with a",
            "warning in the console; xPlots never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .codec(Duration.class, DURATION)
        .codec(Material.class, MATERIAL)
        .version(1)
        .build();

    /** The name of an amount in item currency. */
    public static String amount(ItemCurrency item, long amount) {
        return amount + " " + (amount == 1 ? item.singular() : item.plural());
    }
}
