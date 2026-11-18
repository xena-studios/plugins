package co.xenastudios.xplots.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completer;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.command.Subcommands;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Box;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Layout;
import co.xenastudios.xplots.model.Places;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.Settings;
import co.xenastudios.xplots.rent.Info;
import co.xenastudios.xplots.setup.Pending;
import co.xenastudios.xplots.setup.Selections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /xplots}'s subcommands beside reload and info: the wand, fields, plots, confirm and cancel. */
public final class AdminCommands {

    private static final Message REPLY = Message.of("<prefix><text>");

    private final XPlots plugin;

    private AdminCommands(XPlots plugin) {
        this.plugin = plugin;
    }

    public static List<CommandSpec> specs(XPlots plugin) {
        return new AdminCommands(plugin).all();
    }

    private List<CommandSpec> all() {
        String admin = plugin.adminPermission();
        List<CommandSpec> field = List.of(
            sub("create", "/xplots field create <name> size:<n or WxD> [path:<n>]", this::fieldCreate,
                (s, a) -> a.length >= 2 ? Completions.matching(a[a.length - 1], List.of("size:", "path:")) : List.of()),
            sub("delete", "/xplots field delete <field>", this::fieldDelete, this::completeField),
            sub("list", "/xplots field list", this::fieldList, Completer.NONE),
            sub("info", "/xplots field info <field>", this::fieldInfo, this::completeField),
            sub("set", "/xplots field set <field> <setting> <value or default>", this::fieldSet, (s, a) -> switch (a.length) {
                case 1 -> completeField(s, a);
                case 2 -> Completions.matching(a[1], Settings.KEYS);
                case 3 -> Completions.matching(a[2], List.of("default"));
                default -> List.of();
            }),
            sub("setwarp", "/xplots field setwarp <field> [remove]", this::fieldWarp, (s, a) -> a.length == 2
                ? Completions.matching(a[1], List.of("remove")) : completeField(s, a)));
        List<CommandSpec> plot = List.of(
            sub("create", "/xplots plot create <field>", this::plotCreate, this::completeField),
            sub("delete", "/xplots plot delete [plot]", inv -> plotDelete(inv), this::completePlot),
            sub("info", "/xplots plot info [plot]", this::plotInfo, this::completePlot),
            sub("evict", "/xplots plot evict [plot]", this::plotEvict, this::completePlot),
            sub("reset", "/xplots plot reset [plot]", this::plotReset, this::completePlot),
            sub("setowner", "/xplots plot setowner <plot> <player>", this::plotSetOwner, this::completePlotPlayer),
            sub("addmember", "/xplots plot addmember <plot> <player>", this::plotAddMember, this::completePlotPlayer),
            sub("removemember", "/xplots plot removemember <plot> <player>", this::plotRemoveMember, this::completePlotPlayer));
        return List.of(
            CommandSpec.named("wand").permission(admin).usage("/xplots wand").executes(this::wand),
            CommandSpec.named("select").permission(admin).usage("/xplots select <world> <x1> <y1> <z1> <x2> <y2> <z2>")
                .completes((s, a) -> a.length == 1 ? Completions.matching(a[0], Bukkit.getWorlds().stream().map(World::getName).toList())
                    : List.of())
                .executes(this::select),
            group("field", field, admin),
            group("plot", plot, admin),
            CommandSpec.named("confirm").permission(admin).usage("/xplots confirm").executes(inv -> {
                if (!plugin.pending().confirm(inv.sender().getName())) {
                    throw fail("<error>Nothing is waiting to be confirmed.");
                }
            }),
            CommandSpec.named("cancel").permission(admin).usage("/xplots cancel").executes(inv -> {
                if (!plugin.pending().cancel(inv.sender().getName())) {
                    throw fail("<error>Nothing is waiting to be confirmed.");
                }
                reply(inv.sender(), "Cancelled. Nothing changed.");
            }));
    }

