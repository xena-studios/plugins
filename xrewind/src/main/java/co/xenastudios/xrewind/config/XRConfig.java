package co.xenastudios.xrewind.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.StorageSettings;
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

    public record Messages(ErrorMessages error) {}

    public record Logging(
        @Comment("Blocks players break.") boolean blockBreak,
        @Comment("Blocks players place, and what they replaced.") boolean blockPlace,
        @Comment("Players joining and quitting, and where.") boolean sessions) {}

    public static final XRConfig DEFAULTS = new XRConfig(
        Theme.DEFAULTS,
        new Messages(ErrorMessages.DEFAULTS),
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
