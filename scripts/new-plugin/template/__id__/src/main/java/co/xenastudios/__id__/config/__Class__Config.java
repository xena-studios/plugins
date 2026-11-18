package co.xenastudios.{{id}}.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.text.TextStyle;
import co.xenastudios.core.text.Theme;

/** {{display}}'s config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record {{Class}}Config(
    @Comment({
        "The look {{display}}'s messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<{{accentText}}>, or <gold><bold>."})
    Theme theme,
    @Comment("Messages the commands share.") Messages messages,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(ErrorMessages error) {}

    // {{display}}'s accent, as text (xena-studios/brand's plugin catalog: accentText).
    private static final String ACCENT = "{{accentText}}";

    public static final {{Class}}Config DEFAULTS = new {{Class}}Config(
        new Theme(
            "<" + ACCENT + ">»</" + ACCENT + "> ",
            TextStyle.parse("<gray>"),
            TextStyle.parse("<" + ACCENT + ">"),
            TextStyle.parse("<#5EE08A>"),
            TextStyle.parse("<#FF6B6B>")),
        new Messages(ErrorMessages.DEFAULTS),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<{{Class}}Config> SPEC = ConfigSpec.builder({{Class}}Config.class, DEFAULTS)
        .header(
            "{{display}}: {{taglineLower}}",
            "Docs: https://plugins.xenastudios.co/{{id}}/configuration/",
            "",
            "Apply changes with /{{id}} reload. A bad value falls back to its default with a",
            "warning in the console; {{display}} never stops over config. All text is MiniMessage:",
            "https://plugins.xenastudios.co/start/formatting/")
        .build();
}
