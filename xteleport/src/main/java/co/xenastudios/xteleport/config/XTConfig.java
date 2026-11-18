package co.xenastudios.xteleport.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.TextStyle;
import co.xenastudios.core.text.Theme;
import co.xenastudios.xteleport.config.Settings.Command;
import co.xenastudios.xteleport.config.Settings.HomeCommand;
import co.xenastudios.xteleport.config.Settings.HomeLimit;
import co.xenastudios.xteleport.config.Settings.OthersCommand;
import co.xenastudios.xteleport.config.Settings.RtpWorld;
import co.xenastudios.xteleport.config.Settings.TeleportCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** xTeleport's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XTConfig(
    @Comment({
        "The look xTeleport's messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#EA87E2>, or <gold><bold>."})
    Theme theme,
    @Comment("What players are told.") Messages messages,
    @Comment({
        "Every command has the same settings:",
        "  enabled            false removes the command entirely, so another plugin can have its name",
        "  aliases            other names for it",
        "  permission         needed to use it; blank (\"\") lets anyone",
        "  permission-others  needed to use it on another player's homes",
        "Commands that teleport also have:",
        "  cooldown           seconds before it can be used again; xteleport.cooldown.bypass skips it",
        "  cost               what each teleport costs, through Vault; xteleport.cost.bypass skips it.",
        "                     Taken once the warmup is over, and given back if the teleport fails."})
    Commands commands,
    @Comment("How every teleport behaves.") Teleports teleports,
    Homes homes,
    Tpa tpa,
    Warps warps,
    Spawn spawn,
    @Comment("/rtp: a random safe spot.") Rtp rtp,
    @Comment({
        "Where homes and warps are kept. sqlite needs nothing set up. A change here applies on",
        "restart: homes aren't copied across."})
    StorageSettings storage,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Commands(
        @Comment("/sethome [name]: sets a home where you stand. Setting one again moves it.") Command sethome,
        @Comment("/home [name]: with one home, or one called home, the name can be left out.") HomeCommand home,
        @Comment("/delhome <name>") OthersCommand delhome,
        @Comment("/homes [page]: a list to click. Staff with permission-others: /homes <player>.") OthersCommand homes,
        @Comment("/tpa <player>: ask to teleport to someone. The cost is paid by whoever asked.") TeleportCommand tpa,
        @Comment("/tpahere <player>: ask someone to teleport to you.") TeleportCommand tpahere,
        @Comment("/tpaccept [player]: the newest request, or the one from that player.") Command tpaccept,
        Command tpdeny,
        @Comment("/tpacancel [player]: takes back requests you sent.") Command tpacancel,
        @Comment("/tptoggle: stop or start getting requests.") Command tptoggle,
        Command setwarp,
        @Comment("/warp [name]: without a name, lists the warps.") TeleportCommand warp,
        Command delwarp,
        Command warps,
        Command setspawn,
        TeleportCommand spawn,
        @Comment("/rtp [world]") TeleportCommand rtp) {}

    public record Teleports(
        @Comment("Seconds to stand still before teleporting. 0 teleports at once. xteleport.warmup.bypass skips it.")
        @Range(min = 0, max = 60) int warmup,
        @Comment("Whether taking damage during the warmup cancels it, as moving does.") boolean cancelOnDamage,
        @Comment({
            "Refuse to send players to a home, warp or spawn that is now in lava, inside blocks or",
            "over the void, with a message instead of a death. The spot's chunk is loaded in the",
            "background to check, never on the server's thread."})
        boolean checkSafe) {}

    public record Homes(
        @Comment({
            "How many homes a player may have. A player with xteleport.homes.<n> may have <n>",
            "instead, and xteleport.homes.unlimited any number; the highest wins."})
        @Range(min = 0, max = 10_000) int defaultLimit,
        @Comment({
            "More limits, by permission. A blank permission means group.<name>, which LuckPerms",
            "gives every member of that group, so vip: below works for a LuckPerms group called vip."})
        Map<String, HomeLimit> limits,
        @Comment("The home /sethome sets, and /home goes to, when no name is given.") String defaultName,
        @Comment({
            "Let players go to their bed or respawn anchor with /home <bed-name>. It doesn't count",
            "towards their limit, and shows first in /homes."})
        boolean bedHome,
        String bedName,
        @Comment("Homes per page in /homes.") @Range(min = 1, max = 50) int perPage) {}

    public record Tpa(
        @Comment("Seconds before a request runs out.") @Range(min = 5, max = 3600) int expireAfter) {}

    public record Warps(
        @Comment({
            "Each warp needs xteleport.warp.<name> (operators have them all). /warps only lists",
            "the ones a player may use."})
        boolean perWarpPermission,
        @Comment("Warps per page in /warps.") @Range(min = 1, max = 50) int perPage) {}

    public record Spawn(
        @Comment("Send players who join for the first time to the spawn set with /setspawn.") boolean onFirstJoin,
        @Comment("Send every player there each time they join.") boolean onEveryJoin,
        @Comment("Send players there when they die without a bed or respawn anchor.") boolean onRespawn) {}

    public record Rtp(
        @Comment({
            "Where /rtp sends players who don't name a world. Blank means the world they're in, if",
            "/rtp may send them there."})
        String defaultWorld,
        @Comment("Spots tried before giving up. Each loads one chunk in the background.")
        @Range(min = 1, max = 50) int attempts,
        @Comment({
            "How many /rtp searches may run at once, server-wide. Anyone over it is told to try",
            "again in a few seconds, so a crowd typing /rtp can't slow the server."})
        @Range(min = 1, max = 32) int maxSearches,
        @Comment({
            "Only pick chunks that already exist, so /rtp never makes the server generate terrain.",
            "Best for worlds pre-generated with a plugin such as Chunky."})
        boolean onlyGenerated,
        @Comment("Biomes /rtp never picks, as Minecraft names them.") List<String> blockedBiomes,
        @Comment({
            "The worlds /rtp may send players to, by name, and where. A world that isn't listed",
            "can't be picked. The Nether is searched below its roof, and the End off its main",
            "island unless allow-main-island is true."})
        Map<String, RtpWorld> worlds) {}

    public record Messages(
        ErrorMessages error,
        @Comment("Shared by every teleport.") TeleportMessages teleport,
        HomeMessages homes,
        TpaMessages tpa,
        WarpMessages warps,
        SpawnMessages spawn,
        RtpMessages rtp) {}

    public record TeleportMessages(
        @Comment("<seconds> to stand still.") Message warmup,
        @Comment("On the action bar each second of the warmup. Blank for none.") Message countdown,
        Message movedCancelled,
        Message damageCancelled,
        @Comment("<time> until it can be used again.") Message cooldown,
        @Comment("<cost> is what it costs.") Message cantAfford,
        @Comment("After paying. <cost>.") Message paid,
        @Comment("The teleport failed after paying. <cost>.") Message refunded,
        @Comment("The spot is in lava, inside blocks or over the void now.") Message unsafe,
        @Comment("<world> isn't loaded.") Message worldMissing,
        @Comment("The server refused the teleport, or the player left.") Message failed,
        @Comment("Teleports cost money, but Vault or an economy plugin is missing. The console says which.")
        Message noEconomy,
        @Comment("The database isn't open yet, or couldn't be opened. The console says why.") Message unavailable) {}

    public record HomeMessages(
        @Comment("<home> is its name.") Message set,
        Message moved,
        Message deleted,
        Message teleported,
        Message notFound,
        @Comment("<limit> is how many the player may have.") Message limitReached,
        Message badName,
        @Comment("Naming a home after bed-name, while bed-home is on.") Message reservedName,
        Message noBed,
        Message noHomes,
        @Comment("/homes, before the list. <count> and <limit>.") Message header,
        @Comment({
            "One line per home. <home>, <world>, <x> <y> <z>, and <command>: the /home command",
            "for it, to click."})
        Message entry,
        @Comment("Under the list when it has more pages.") Message footer,
        @Comment("Staff using another player's homes. <player> is whose.") Message headerOthers,
        Message notFoundOthers,
        Message noHomesOthers,
        Message deletedOthers,
        @Comment("<player> has never joined this server.") Message neverJoined) {}

    public record TpaMessages(
        @Comment("To whoever asked. <player> is who was asked, <seconds> how long it lasts.") Message sent,
        Message sentHere,
        @Comment({
            "To whoever was asked. <player> asked; <accept> and <deny> are the commands to",
            "answer, to click."})
        Message received,
        Message receivedHere,
        @Comment("To whoever asked.") Message accepted,
        @Comment("To whoever accepted.") Message youAccepted,
        Message denied,
        Message youDenied,
        @Comment("To whoever was asked, when the request is taken back.") Message cancelled,
        @Comment("<count> requests taken back.") Message youCancelled,
        Message noRequests,
        @Comment("<player> sent no request, or it ran out.") Message noRequestFrom,
        Message nothingToCancel,
        Message notYourself,
        Message notTaking,
        Message toggledOff,
        Message toggledOn,
        @Comment("After arriving. <player> is who they went to.") Message teleported) {}

    public record WarpMessages(
        @Comment("<warp> is its name.") Message set,
        Message moved,
        Message deleted,
        Message teleported,
        Message notFound,
        Message badName,
        Message noWarps,
        @Comment("/warps, before the list. <count>.") Message header,
        @Comment("One line per warp. <warp>, <world>, <x> <y> <z>, and <command> to click.") Message entry,
        Message footer) {}

    public record SpawnMessages(Message set, Message teleported) {}

    public record RtpMessages(
        Message searching,
        @Comment("After arriving. <world>, <x> <y> <z>.") Message teleported,
        Message notFound,
        @Comment("Too many searches running at once.") Message busy,
        Message alreadySearching,
        @Comment("<world> isn't one /rtp may send the player to.") Message worldNotAllowed) {}

    private static final String ACCENT = "#EA87E2";

    public static final XTConfig DEFAULTS = new XTConfig(
        new Theme(
            "<" + ACCENT + ">»</" + ACCENT + "> ",
            TextStyle.parse("<gray>"),
            TextStyle.parse("<" + ACCENT + ">"),
            TextStyle.parse("<#5EE08A>"),
            TextStyle.parse("<#FF6B6B>")),
        new Messages(
            ErrorMessages.DEFAULTS,
            new TeleportMessages(
                Message.of("<prefix>Teleporting in <highlight><seconds>s</highlight>. Don't move."),
                Message.of("Teleporting in <highlight><seconds></highlight>..."),
                Message.of("<prefix><error>Teleport cancelled: you moved."),
                Message.of("<prefix><error>Teleport cancelled: you took damage."),
                Message.of("<prefix><error>You can do that again in <highlight><time></highlight>."),
                Message.of("<prefix><error>That costs <highlight><cost></highlight>, which you don't have."),
                Message.of("<prefix>Paid <highlight><cost></highlight>."),
                Message.of("<prefix>The teleport didn't happen, so you got back <highlight><cost></highlight>."),
                Message.of("<prefix><error>That spot isn't safe right now (lava, blocks or the void), so you stayed here."),
                Message.of("<prefix><error>That spot is in <highlight><world></highlight>, which isn't loaded."),
                Message.of("<prefix><error>The teleport didn't work. Try again."),
                Message.of("<prefix><error>Teleports cost money here, but the economy isn't working. Tell staff."),
                Message.of("<prefix><error>Teleports aren't available right now. Try again in a moment.")),
            new HomeMessages(
                Message.of("<prefix><success>Set home</success> <highlight><home></highlight>."),
                Message.of("<prefix><success>Moved home</success> <highlight><home></highlight> here."),
                Message.of("<prefix>Deleted home <highlight><home></highlight>."),
                Message.of("<prefix>Welcome to <highlight><home></highlight>."),
                Message.of("<prefix><error>You have no home called <highlight><home></highlight>. See <highlight>/homes</highlight>."),
                Message.of("<prefix><error>You can have <highlight><limit></highlight> homes. Delete one, or set one again to move it."),
                Message.of("<prefix><error>A home's name is 1 to 32 letters, digits, - or _."),
                Message.of("<prefix><error><highlight><home></highlight> is your bed's name. Pick another."),
                Message.of("<prefix><error>You have no bed or respawn anchor to go to."),
                Message.of("<prefix>You have no homes yet. Set one with <highlight>/sethome</highlight>."),
                Message.of("<prefix>Your homes (<count>/<limit>):"),
                Message.of(" <click:run_command:'<command>'><hover:show_text:'<world> <x> <y> <z>'><highlight><home></highlight></hover></click> <dark_gray><world></dark_gray>"),
                Message.of(" " + ChatPages.FOOTER),
                Message.of("<prefix><highlight><player></highlight>'s homes (<count>):"),
                Message.of("<prefix><error><highlight><player></highlight> has no home called <highlight><home></highlight>."),
                Message.of("<prefix><highlight><player></highlight> has no homes."),
                Message.of("<prefix>Deleted <highlight><player></highlight>'s home <highlight><home></highlight>."),
                Message.of("<prefix><error><highlight><player></highlight> has never played here.")),
            new TpaMessages(
                Message.of("<prefix>Asked <highlight><player></highlight> if you can teleport to them. It lasts <seconds>s."),
                Message.of("<prefix>Asked <highlight><player></highlight> to teleport to you. It lasts <seconds>s."),
                Message.of("<prefix><highlight><player></highlight> wants to teleport to you. "
                    + "<click:run_command:'<accept>'><hover:show_text:'<accept>'><success>[Accept]</success></hover></click> "
                    + "<click:run_command:'<deny>'><hover:show_text:'<deny>'><error>[Deny]</error></hover></click>"),
                Message.of("<prefix><highlight><player></highlight> wants you to teleport to them. "
                    + "<click:run_command:'<accept>'><hover:show_text:'<accept>'><success>[Accept]</success></hover></click> "
                    + "<click:run_command:'<deny>'><hover:show_text:'<deny>'><error>[Deny]</error></hover></click>"),
                Message.of("<prefix><highlight><player></highlight> <success>accepted</success> your request."),
                Message.of("<prefix><success>Accepted</success> <highlight><player></highlight>'s request."),
                Message.of("<prefix><highlight><player></highlight> <error>denied</error> your request."),
                Message.of("<prefix>Denied <highlight><player></highlight>'s request."),
                Message.of("<prefix><highlight><player></highlight> took back their request."),
                Message.of("<prefix>Took back <highlight><count></highlight> request(s)."),
                Message.of("<prefix><error>You have no teleport requests."),
                Message.of("<prefix><error><highlight><player></highlight> hasn't asked you anything, or it ran out."),
                Message.of("<prefix><error>You have no requests to take back."),
                Message.of("<prefix><error>You can't ask yourself."),
                Message.of("<prefix><error><highlight><player></highlight> isn't taking teleport requests."),
                Message.of("<prefix>You won't get teleport requests now. <highlight>/tptoggle</highlight> turns them back on."),
                Message.of("<prefix>You'll get teleport requests again."),
                Message.of("<prefix>Teleported to <highlight><player></highlight>.")),
            new WarpMessages(
                Message.of("<prefix><success>Set warp</success> <highlight><warp></highlight>."),
                Message.of("<prefix><success>Moved warp</success> <highlight><warp></highlight> here."),
                Message.of("<prefix>Deleted warp <highlight><warp></highlight>."),
                Message.of("<prefix>Welcome to <highlight><warp></highlight>."),
                Message.of("<prefix><error>There's no warp called <highlight><warp></highlight>. See <highlight>/warps</highlight>."),
                Message.of("<prefix><error>A warp's name is 1 to 32 letters, digits, - or _."),
                Message.of("<prefix>There are no warps yet."),
                Message.of("<prefix>Warps (<count>):"),
                Message.of(" <click:run_command:'<command>'><hover:show_text:'<command>'><highlight><warp></highlight></hover></click> <dark_gray><world></dark_gray>"),
                Message.of(" " + ChatPages.FOOTER)),
            new SpawnMessages(
                Message.of("<prefix><success>Set the spawn</success> here."),
                Message.of("<prefix>Welcome to spawn.")),
            new RtpMessages(
                Message.of("<prefix>Looking for a safe spot..."),
                Message.of("<prefix>Teleported to <highlight><x> <y> <z></highlight> in <world>."),
                Message.of("<prefix><error>Couldn't find a safe spot this time. Try again."),
                Message.of("<prefix><error>Lots of players are looking for a spot. Try again in a few seconds."),
                Message.of("<prefix><error>Still looking for your spot."),
                Message.of("<prefix><error>/rtp can't send you to <highlight><world></highlight>."))),
        new Commands(
            command("sethome"),
            new HomeCommand(true, List.of(), "xteleport.command.home", "xteleport.homes.others", 0, 0),
            new OthersCommand(true, List.of(), "xteleport.command.delhome", "xteleport.homes.others"),
            new OthersCommand(true, List.of(), "xteleport.command.homes", "xteleport.homes.others"),
            teleport("tpa", List.of()),
            teleport("tpahere", List.of()),
            new Command(true, List.of("tpyes"), "xteleport.command.tpaccept"),
            new Command(true, List.of("tpno"), "xteleport.command.tpdeny"),
            command("tpacancel"),
            command("tptoggle"),
            command("setwarp"),
            teleport("warp", List.of()),
            command("delwarp"),
            command("warps"),
            command("setspawn"),
            teleport("spawn", List.of()),
            new TeleportCommand(true, List.of("wild"), "xteleport.command.rtp", 300, 0)),
        new Teleports(3, true, true),
        new Homes(3, limits(), "home", true, "bed", 10),
        new Tpa(60),
        new Warps(false, 10),
        new Spawn(true, false, true),
        new Rtp("", 10, 3, false,
            List.of("ocean", "deep_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "cold_ocean",
                "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean"),
            rtpWorlds()),
        StorageSettings.defaults("xteleport_"),
        UpdateCheckerSettings.DEFAULTS);

    private static Command command(String name) {
        return new Command(true, List.of(), "xteleport.command." + name);
    }

    private static TeleportCommand teleport(String name, List<String> aliases) {
        return new TeleportCommand(true, aliases, "xteleport.command." + name, 0, 0);
    }

    private static Map<String, HomeLimit> limits() {
        Map<String, HomeLimit> limits = new LinkedHashMap<>();
        limits.put("vip", new HomeLimit("", 5));
        return limits;
    }

    private static Map<String, RtpWorld> rtpWorlds() {
        Map<String, RtpWorld> worlds = new LinkedHashMap<>();
        worlds.put("world", new RtpWorld(true, "", true, 0, 0, 300, 5000, false));
        worlds.put("world_nether", new RtpWorld(true, "", true, 0, 0, 100, 1000, false));
        worlds.put("world_the_end", new RtpWorld(true, "", false, 0, 0, 1100, 4000, false));
        return worlds;
    }

    public static final ConfigSpec<XTConfig> SPEC = ConfigSpec.builder(XTConfig.class, DEFAULTS)
        .header(
            "xTeleport: every teleport your server needs.",
            "Docs: https://docs.xenastudios.co/xteleport/configuration/",
            "",
            "Apply changes with /xteleport reload (or /xtp reload). Everything applies except",
            "storage, even turning commands on or off. A bad value falls back to its default with",
            "a warning in the console; xTeleport never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .template(HomeLimit.class, new HomeLimit("", 0))
        .template(RtpWorld.class, new RtpWorld(true, "", true, 0, 0, 300, 5000, false))
        .build();
}
