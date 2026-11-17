package co.xenastudios.xrewind.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.Message;
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
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(
        ErrorMessages error,
        @Comment("Lookups and the inspector.") LookupMessages lookup) {}

    public record LookupMessages(
        @Comment("Above a page of results. <count> is how many there are in all.") Message header,
        @Comment("Above the history of one block, from the inspector. <x>, <y>, <z>, <world>.") Message headerAt,
        @Comment({
            "One change. <ago> is how long ago, <user> who, <action> what they did, <block> the",
            "block, <x> <y> <z> <world> where."})
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

    public record Words(String placed, String broke, String joined, String quit) {}

    public record Logging(
        @Comment("Blocks players break.") boolean blockBreak,
        @Comment("Blocks players place, and what they replaced.") boolean blockPlace,
        @Comment("Players joining and quitting, and where.") boolean sessions) {}

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
            new Words("placed", "broke", "joined", "quit"))),
        StorageSettings.defaults("xrewind_"),
        new Logging(true, true, true),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XRConfig> SPEC = ConfigSpec.builder(XRConfig.class, DEFAULTS)
        .header(
            "xRewind: every change, logged. Any of it, undone.",
            "Docs: https://docs.xenastudios.co/xrewind/configuration/",
            "",
            "Apply changes with /xrewind reload. A bad value falls back to its default with a",
            "warning in the console; xRewind never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(1)
        .build();
}
