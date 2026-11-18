package co.xenastudios.xvote.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import co.xenastudios.core.text.Theme;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** xVote's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XVConfig(
    @Comment({
        "The look xVote's messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#F2B53A>, or <gold><bold>."})
    Theme theme,
    @Comment("What players are told.") Messages messages,
    @Comment({
        "The Votifier receiver: where server lists send votes. It speaks Votifier v1 and v2,",
        "like NuVotifier, and uses the keys in rsa/ and the tokens in tokens.yml. Don't run",
        "NuVotifier as well: both would want the same port. Changes apply on /xvote reload."})
    Receiver receiver,
    @Comment({
        "The sites players vote on, shown by /vote. Each is matched to votes by its service:",
        "the name the list sends, which /xvote status shows for the last votes. name is",
        "what players see. A vote sooner than cooldown-hours after the last one from the same",
        "site is a repeat and is dropped; set it to how often the site lets players vote.",
        "rewards are given for this site only, on top of every-vote's. Votes from a site",
        "that isn't listed still count."})
    Map<String, Site> sites,
    @Comment({
        "Rewards, by name, to use in triggers, sites and the party. Each can give money",
        "(through Vault), items, console commands, a message, a broadcast and a sound, with",
        "a chance in percent. Items are written like /give: diamond 3, or",
        "diamond_sword[enchantments={sharpness:3}] 1. Items that don't fit wait for /vote",
        "claim. Commands, messages and broadcasts can use <player>, <uuid>, <site>, <total>",
        "and <streak>. custom lists reward types other plugins add, as \"<type> <argument>\"."})
    Map<String, Reward> rewards,
    @Comment({
        "Which rewards each vote gives, besides its site's. every-vote: on every vote.",
        "first-vote-of-day: a player's first vote each day. every: on every Nth vote, by N.",
        "streak: on reaching that many days in a row. total: once, on reaching that many votes."})
    Triggers triggers,
    @Comment({
        "Every so many votes from anyone, a reward for everyone online (who: online), or for",
        "everyone online who voted since the last party (who: voted)."})
    Party party,
    @Comment("Telling players they can vote.") Reminders reminders,
    @Comment("How votes are counted.") Counting counting,
    @Comment({
        "Where votes are kept. sqlite needs nothing set up; mysql (or MariaDB) is for networks.",
        "A change here applies on restart: votes aren't copied across."})
    StorageSettings storage,
    @Comment({
        "For networks: servers sharing one MySQL database, so a vote that arrives on one",
        "server pays the player on whichever server they're on. Applies on restart."})
    NetworkSettings network,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Receiver(
        @Comment("Off stops listening; votes from /vote test and other plugins still count.") boolean enabled,
        @Comment("The address to listen on. 0.0.0.0 is every address the machine has.") String host,
        @Comment({
            "The port lists send votes to. It must be open to the internet, and isn't the",
            "server's own port. On a host that gives you one port, see the docs."})
        @Range(min = 1, max = 65535) int port,
        @Comment("Accept Votifier v1 votes. Most lists still send v1; turn it off only if all yours send v2.")
        boolean v1,
        @Comment("Most votes handled at once. More are turned away until one finishes.")
        @Range(min = 1, max = 1000) int maxConnections,
        @Comment("Seconds a list has to send its vote before the connection is closed.")
        @Range(min = 1, max = 60) int timeoutSeconds,
        @Comment("Most connections one address may make a minute. A list makes one per vote.")
        @Range(min = 1, max = 100_000) int perIpPerMinute) {}

    public record Site(
        String name,
        String service,
        String link,
        @Range(min = 0, max = 720) int cooldownHours,
        List<String> rewards) {}

    public record Reward(
        @Range(min = 0, max = 100) double chance,
        @Range(min = 0) double money,
        List<String> items,
        List<String> commands,
        Message message,
        Message broadcast,
        SoundEffect sound,
        List<String> custom) {}

    public record Milestone(List<String> rewards) {}

    public record Triggers(
        List<String> everyVote,
        List<String> firstVoteOfDay,
        Map<String, Milestone> every,
        Map<String, Milestone> streak,
        Map<String, Milestone> total) {}

    public record Party(
        boolean enabled,
        @Comment("Votes from anyone a party needs.") @Range(min = 1, max = 1_000_000) int votes,
        @Comment("online or voted.") Who who,
        List<String> rewards,
        @Comment("Broadcast when a party starts.") Message started,
        @Comment("Broadcast every this many votes on the way, with <left>. 0 is never.")
        @Range(min = 0, max = 1_000_000) int progressEvery,
        Message progress) {

        public enum Who { ONLINE, VOTED }
    }

    public record Reminders(
        @Comment("Remind players who can vote when they join.") boolean onJoin,
        @Comment("Seconds after joining.") @Range(min = 0, max = 600) int joinDelaySeconds,
        @Comment({
            "Also remind everyone online who can vote, every this many minutes. 0 is off, and",
            "then nothing repeats."})
        @Range(min = 0, max = 1440) int everyMinutes,
        Message message) {}

    public record Counting(
        @Comment({
            "Days, weeks and months start at midnight here: an id like Europe/London or",
            "America/New_York, or server for the server's own."})
        String timeZone,
        @Comment("The day weeks start on.") DayOfWeek weekStarts,
        @Comment({
            "Floodgate's prefix for Bedrock players' names. A vote for a name nobody has is",
            "tried with it, so Bedrock players who leave it off are still paid."})
        String bedrockPrefix,
        @Comment({
            "Days to keep votes for names that have never joined. They're paid if that name",
            "joins in time. 0 keeps them forever."})
        @Range(min = 0, max = 3650) int unknownNameDays) {}

    public record Messages(
        ErrorMessages error,
        @Comment({
            "/vote: a header, a line per site, and a footer. <site> is the site's name,",
            "<link> its link, <time> how long until it can be voted on again; <available> and",
            "<sites> count them; <total> and <streak> are the player's."})
        VoteList list,
        @Comment("/vote top. <rank>, <player>, <votes>; <period> is one of periods below.") Top top,
        @Comment("Reward items that didn't fit, and /vote claim. <count> is how many.") Claim claim) {}

    public record VoteList(Message header, Message available, Message cooldown, Message footer,
                           @Comment("Under the footer when the vote party is on. <votes>, <needed>, <left>.") Message party,
                           Message none) {}

    public record Top(Message header, Message line, Message footer, Message empty, Periods periods) {}

    public record Periods(String day, String week, String month, String all) {}

    public record Claim(
        @Comment("A reward's items didn't all fit.") Message held,
        Message none,
        Message claimed,
        @Comment("Some still didn't fit.") Message stillFull) {}

    private static final String LINK = "<click:open_url:'<link>'><hover:show_text:'<gray>Open <link>'>";

    public static final XVConfig DEFAULTS = new XVConfig(
        Theme.DEFAULTS,
        new Messages(
            ErrorMessages.DEFAULTS,
            new VoteList(
                Message.of("<prefix>Vote for the server and get rewards. <highlight><available></highlight> of <sites> sites are open:"),
                Message.of(" " + LINK + "<success>●</success> <highlight><site></highlight> <gray>click to vote</gray></hover></click>"),
                Message.of(" " + LINK + "<gray>✓ <site>, again in <time></gray></hover></click>"),
                Message.of("<gray>You have <highlight><total></highlight> votes and a <highlight><streak></highlight>-day streak."),
                Message.of("<gray>Vote party: <highlight><votes></highlight> of <needed>, <left> to go."),
                Message.of("<prefix>There's nowhere to vote yet.")),
            new Top(
                Message.of("<prefix>Top voters <period>:"),
                Message.of(" <gray>#<rank></gray> <highlight><player></highlight> <gray><votes> votes"),
                Message.of(ChatPages.FOOTER),
                Message.of("<prefix>No votes <period> yet."),
                new Periods("today", "this week", "this month", "of all time")),
            new Claim(
                Message.of("<prefix><highlight><count></highlight> reward items didn't fit. Make room, then <click:run_command:'/vote claim'><highlight>/vote claim</highlight></click>."),
                Message.of("<prefix>You have no reward items waiting."),
                Message.of("<prefix><success>Here are your <count> reward items."),
                Message.of("<prefix><error><count> items still don't fit.</error> Make room, then /vote claim again."))),
        new Receiver(true, "0.0.0.0", 8192, true, 32, 10, 60),
        defaultSites(),
        defaultRewards(),
        new Triggers(List.of("vote"), List.of(), Map.of(), Map.of("7", new Milestone(List.of("streak"))), Map.of()),
        new Party(false, 50, Party.Who.ONLINE, List.of("party"),
            Message.of("<prefix><highlight>Vote party!</highlight> <votes> votes, so everyone gets a reward."),
            10,
            Message.of("<prefix><highlight><left></highlight> more votes until the vote party. <click:run_command:'/vote'><highlight>/vote</highlight></click>")),
        new Reminders(true, 5, 0,
            Message.of("<prefix>You can vote on <highlight><available></highlight> sites for rewards. <click:run_command:'/vote'><highlight>/vote</highlight></click>")),
        new Counting("server", DayOfWeek.MONDAY, ".", 30),
        StorageSettings.defaults("xvote_"),
        NetworkSettings.DEFAULTS,
        UpdateCheckerSettings.DEFAULTS);

    private static Map<String, Site> defaultSites() {
        Map<String, Site> sites = new LinkedHashMap<>();
        sites.put("planetminecraft", new Site("Planet Minecraft", "PlanetMinecraft.com",
            "https://www.planetminecraft.com/server/your-server/vote/", 24, List.of()));
        sites.put("minecraft-mp", new Site("Minecraft-MP", "Minecraft-MP.com",
            "https://minecraft-mp.com/server/00000/vote/", 24, List.of()));
        return sites;
    }

    private static Map<String, Reward> defaultRewards() {
        Map<String, Reward> rewards = new LinkedHashMap<>();
        rewards.put("vote", new Reward(100, 0, List.of("diamond 1"), List.of(),
            Message.of("<prefix><success>Thanks for voting</success> on <highlight><site></highlight>! Here's a diamond."),
            Message.of("<prefix><highlight><player></highlight> voted on <site>. <click:run_command:'/vote'><highlight>/vote</highlight></click> to vote too."),
            SoundEffect.parse("entity.player.levelup"), List.of()));
        rewards.put("streak", new Reward(100, 0, List.of("diamond 5"), List.of(),
            Message.of("<prefix>A <highlight><streak>-day</highlight> voting streak! Here are 5 more diamonds."),
            Message.empty(), SoundEffect.NONE, List.of()));
        rewards.put("party", new Reward(100, 0, List.of("golden_apple 1"), List.of(),
            Message.of("<prefix><success>Vote party!</success> Here's a golden apple."),
            Message.empty(), SoundEffect.parse("entity.firework_rocket.blast"), List.of()));
        return rewards;
    }

    public static final ConfigSpec<XVConfig> SPEC = ConfigSpec.builder(XVConfig.class, DEFAULTS)
        .header(
            "xVote: vote rewards, with a built-in Votifier.",
            "Docs: https://docs.xenastudios.co/xvote/configuration/",
            "",
            "Apply changes with /xvote reload. A bad value falls back to its default with a",
            "warning in the console; xVote never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(1)
        .template(Site.class, new Site("", "", "", 24, List.of()))
        .template(Reward.class, new Reward(100, 0, List.of(), List.of(), Message.empty(), Message.empty(), SoundEffect.NONE, List.of()))
        .template(Milestone.class, new Milestone(List.of()))
        .build();
}