    private static CommandSpec sub(String name, String usage, co.xenastudios.core.command.Executor executor, Completer completer) {
        return CommandSpec.named(name).usage(usage).completes(completer).executes(executor);
    }

    private static CommandSpec group(String name, List<CommandSpec> subs, String permission) {
        return CommandSpec.named(name).permission(permission).usage("/xplots " + name + " " + Subcommands.names(subs))
            .completes(Subcommands.completer(subs)).executes(Subcommands.executor(subs));
    }

    // ---- replies ----

    private static void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(REPLY.chat(Placeholders.component("text", Message.of(miniMessage).render())));
    }

    private static CommandFailure fail(String miniMessage) {
        return new CommandFailure(Message.of("<prefix>" + miniMessage));
    }

    // ---- selection ----

    private void wand(Invocation inv) {
        Player player = inv.player();
        player.getInventory().addItem(plugin.selections().wand()).values()
            .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        reply(player, "Left click one corner of the plot area and right click the other, then <highlight>/xplots field create</highlight>.");
    }

    private void select(Invocation inv) {
        World world = Bukkit.getWorld(inv.arg(0) == null ? "" : inv.arg(0));
        if (world == null) {
            throw fail("<error>There's no world called " + safe(inv.arg(0)) + ".");
        }
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) {
            c[i] = inv.integer(i + 1, -30_000_000, 30_000_000);
        }
        String sender = inv.sender().getName();
        plugin.selections().corner(sender, 1, world.getName(), c[0], c[1], c[2]);
        Box box = plugin.selections().corner(sender, 2, world.getName(), c[3], c[4], c[5]).box();
        reply(inv.sender(), "Selected <highlight>" + box.width() + " × " + box.height() + " × " + box.depth() + "</highlight> in " + world.getName() + ".");
    }

    private Selections.Selection selection(Invocation inv) {
        Selections.Selection selection = plugin.selections().get(inv.sender().getName());
        if (selection == null || selection.box() == null) {
            throw fail("<error>Select the area first: <highlight>/xplots wand</highlight>, then click two corners.");
        }
        return selection;
    }

    // ---- fields ----

    private void fieldCreate(Invocation inv) {
        String name = inv.arg(0);
        if (name == null) {
            throw inv.usage();
        }
        name = name.toLowerCase(Locale.ROOT);
        int sizeX = 0;
        int sizeZ = 0;
        int path = 1;
        for (int i = 1; i < inv.size(); i++) {
            String arg = inv.arg(i).toLowerCase(Locale.ROOT);
            if (arg.startsWith("size:")) {
                String[] parts = arg.substring(5).split("x", -1);
                sizeX = number(parts[0], 1, 256);
                sizeZ = parts.length == 2 ? number(parts[1], 1, 256) : sizeX;
                if (parts.length > 2) {
                    throw inv.usage();
                }
            } else if (arg.startsWith("path:")) {
                path = number(arg.substring(5), 0, 64);
            } else {
                throw inv.usage();
            }
        }
        if (sizeX == 0) {
            throw inv.usage();
        }
        Selections.Selection selection = selection(inv);
        World world = Bukkit.getWorld(selection.world());
        if (world == null) {
            throw fail("<error>The selection's world isn't loaded.");
        }
        Layout layout = Layout.of(selection.box(), sizeX, sizeZ, path);
        String problem = plugin.builder().problem(name, world.getName(), layout);
        if (problem != null) {
            throw fail("<error>" + problem);
        }
        CommandSender sender = inv.sender();
        String fieldName = name;
        Object preview = sender instanceof Player player ? plugin.builder().preview(player, world.getName(), layout) : null;
        plugin.pending().put(sender.getName(), new Pending.Action() {
            @Override
            public void confirm() {
                // Checked again: something may have changed in the two minutes since.
                String again = plugin.builder().problem(fieldName, world.getName(), layout);
                if (again != null) {
                    reply(sender, "<error>" + again);
                    return;
                }
                reply(sender, "Making <highlight>" + fieldName + "</highlight>...");
                plugin.builder().createField(fieldName, world, layout, () -> reply(sender,
                    "<success>Made</success> field <highlight>" + fieldName + "</highlight>: " + layout.count()
                        + " plots, each with its sign. Settings: <highlight>/xplots field info " + fieldName + "</highlight>."));
            }

            @Override
            public void dismiss() {
                plugin.builder().stopPreview(preview);
            }
        });
        String size = sizeX + "×" + sizeZ;
        reply(sender, "<highlight>" + layout.cols() + " × " + layout.rows() + " = " + layout.count() + "</highlight> plots of "
            + size + ", " + path + "-block paths" + (sender instanceof Player ? ", outlined around you" : "") + ". "
            + "<click:run_command:'/xplots confirm'><hover:show_text:'/xplots confirm'><success>[Confirm]</success></hover></click> "
            + "<click:run_command:'/xplots cancel'><hover:show_text:'/xplots cancel'><error>[Cancel]</error></hover></click>");
    }

    private static int number(String raw, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Falls through.
        }
        throw fail("<error>" + safe(raw) + " should be a whole number from " + min + " to " + max + ".");
    }

    private Field field(Invocation inv, int index) {
        String name = inv.arg(index);
        if (name == null) {
            throw inv.usage();
        }
        Field field = plugin.plots().field(name);
        if (field == null) {
            throw fail("<error>There's no field called " + safe(name) + ".");
        }
        return field;
    }

    private void fieldDelete(Invocation inv) {
        Field field = field(inv, 0);
        long rented = field.plots().stream().filter(p -> !p.isFree()).count();
        CommandSender sender = inv.sender();
        plugin.pending().put(sender.getName(), () -> {
            if (plugin.plots().field(field.name()) != field) {
                return;
            }
            plugin.builder().deleteField(field);
            reply(sender, "Deleted <highlight>" + field.name() + "</highlight>. Its blocks are as they were.");
        });
        reply(sender, "Delete <highlight>" + field.name() + "</highlight> and its " + field.plots().size() + " plots"
            + (rented > 0 ? ", <error>" + rented + " of them rented, with no refund</error>" : "") + "? Signs come down; blocks stay. "
            + "<click:run_command:'/xplots confirm'><hover:show_text:'/xplots confirm'><error>[Delete]</error></hover></click> "
            + "<click:run_command:'/xplots cancel'><hover:show_text:'/xplots cancel'><success>[Keep]</success></hover></click>");
    }

    private void fieldList(Invocation inv) {
        if (plugin.plots().isEmpty()) {
            reply(inv.sender(), "No fields yet. Start with <highlight>/xplots wand</highlight>.");
            return;
        }
        List<Field> fields = new ArrayList<>(plugin.plots().fields());
        fields.sort((a, b) -> a.name().compareTo(b.name()));
        StringBuilder out = new StringBuilder("Fields:");
        for (Field field : fields) {
            List<Plot> plots = field.plots();
            long rented = plots.stream().filter(p -> !p.isFree()).count();
            out.append("<newline> <highlight>").append(field.name()).append("</highlight> in ").append(field.world())
                .append(": ").append(rented).append(" of ").append(plots.size()).append(" rented");
        }
        reply(inv.sender(), out.toString());
    }

    private void fieldInfo(Invocation inv) {
        Field field = field(inv, 0);
        var settings = field.settings(plugin.config().plots());
        var area = field.layout().area();
        long rented = field.plots().stream().filter(p -> !p.isFree()).count();
        StringBuilder out = new StringBuilder("<highlight>" + field.name() + "</highlight> in " + field.world() + " at "
            + area.minX() + " " + area.minY() + " " + area.minZ() + ": " + field.plots().size() + " plots of "
            + field.layout().sizeX() + "×" + field.layout().sizeZ() + ", " + rented + " rented.");
        Map<String, String> own = field.overrides();
        String[][] rows = {
            {"price", plugin.wallet().format(settings.price())},
            {"period", Info.time(settings.period().toMillis())},
            {"max-prepaid", Info.time(settings.maxPrepaid().toMillis())},
            {"starting-slots", Integer.toString(settings.startingSlots())},
            {"max-slots", Integer.toString(settings.maxSlots())},
            {"slot-price", plugin.wallet().format(settings.slotPrice())},
            {"reset", Boolean.toString(settings.reset())},
            {"abandon-refund", settings.abandonRefund() + "%"}};
        for (String[] row : rows) {
            out.append("<newline> ").append(row[0]).append(": <highlight>").append(safe(row[1])).append("</highlight>")
                .append(own.containsKey(row[0]) ? " (this field's own)" : "");
        }
        out.append("<newline> warp: ").append(field.warp() == null ? "none" : "set");
        reply(inv.sender(), out.toString());
    }

    private void fieldSet(Invocation inv) {
        Field field = field(inv, 0);
        String key = inv.arg(1);
        String value = inv.rest(2);
        if (key == null || value == null) {
            throw inv.usage();
        }
        key = key.toLowerCase(Locale.ROOT);
        if (!Settings.KEYS.contains(key)) {
            throw fail("<error>" + safe(key) + " isn't a setting. Use one of: " + String.join(", ", Settings.KEYS) + ".");
        }
        if (value.equalsIgnoreCase("default")) {
            field.override(key, null);
            reply(inv.sender(), field.name() + " now uses the config's <highlight>" + key + "</highlight>.");
        } else {
            try {
                field.override(key, Settings.check(key, value));
            } catch (IllegalArgumentException e) {
                throw fail("<error>" + key + " " + safe(e.getMessage()) + ".");
            }
            reply(inv.sender(), "Set " + field.name() + "'s <highlight>" + key + "</highlight> to <highlight>"
                + safe(field.overrides().get(key)) + "</highlight>.");
        }
        plugin.store().updateField(field);
        // Prices and periods show on signs.
        field.plots().forEach(plugin.signs()::update);
    }

    private void fieldWarp(Invocation inv) {
        Field field = field(inv, 0);
        if ("remove".equalsIgnoreCase(inv.arg(1))) {
            field.warp(null);
            reply(inv.sender(), "Removed " + field.name() + "'s warp.");
        } else {
            Player player = inv.player();
            if (!player.getWorld().getName().equals(field.world())) {
                throw fail("<error>Stand in " + field.world() + ", the field's world.");
            }
            field.warp(Places.encode(player.getLocation()));
            reply(player, "Set " + field.name() + "'s warp here: <highlight>/plots warp " + field.name() + "</highlight>.");
        }
        plugin.store().updateField(field);
    }

    // ---- plots ----

    /** The plot named at {@code index}, or the one the player stands in. */
    private Plot plot(Invocation inv, int index) {
        String id = inv.arg(index);
        if (id == null) {
            if (inv.sender() instanceof Player player) {
                Plot here = plugin.plots().at(player.getLocation().getBlock());
                if (here != null && here != Field.PATH) {
                    return here;
                }
            }
            throw inv.usage();
        }
        Plot plot = plugin.plots().plot(id.toLowerCase(Locale.ROOT));
        if (plot == null) {
            throw fail("<error>There's no plot called " + safe(id) + ". Plots are named like box-12.");
        }
        return plot;
    }

    private void plotCreate(Invocation inv) {
        Field field = field(inv, 0);
        Selections.Selection selection = selection(inv);
        World world = Bukkit.getWorld(selection.world());
        if (world == null) {
            throw fail("<error>The selection's world isn't loaded.");
        }
        String problem = plugin.builder().plotProblem(field, world.getName(), selection.box());
        if (problem != null) {
            throw fail("<error>" + problem);
        }
        Plot plot = plugin.builder().createPlot(field, world, selection.box());
        reply(inv.sender(), "<success>Made</success> <highlight>" + plot.id() + "</highlight>. Its sign is outside the middle of its south edge; "
            + "sneak and right click with the wand to move it.");
    }

    private void plotDelete(Invocation inv) {
        Plot plot = plot(inv, 0);
        plugin.builder().deletePlot(plot);
        reply(inv.sender(), "Deleted <highlight>" + plot.id() + "</highlight>. Its blocks are as they were.");
    }

    private void plotInfo(Invocation inv) {
        Plot plot = plot(inv, 0);
        Info info = plugin.rentals().info(plot);
        Box box = plot.box();
        String where = box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ();
        reply(inv.sender(), "<highlight>" + plot.id() + "</highlight>, " + info.size() + ", " + where
            + (plot.isFree() ? ": free." : "<newline> Owner: <highlight>" + safe(info.owner()) + "</highlight>, "
                + info.time() + " left<newline> Members (" + info.members() + "/" + info.slots() + "): " + safe(info.memberNames()))
            + (plugin.resets().isBusy(plot) ? "<newline> Being put back now." : ""));
    }

    private void plotEvict(Invocation inv) {
        Plot plot = plot(inv, 0);
        if (plot.isFree()) {
            throw fail("<error>" + plot.id() + " isn't rented.");
        }
        String owner = plot.lease().ownerName();
        plugin.rentals().end(plot);
        reply(inv.sender(), "Evicted " + safe(owner) + " from <highlight>" + plot.id() + "</highlight>.");
    }

    private void plotReset(Invocation inv) {
        Plot plot = plot(inv, 0);
        CommandSender sender = inv.sender();
        plugin.resets().reset(plot, () -> {
            plugin.signs().update(plot);
            reply(sender, "Put <highlight>" + plot.id() + "</highlight> back as it was made.");
        });
    }

    private OfflinePlayer known(Invocation inv, int index) {
        String name = inv.arg(index);
        if (name == null) {
            throw inv.usage();
        }
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        if (player == null) {
            throw new CommandFailure(plugin.config().messages().neverJoined(), Placeholders.text("player", name));
        }
        return player;
    }

    private void plotSetOwner(Invocation inv) {
        Plot plot = plot(inv, 0);
        OfflinePlayer owner = known(inv, 1);
        plugin.rentals().setOwner(plot, owner);
        reply(inv.sender(), "<highlight>" + plot.id() + "</highlight> is " + safe(plot.lease().ownerName()) + "'s, for "
            + Info.time(plot.lease().remaining(System.currentTimeMillis())) + ".");
    }

    private void plotAddMember(Invocation inv) {
        Plot plot = plot(inv, 0);
        if (plot.isFree()) {
            throw fail("<error>" + plot.id() + " isn't rented, so it has no members.");
        }
        plugin.rentals().addMember(inv.sender(), plot, known(inv, 1), true);
    }

    private void plotRemoveMember(Invocation inv) {
        Plot plot = plot(inv, 0);
        plugin.rentals().removeMember(inv.sender(), plot, known(inv, 1).getUniqueId());
    }

    // ---- completion ----

    private List<String> completeField(CommandSender sender, String[] args) {
        return args.length == 1 ? Completions.matching(args[0], plugin.plots().fields().stream().map(Field::name).toList()) : List.of();
    }

    private List<String> completePlot(CommandSender sender, String[] args) {
        return args.length == 1 ? Completions.matching(args[0], plugin.plots().plotIds()) : List.of();
    }

    private List<String> completePlotPlayer(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return completePlot(sender, args);
        }
        if (args.length == 2) {
            return Completions.players(sender, args[1]);
        }
        return List.of();
    }

    /** Text from a player or the database, safe to put in a MiniMessage reply. */
    private static String safe(String text) {
        return text == null ? "" : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text);
    }
}
