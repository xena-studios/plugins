package co.xenastudios.xstats.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.api.ProfileLine;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.data.PlayerRecord;
import co.xenastudios.xstats.profile.Sections;
import co.xenastudios.xstats.stat.Format;
import co.xenastudios.xstats.stat.StatDef;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** /stats [player], and /stats private. */
public final class StatsCommand {

    /** /stats private: everyone has it. */
    public static final String PRIVATE = "xstats.private";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private StatsCommand() {}

    public static void register(Setup setup, XStats plugin, XSConfig config) {
        XSConfig.Command settings = config.commands().stats();
        if (!settings.enabled()) {
            return;
        }
        setup.command(CommandSpec.named("stats", settings)
            .permissionDefault(PermissionDefault.TRUE)
            .description("See a player's stats.")
            .usage("/stats [player|private]")
            .completes((sender, args) -> {
                if (args.length != 1) {
                    return List.of();
                }
                List<String> options = new ArrayList<>(Completions.players(sender, args[0]));
                if (sender.hasPermission(PRIVATE)) {
                    options.addAll(Completions.matching(args[0], List.of("private")));
                }
                return options;
            })
            .executes(invocation -> {
                CommandSender sender = invocation.sender();
                XSConfig.Messages messages = plugin.config().messages();
                Engine engine = plugin.engine();
                if (!engine.ready()) {
                    throw new CommandFailure(messages.notReady());
                }
                String name = invocation.arg(0);
                if ("private".equalsIgnoreCase(name) && (!(sender instanceof Player self) || !self.getName().equalsIgnoreCase(name))) {
                    invocation.require(PRIVATE);
                    Player player = invocation.player();
                    plugin.worker().execute(() -> {
                        PlayerRecord record = engine.ensure(player.getUniqueId(), player.getName());
                        boolean hide = !record.hidden();
                        engine.hide(record, hide);
                        plugin.tasks().sender(player, () -> (hide ? messages.privateOn() : messages.privateOff()).send(player));
                    });
                    return;
                }
                if (name == null) {
                    Player player = invocation.player();
                    PlayerRecord record = engine.player(player.getUniqueId());
                    show(plugin, sender, record != null ? record : new PlayerRecord(player.getUniqueId(), player.getName()));
                    return;
                }
                PlayerRecord known = engine.byName(name);
                if (known != null) {
                    show(plugin, sender, known);
                    return;
                }
                if (!Profiles.validName(name)) {
                    throw new CommandFailure(messages.neverJoined(), Placeholders.text("player", name));
                }
                // Not under that name: maybe they changed it.
                plugin.profiles().byName(name).thenAccept(found -> {
                    PlayerRecord record = found.map(profile -> engine.player(profile.id())).orElse(null);
                    if (record == null) {
                        plugin.tasks().sender(sender, () -> messages.neverJoined().send(sender, Placeholders.text("player", name)));
                    } else {
                        show(plugin, sender, record);
                    }
                });
            }));
    }

    /** Builds the profile off the server's threads (other plugins' sections may take a moment), then sends it. */
    static void show(XStats plugin, CommandSender sender, PlayerRecord record) {
        boolean staff = sender.hasPermission(XStats.STAFF);
        plugin.tasks().async(() -> {
            List<Component> lines = profile(plugin, record, staff);
            plugin.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
        });
    }

    static List<Component> profile(XStats plugin, PlayerRecord record, boolean staff) {
        XSConfig config = plugin.config();
        XSConfig.Messages messages = config.messages();
        Engine engine = plugin.engine();
        ZoneId zone = engine.settings().calendar().zone();
        String name = record.name() == null ? record.id.toString() : record.name();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.profileHeader().chat(
            Placeholders.text("player", name),
            Placeholders.text("first-join", record.firstJoin() > 0 ? DATE.format(Instant.ofEpochMilli(record.firstJoin()).atZone(zone)) : "-"),
            Placeholders.text("last-seen", lastSeen(record)),
            Placeholders.text("playtime", plugin.catalog().snapshot().get("playtime")
                .map(stat -> Format.value(stat, engine.value(record, stat, Period.ALL))).orElse("")),
            Placeholders.text("kd", kd(plugin, engine, record))));
        for (StatDef stat : plugin.catalog().snapshot().stats()) {
            if (!stat.inGame()) {
                continue;
            }
            int rank = engine.rank(record, stat, Period.ALL);
            Component rankText = rank > 0 ? messages.profileRank().render(Placeholders.text("rank", rank)) : Component.empty();
            lines.add(messages.profileLine().render(
                Placeholders.text("stat", stat.name()),
                Placeholders.text("value", Format.value(stat, engine.value(record, stat, Period.ALL))),
                Placeholders.component("rank", rankText)));
        }
        for (Sections.Filled section : plugin.sections().fill(record.id, staff)) {
            lines.add(messages.profileSection().chat(Placeholders.text("title", section.title())));
            for (ProfileLine line : section.lines()) {
                lines.add(messages.profileSectionLine().render(Placeholders.text("label", line.label()),
                    Placeholders.text("value", line.value())));
            }
        }
        String url = pageUrl(config, record);
        if (url != null) {
            // Inside a click only pre-processed tags are filled in; the url can't hold a quote (pageUrl checks).
            lines.add(messages.profileLink().chat(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver("url",
                net.kyori.adventure.text.minimessage.tag.Tag.preProcessParsed(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(url)))));
        }
        return lines;
    }

    private static String kd(XStats plugin, Engine engine, PlayerRecord record) {
        var stats = plugin.catalog().snapshot();
        var kills = stats.get("kills");
        var deaths = stats.get("deaths");
        return kills.isEmpty() || deaths.isEmpty() ? ""
            : Format.ratio(engine.value(record, kills.get(), Period.ALL), engine.value(record, deaths.get(), Period.ALL));
    }

    private static String lastSeen(PlayerRecord record) {
        if (org.bukkit.Bukkit.getPlayer(record.id) != null) {
            return "now";
        }
        if (record.lastSeen() <= 0) {
            return "-";
        }
        return Format.time(Math.max(0, (System.currentTimeMillis() - record.lastSeen()) / 1000)) + " ago";
    }

    /** The player's page on the website, or null when there's no public url or they're hidden. */
    public static String pageUrl(XSConfig config, PlayerRecord record) {
        String base = config.web().publicUrl().strip();
        if (base.isEmpty() || record.hidden() || record.name() == null || !base.matches("https?://[^\\s'\"<>]+")) {
            return null;
        }
        // By uuid: it never changes, and Bedrock players' names (Floodgate's .Steve) aren't Java names.
        return (base.endsWith("/") ? base : base + "/") + "#/player/" + record.id;
    }
}
