package co.xenastudios.xutilities.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.text.Message;
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
            "what it accepts, on a 1 to 10 scale (Minecraft's 0.1 to 1.0; walking is 2 by default)."})
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
            "Workstation menus: open the real menu without the block. message is sent when it",
            "opens (blank sends nothing)."})
        Workstation anvil,
        Workstation craft,
        Workstation grinder,
        Workstation loom,
        Workstation smith,
        Workstation stonecutter,
        Workstation cartography,
        @Comment("/trash: an empty menu whose contents are deleted when it closes.") Trash trash) {}

    private static final String ON = "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>";
    private static final String OTHERS = "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>";

    public static final XConfig DEFAULTS = new XConfig(
        new Messages(ErrorMessages.DEFAULTS, new StateWords("enabled", "disabled")),
        new Commands(
            gamemode("survival", List.of("gms", "gm0")),
            gamemode("creative", List.of("gmc", "gm1")),
            gamemode("adventure", List.of("gma", "gm2")),
            gamemode("spectator", List.of("gmsp", "gm3")),
            new Gamemode(true, List.of("gm"), "xutilities.command.gamemode", "xutilities.command.gamemode.others"),
            new Fly(true, List.of("flight", "togglefly", "toggleflymode"), "xutilities.command.fly", "xutilities.command.fly.others",
                Message.of("<gray>Flight <green><state></green>.</gray>"),
                Message.of("<gray>Flight <red><state></red>.</gray>"),
                Message.of("<gray>Flight <green><state></green> for <yellow><player></yellow>.</gray>"),
                Message.of("<gray>Flight <red><state></red> for <yellow><player></yellow>.</gray>")),
            new Speed(true, List.of("setspeed"), "xutilities.command.speed", "xutilities.command.speed.others", 1, 10,
                Message.of("<gray>Speed set to <aqua><speed></aqua>.</gray>"),
                Message.of("<gray>Set <yellow><player></yellow>'s speed to <aqua><speed></aqua>.</gray>"),
                Message.of("<red>Speed must be between <min> and <max>.</red>")),
            new Top(true, List.of(), "xutilities.command.top",
                Message.of("<gray>Teleported to the highest block.</gray>"),
                Message.of("<red>No safe block to teleport to here.</red>")),
            new PlayerCommand(true, List.of(), "xutilities.command.setrotation", "xutilities.command.setrotation.others",
                Message.of("<gray>Rotation set to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>"),
                Message.of("<gray>Set <yellow><player></yellow>'s rotation to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>")),
            new PlayerCommand(true, List.of(), "xutilities.command.ping", "xutilities.command.ping.others",
                Message.of("<gray>Your ping is <aqua><ping></aqua>ms.</gray>"),
                Message.of("<gray><yellow><player></yellow>'s ping is <aqua><ping></aqua>ms.</gray>")),
            new Back(true, List.of(), "xutilities.command.back",
                Message.of("<gray>Teleported to your previous location.</gray>"),
                Message.of("<red>You have no previous location to return to.</red>")),
            new Heal(true, List.of(), "xutilities.command.heal", "xutilities.command.heal.others", true, true, true,
                Message.of("<green>You have been healed.</green>"),
                Message.of("<green>Healed <yellow><player></yellow>.</green>")),
            new PlayerCommand(true, List.of(), "xutilities.command.feed", "xutilities.command.feed.others",
                Message.of("<green>You have been fed.</green>"),
                Message.of("<green>Fed <yellow><player></yellow>.</green>")),
            new Repair(true, List.of("fix"), "xutilities.command.repair", "xutilities.command.repair.others",
                Message.of("<green>Repaired your held item(s).</green>"),
                Message.of("<green>Repaired <yellow><player></yellow>'s item(s).</green>"),
                Message.of("<red>Nothing to repair.</red>")),
            new Clear(true, List.of("clearinventory", "ci"), "xutilities.command.clear", "xutilities.command.clear.others", true,
                Message.of("<gray>Your inventory has been cleared.</gray>"),
                Message.of("<gray>Cleared <yellow><player></yellow>'s inventory.</gray>")),
            new EnderChest(true, List.of("ec", "ender"), "xutilities.command.enderchest", "xutilities.command.enderchest.others",
                Message.of("<gray>Opening <yellow><player></yellow>'s ender chest.</gray>"),
                Message.of("<red><yellow><player></yellow> is too far away to open their ender chest.</red>")),
            workstation("anvil", List.of()),
            workstation("craft", List.of("workbench", "wb")),
            workstation("grinder", List.of("grindstone")),
            workstation("loom", List.of()),
            workstation("smith", List.of("smithingtable", "smithtable")),
            workstation("stonecutter", List.of()),
            workstation("cartography", List.of("cartographytable")),
            new Trash(true, List.of("disposal"), "xutilities.command.trash", Message.of("Disposal"), Message.empty())),
        links(),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XConfig> SPEC = ConfigSpec.builder(XConfig.class, DEFAULTS)
        .header(
            "xUtilities: every command you need. None you don't.",
            "Docs: https://docs.xenastudios.co/xutilities/configuration/",
            "",
            "Apply changes with /xutilities reload. Everything applies on reload, even turning",
            "commands on or off. A bad value falls back to its default with a warning in the",
            "console; xUtilities never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(2)
        // 1.x had an error for disabled commands; a disabled command now doesn't exist.
        .migrate(1, root -> root.set("messages.error.disabled", null))
        .template(LinkCommand.class, new LinkCommand(true, List.of(), null, Message.empty()))
        .build();

    private static PlayerCommand gamemode(String mode, List<String> aliases) {
        return new PlayerCommand(true, aliases, "xutilities.command." + mode, "xutilities.command." + mode + ".others",
            Message.of(ON), Message.of(OTHERS));
    }

    private static Workstation workstation(String name, List<String> aliases) {
        return new Workstation(true, aliases, "xutilities.command." + name, Message.empty());
    }

    private static Map<String, LinkCommand> links() {
        Map<String, LinkCommand> links = new LinkedHashMap<>();
        links.put("discord", link("discord", "<blue>Join our Discord: ", "https://discord.gg/example", "discord.gg/example", "</blue>"));
        links.put("store", link("store", "<gold>Visit our store: ", "https://store.example.com", "store.example.com", "</gold>"));
        links.put("website", link("website", "<aqua>Visit our website: ", "https://example.com", "example.com", "</aqua>"));
        return links;
    }

    private static LinkCommand link(String name, String before, String url, String shown, String after) {
        return new LinkCommand(true, List.of(), "xutilities.command." + name, Message.of(
            before + "<click:open_url:'" + url + "'><hover:show_text:'<gray>Click to open'><underlined>" + shown
                + "</underlined></hover></click>" + after));
    }
}
