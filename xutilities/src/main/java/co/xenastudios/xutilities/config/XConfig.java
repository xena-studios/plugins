package co.xenastudios.xutilities.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import co.xenastudios.core.text.Theme;
import co.xenastudios.xutilities.config.Settings.Back;
import co.xenastudios.xutilities.config.Settings.Clear;
import co.xenastudios.xutilities.config.Settings.EnderChest;
import co.xenastudios.xutilities.config.Settings.Fly;
import co.xenastudios.xutilities.config.Settings.Gamemode;
import co.xenastudios.xutilities.config.Settings.Heal;
import co.xenastudios.xutilities.config.Settings.LinkCommand;
import co.xenastudios.xutilities.config.Settings.PlayerCommand;
import co.xenastudios.xutilities.config.Settings.Repair;
import co.xenastudios.xutilities.config.Settings.Speed;
import co.xenastudios.xutilities.config.Settings.Top;
import co.xenastudios.xutilities.config.Settings.Trash;
import co.xenastudios.xutilities.config.Settings.Workstation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** xUtilities' config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XConfig(
    @Comment({
        "The look every message shares. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#B48CFF>, or <gold><bold>."})
    Theme theme,
    @Comment("Messages the commands share.") Messages messages,
    @Comment({
        "Every command has the same settings:",
        "  enabled            false removes the command entirely",
        "  aliases            other names for it",
        "  permission         needed to use it; blank (\"\") lets anyone",
        "  permission-others  needed to use it on another player, on commands with a [player] form",
        "When one player changes another, the changed player gets the -self message too."})
    Commands commands,
    @Comment({
        "Commands you write yourself. Each key becomes a command that sends its message,",
        "usually a clickable link. Add one, such as vote:, and reload to get /vote.",
        "permission is optional: it defaults to xutilities.command.<name>, which everyone has.",
        "A name or alias that clashes with one of the commands above is skipped.",
        "Replace the example links!"})
    Map<String, LinkCommand> infoCommands,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(ErrorMessages error, @Comment("The words filled in for <state>.") StateWords state) {}

    public record StateWords(String enabled, String disabled) {}

    public record Commands(
        @Comment("/survival [player]") PlayerCommand survival,
        @Comment("/creative [player]") PlayerCommand creative,
        @Comment("/adventure [player]") PlayerCommand adventure,
        @Comment("/spectator [player]") PlayerCommand spectator,
        @Comment({
            "/gamemode <mode> [player]. Offers the modes whose own command is on, and needs",
            "that command's permission too. <mode> can be a name, a number or an alias such as gmc."})
        Gamemode gamemode,
        @Comment("/fly [player]: turns flight on or off. <state> is a word from messages.state.") Fly fly,
        @Comment({
            "/speed <speed> [player]: fly speed while flying, walk speed otherwise. min and max bound",
            "what it accepts, on a 1 to 10 scale (Minecraft's 0.1 to 1.0; walking is 2 by default).",
            "<type> is a word from type-flying or type-walking."})
        Speed speed,
        @Comment("/top: teleports you to the highest block above you, or in the Nether, the highest below the roof.") Top top,
        @Comment("/setrotation <yaw> <pitch> [player]: sets which way a player faces.") PlayerCommand setrotation,
        @Comment("/ping [player]: network latency in milliseconds. Everyone has it by default.") PlayerCommand ping,
        @Comment("/back: returns you to where you were before your last teleport, or where you died.") Back back,
        @Comment("/heal [player]: restores health, and these extras.") Heal heal,
        @Comment("/feed [player]: restores hunger.") PlayerCommand feed,
        @Comment("/repair [all] [player]: repairs the held item, or with all, the whole inventory.") Repair repair,
        @Comment("/clear [player]: empties the inventory. include-armor: false keeps armour and the off-hand.") Clear clear,
        @Comment("/enderchest [player]: opens your Ender Chest, or another player's.") EnderChest enderchest,
        @Comment({
            "Workstation menus: open the real menu without the block. When it opens, message is",
            "sent and sound is played; leave either blank for none. A sound is its Minecraft name,",
            "optionally followed by a volume (0 to 10) and pitch (0.5 to 2): \"block.anvil.use 0.5 1.2\"."})
        Workstation anvil,
        Workstation craft,
        Workstation grinder,
        Workstation loom,
        Workstation smith,
        Workstation stonecutter,
        Workstation cartography,
        @Comment("/trash: an empty menu whose contents are deleted when it closes.") Trash trash) {}

    private static final String ON = "<prefix>Your gamemode is now <highlight><mode></highlight>.";
    private static final String OTHERS = "<prefix>Set <highlight><player></highlight>'s gamemode to <highlight><mode></highlight>.";

    public static final XConfig DEFAULTS = new XConfig(
        Theme.DEFAULTS,
        new Messages(ErrorMessages.DEFAULTS, new StateWords("enabled", "disabled")),
        new Commands(
            gamemode("survival", List.of("gms", "gm0")),
            gamemode("creative", List.of("gmc", "gm1")),
            gamemode("adventure", List.of("gma", "gm2")),
            gamemode("spectator", List.of("gmsp", "gm3")),
            new Gamemode(true, List.of("gm"), "xutilities.command.gamemode", "xutilities.command.gamemode.others"),
            new Fly(true, List.of("flight", "togglefly", "toggleflymode"), "xutilities.command.fly", "xutilities.command.fly.others",
                Message.of("<prefix>Flight <success><state></success>."),
                Message.of("<prefix>Flight <error><state></error>."),
                Message.of("<prefix>Flight <success><state></success> for <highlight><player></highlight>."),
                Message.of("<prefix>Flight <error><state></error> for <highlight><player></highlight>.")),
            new Speed(true, List.of("setspeed"), "xutilities.command.speed", "xutilities.command.speed.others", 1, 10, "flying", "walking",
                Message.of("<prefix>Your <type> speed is now <highlight><speed></highlight>."),
                Message.of("<prefix>Set <highlight><player></highlight>'s <type> speed to <highlight><speed></highlight>."),
                Message.of("<prefix><error>Speed must be from <min> to <max>.")),
            new Top(true, List.of(), "xutilities.command.top",
                Message.of("<prefix>Teleported to the top."),
                Message.of("<prefix><error>There's no safe block to stand on here.")),
            new PlayerCommand(true, List.of(), "xutilities.command.setrotation", "xutilities.command.setrotation.others",
                Message.of("<prefix>Now facing yaw <highlight><yaw></highlight>, pitch <highlight><pitch></highlight>."),
                Message.of("<prefix><highlight><player></highlight> is now facing yaw <highlight><yaw></highlight>, pitch <highlight><pitch></highlight>.")),
            new PlayerCommand(true, List.of(), "xutilities.command.ping", "xutilities.command.ping.others",
                Message.of("<prefix>Your ping is <highlight><ping>ms</highlight>."),
                Message.of("<prefix><highlight><player></highlight>'s ping is <highlight><ping>ms</highlight>.")),
            new Back(true, List.of(), "xutilities.command.back",
                Message.of("<prefix>Teleported back to your previous location."),
                Message.of("<prefix><error>You have nowhere to go back to.")),
            new Heal(true, List.of(), "xutilities.command.heal", "xutilities.command.heal.others", true, true, true,
                Message.of("<prefix>You've been <success>healed</success>."),
                Message.of("<prefix><success>Healed</success> <highlight><player></highlight>.")),
            new PlayerCommand(true, List.of(), "xutilities.command.feed", "xutilities.command.feed.others",
                Message.of("<prefix>You've been <success>fed</success>."),
                Message.of("<prefix><success>Fed</success> <highlight><player></highlight>.")),
            new Repair(true, List.of("fix"), "xutilities.command.repair", "xutilities.command.repair.others",
                Message.of("<prefix>Your gear has been <success>repaired</success>."),
                Message.of("<prefix><success>Repaired</success> <highlight><player></highlight>'s gear."),
                Message.of("<prefix><error>Nothing needs repairing.")),
            new Clear(true, List.of("clearinventory", "ci"), "xutilities.command.clear", "xutilities.command.clear.others", true,
                Message.of("<prefix>Your inventory has been <success>cleared</success>."),
                Message.of("<prefix><success>Cleared</success> <highlight><player></highlight>'s inventory.")),
            new EnderChest(true, List.of("ec", "ender"), "xutilities.command.enderchest", "xutilities.command.enderchest.others",
                SoundEffect.parse("block.ender_chest.open 0.5"),
                Message.of("<prefix>Opened <highlight><player></highlight>'s ender chest."),
                Message.of("<prefix><error><highlight><player></highlight> is too far away to open their ender chest.")),
            workstation("anvil", List.of()),
            workstation("craft", List.of("workbench", "wb")),
            workstation("grinder", List.of("grindstone")),
            workstation("loom", List.of()),
            workstation("smith", List.of("smithingtable", "smithtable")),
            workstation("stonecutter", List.of()),
            workstation("cartography", List.of("cartographytable")),
            new Trash(true, List.of("disposal"), "xutilities.command.trash", Message.of("Disposal"), Message.empty(),
                SoundEffect.parse("block.barrel.open 0.5"))),
        links(),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XConfig> SPEC = ConfigSpec.builder(XConfig.class, DEFAULTS)
        .header(
            "xUtilities: every command you need. None you don't.",
            "Docs: https://docs.xenastudios.co/xutilities/configuration/",
            "",
            "Apply changes with /xutilities reload (or /xu reload). Everything applies, even",
            "turning commands on or off. A bad value falls back to its default with a warning in the",
            "console; xUtilities never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(2)
        // 1.x had an error for disabled commands; a disabled command now doesn't exist.
        .migrate(1, root -> root.set("messages.error.disabled", null))
        .template(LinkCommand.class, new LinkCommand(true, List.of(), null, Message.empty()))
        .apply(XConfig::formerDefaults)
        .build();

    private static PlayerCommand gamemode(String mode, List<String> aliases) {
        return new PlayerCommand(true, aliases, "xutilities.command." + mode, "xutilities.command." + mode + ".others",
            Message.of(ON), Message.of(OTHERS));
    }

    private static Workstation workstation(String name, List<String> aliases) {
        return new Workstation(true, aliases, "xutilities.command." + name, Message.empty(), SoundEffect.NONE);
    }

    private static Map<String, LinkCommand> links() {
        Map<String, LinkCommand> links = new LinkedHashMap<>();
        links.put("discord", link("discord", "Join our Discord: ", "https://discord.gg/example", "discord.gg/example"));
        links.put("store", link("store", "Visit our store: ", "https://store.example.com", "store.example.com"));
        links.put("website", link("website", "Visit our website: ", "https://example.com", "example.com"));
        return links;
    }

    private static LinkCommand link(String name, String text, String url, String shown) {
        return new LinkCommand(true, List.of(), "xutilities.command." + name, Message.of(
            "<prefix>" + text + "<click:open_url:'" + url + "'><hover:show_text:'<gray>Click to open'><highlight><underlined>" + shown));
    }

    // 1.x's messages. A carried-forward file still using one gets 2.0's instead.
    private static ConfigSpec.Builder<XConfig> formerDefaults(ConfigSpec.Builder<XConfig> spec) {
        spec.formerDefault("messages.error.player-only", "<red>Only players can use this command.</red>")
            .formerDefault("messages.error.no-permission", "<red>You don't have permission to do that.</red>")
            .formerDefault("messages.error.player-not-found", "<red>That player could not be found.</red>");
        for (String mode : List.of("survival", "creative", "adventure", "spectator")) {
            spec.formerDefault("commands." + mode + ".message-self", "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>")
                .formerDefault("commands." + mode + ".message-others", "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>");
        }
        return spec
            .formerDefault("commands.fly.message-on-self", "<gray>Flight <green><state></green>.</gray>")
            .formerDefault("commands.fly.message-off-self", "<gray>Flight <red><state></red>.</gray>")
            .formerDefault("commands.fly.message-on-others", "<gray>Flight <green><state></green> for <yellow><player></yellow>.</gray>")
            .formerDefault("commands.fly.message-off-others", "<gray>Flight <red><state></red> for <yellow><player></yellow>.</gray>")
            .formerDefault("commands.speed.message-self", "<gray>Speed set to <aqua><speed></aqua>.</gray>")
            .formerDefault("commands.speed.message-others", "<gray>Set <yellow><player></yellow>'s speed to <aqua><speed></aqua>.</gray>")
            .formerDefault("commands.speed.message-invalid", "<red>Speed must be between <min> and <max>.</red>")
            .formerDefault("commands.top.message-success", "<gray>Teleported to the highest block.</gray>")
            .formerDefault("commands.top.message-unsafe", "<red>No safe block to teleport to here.</red>")
            .formerDefault("commands.setrotation.message-self", "<gray>Rotation set to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>")
            .formerDefault("commands.setrotation.message-others", "<gray>Set <yellow><player></yellow>'s rotation to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>")
            .formerDefault("commands.ping.message-self", "<gray>Your ping is <aqua><ping></aqua>ms.</gray>")
            .formerDefault("commands.ping.message-others", "<gray><yellow><player></yellow>'s ping is <aqua><ping></aqua>ms.</gray>")
            .formerDefault("commands.back.message-success", "<gray>Teleported to your previous location.</gray>")
            .formerDefault("commands.back.message-none", "<red>You have no previous location to return to.</red>")
            .formerDefault("commands.heal.message-self", "<green>You have been healed.</green>")
            .formerDefault("commands.heal.message-others", "<green>Healed <yellow><player></yellow>.</green>")
            .formerDefault("commands.feed.message-self", "<green>You have been fed.</green>")
            .formerDefault("commands.feed.message-others", "<green>Fed <yellow><player></yellow>.</green>")
            .formerDefault("commands.repair.message-self", "<green>Repaired your held item(s).</green>")
            .formerDefault("commands.repair.message-others", "<green>Repaired <yellow><player></yellow>'s item(s).</green>")
            .formerDefault("commands.repair.message-nothing", "<red>Nothing to repair.</red>")
            .formerDefault("commands.clear.message-self", "<gray>Your inventory has been cleared.</gray>")
            .formerDefault("commands.clear.message-others", "<gray>Cleared <yellow><player></yellow>'s inventory.</gray>")
            .formerDefault("commands.enderchest.message-others", "<gray>Opening <yellow><player></yellow>'s ender chest.</gray>")
            .formerDefault("info-commands.discord.message",
                "<blue>Join our Discord: <click:open_url:'https://discord.gg/example'><hover:show_text:'<gray>Click to open'><underlined>discord.gg/example</underlined></hover></click></blue>")
            .formerDefault("info-commands.store.message",
                "<gold>Visit our store: <click:open_url:'https://store.example.com'><hover:show_text:'<gray>Click to open'><underlined>store.example.com</underlined></hover></click></gold>")
            .formerDefault("info-commands.website.message",
                "<aqua>Visit our website: <click:open_url:'https://example.com'><hover:show_text:'<gray>Click to open'><underlined>example.com</underlined></hover></click></aqua>");
    }
}
