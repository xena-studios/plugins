package co.xenastudios.xrewind.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.Message;
import java.util.List;
import co.xenastudios.core.text.Theme;

/** xRewind's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XRConfig(
    @Comment({
        "The look xRewind's messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#3DD6C6>, or <gold><bold>."})
    Theme theme,
    @Comment("Messages the commands share.") Messages messages,
    @Comment({
        "Where the history is kept. Changing it on /xrewind reload switches to the new one;",
        "history isn't copied across."})
    StorageSettings storage,
    @Comment({
        "What's logged. Something that's off isn't even listened for, so it costs nothing.",
        "Changes apply on /xrewind reload."})
    Logging log,
    @Comment({
        "Rollbacks and restores. Blocks are set a few milliseconds per tick, chunk by chunk, so",
        "even a big one doesn't freeze the server, and without physics, so nothing cascades."})
    Rollback rollback,
    @Comment("Deleting old history, so the database doesn't grow forever.") Purge purge,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(
        ErrorMessages error,
        @Comment("Lookups and the inspector.") LookupMessages lookup,
        @Comment("Rollbacks, restores, previews and undo.") RollbackMessages rollback,
        @Comment("/xrewind purge and /xrewind import.") UpkeepMessages upkeep) {}

    public record UpkeepMessages(
        @Comment("A purge of less than a day.") Message purgeTooRecent,
        Message purging,
        @Comment("<count> changes deleted; <time> how old.") Message purged,
        @Comment("<source> is where CoreProtect's history is read from.") Message importing,
        @Comment("<count> changes imported in <seconds>s.") Message imported,
        @Comment("It was imported before. <date>.") Message importedBefore,
        @Comment("CoreProtect's database couldn't be read. <reason>.") Message importFailed,
        Message importRunning) {}

    public record RollbackMessages(
        @Comment("A rollback or restore without t:.") Message needTime,
        @Comment("Past max-radius without permission. <max> is the limit.") Message tooFar,
        @Comment("Another one is already running.") Message busy,
        Message nothing,
        @Comment("A #preview is showing. <count> is how many blocks.") Message preview,
        Message noPreview,
        Message cancelled,
        @Comment("On the action bar while it works. <verb>, <done>, <total>.") Message progress,
        @Comment("When it's finished. <verb>, <count>, <seconds>.") Message done,
        @Comment("Stored blocks this server couldn't read. <count>.") Message skipped,
        Message noUndo,
        Verbs verbs) {}

    public record Verbs(String rollback, String restore, String undo) {}

    public record LookupMessages(
        @Comment("Above a page of results. <count> is how many there are in all.") Message header,
        @Comment("Above the history of one block, from the inspector. <x>, <y>, <z>, <world>.") Message headerAt,
        @Comment({
            "One change. <ago> is how long ago, <user> who, <action> what they did, <block> what",
            "to (a block, items, a mob, or what they wrote), <x> <y> <z> <world> where."})
        Message line,
        @Comment("A change that's been rolled back.") Message lineUndone,
        @Comment("Below a page. <page>, <pages>, and <next>: the command for the next page.") Message footer,
        @Comment("Nothing matched.") Message none,
        @Comment("Sent while waiting on the database.") Message working,
        @Comment("The parameters were wrong. <reason> says how.") Message invalid,
        @Comment("There's no previous lookup for /xrewind page.") Message noPrevious,
        @Comment("The database isn't ready yet.") Message notReady,
        Message inspectorOn,
        Message inspectorOff,
        @Comment("The words for each kind of change, as <action> shows them.") Words words) {}

    public record Words(String placed, String broke, String clicked, String added, String took, String killed,
                        String wrote, String said, String ran, String joined, String quit) {}

    public record Rollback(
        @Comment("How long a rollback may work each tick, in milliseconds. A tick is 50.")
        @co.xenastudios.core.config.Range(min = 1, max = 40) int msPerTick,
        @Comment("The radius, in blocks, when a rollback doesn't give one with r:.")
        @co.xenastudios.core.config.Range(min = 0, max = 1000) int defaultRadius,
        @Comment({
            "The largest r: without the xrewind.rollback.global permission, which r:#world and",
            "r:#global also need."})
        @co.xenastudios.core.config.Range(min = 0, max = 100_000) int maxRadius) {}

    public record Purge(
        @Comment({
            "Delete history older than this many days, once a day. 0 keeps everything. A",
            "rollback can't reach further back than what's kept."})
        @co.xenastudios.core.config.Range(min = 0, max = 10_000) int keepDays) {}

    public record Logging(
        @Comment("Blocks players break, with what they held, and blocks that fall off with them.") boolean blockBreak,
        @Comment("Blocks players place, and what they replaced.") boolean blockPlace,
        @Comment("Blocks explosions destroy. TNT is put down to whoever lit it.") boolean explosions,
        @Comment("Blocks burnt away, and fires players light.") boolean fire,
        @Comment("Buckets, and flowing water or lava destroying a block. Plain spreading isn't logged.") boolean liquids,
        @Comment("Blocks pistons move.") boolean pistons,
        @Comment("Endermen, withers, ravagers, falling sand. Sheep eating grass and villagers farming aren't.") boolean mobs,
        @Comment("Text written on signs.") boolean signs,
        @Comment("Items players put into or take out of containers.") boolean containers,
        @Comment("Items hoppers and droppers move. Busy on farms.") boolean hoppers,
        @Comment("Mobs and players killed by players.") boolean kills,
        @Comment("Doors, trapdoors, gates, levers and buttons players use.") boolean clicks,
        @Comment("Leaves decaying.") boolean leafDecay,
        @Comment("Players joining and quitting, and where.") boolean sessions,
        @Comment("Chat messages. Off by default for players' privacy; lookups need xrewind.lookup.chat.") boolean chat,
        @Comment("Commands players run. Off by default; lookups need xrewind.lookup.chat.") boolean commands,
        @Comment("Worlds nothing is logged in, by name.") List<String> excludedWorlds) {}

    public static final XRConfig DEFAULTS = new XRConfig(
        Theme.DEFAULTS,
        new Messages(ErrorMessages.DEFAULTS, new LookupMessages(
            Message.of("<prefix>Lookup: <highlight><count></highlight> changes"),
            Message.of("<prefix>History of <highlight><x> <y> <z></highlight> in <world>"),
            Message.of("<gray><ago> ago</gray> <highlight><user></highlight> <action> <highlight><block></highlight> <gray>at <x> <y> <z></gray>"),
            Message.of("<strikethrough><gray><ago> ago</gray> <user> <action> <block> <gray>at <x> <y> <z></gray></strikethrough> <gray>(undone)"),
            Message.of("<gray>Page <page> of <pages></gray> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next page »"),
            Message.of("<prefix>Nothing found."),
            Message.of("<prefix><gray>Looking..."),
            Message.of("<prefix><error><reason>"),
            Message.of("<prefix><error>Look something up first, then use /xrewind page."),
            Message.of("<prefix><error>The database isn't ready yet. /xrewind status says why."),
            Message.of("<prefix>Inspector <success>on</success>. Click a block to see its history; place one to see what was there."),
            Message.of("<prefix>Inspector <highlight>off</highlight>."),
            new Words("placed", "broke", "used", "added", "took", "killed", "wrote", "said", "ran", "joined", "quit")),
            new RollbackMessages(
                Message.of("<prefix><error>Add a time, such as <highlight>t:1h</highlight>, so it can't reach further back than you meant."),
                Message.of("<prefix><error>That's past the <highlight><max></highlight>-block limit. r:#world, r:#global and bigger radii need <highlight>xrewind.rollback.global</highlight>."),
                Message.of("<prefix><error>Another rollback is running. Wait for it to finish."),
                Message.of("<prefix>Nothing to change."),
                Message.of("<prefix>Previewing <highlight><count></highlight> blocks, for you only. <click:run_command:'/xrewind apply'><hover:show_text:'/xrewind apply'><success>[Apply]</success></hover></click> <click:run_command:'/xrewind cancel'><hover:show_text:'/xrewind cancel'><error>[Cancel]</error></hover></click>"),
                Message.of("<prefix><error>You have no preview. Add #preview to a rollback or restore."),
                Message.of("<prefix>Preview cancelled. Nothing changed."),
                Message.of("<highlight><verb></highlight>: <done> of <total> blocks"),
                Message.of("<prefix><success><verb></success> <highlight><count></highlight> blocks in <seconds>s."),
                Message.of("<prefix><error><count> blocks were stored by a Minecraft version this one can't read, and were left as they are."),
                Message.of("<prefix><error>Nothing to undo. Undo works on your last rollback or restore since the server started."),
                new Verbs("Rolled back", "Restored", "Undid")),
            new UpkeepMessages(
                Message.of("<prefix><error>Purge history at least a day old: <highlight>t:1d</highlight> or more."),
                Message.of("<prefix><gray>Purging..."),
                Message.of("<prefix><success>Purged</success> <highlight><count></highlight> changes older than <time>."),
                Message.of("<prefix>Importing CoreProtect's history from <highlight><source></highlight>. Logging carries on meanwhile; the console shows progress."),
                Message.of("<prefix><success>Imported</success> <highlight><count></highlight> changes from CoreProtect in <seconds>s."),
                Message.of("<prefix><error>CoreProtect's history was already imported (<date>). <highlight>#again</highlight> imports it again, and what came across before shows twice."),
                Message.of("<prefix><error>Couldn't read CoreProtect's history: <reason>"),
                Message.of("<prefix><error>An import is already running."))),
        StorageSettings.defaults("xrewind_"),
        new Logging(true, true, true, true, true, true, true, true, true, false, true, false, false, true, false, false, List.of()),
        new Rollback(10, 10, 100),
        new Purge(30),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XRConfig> SPEC = ConfigSpec.builder(XRConfig.class, DEFAULTS)
        .header(
            "xRewind: every change, logged. Any of it, undone.",
            "Docs: https://docs.xenastudios.co/xrewind/configuration/",
            "",
            "Apply changes with /xrewind reload (or /xr reload). A bad value falls back to its",
            "default with a warning in the console; xRewind never stops over config. All text is",
            "MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(1)
        .build();
}
