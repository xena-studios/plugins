package co.xenastudios.zchat.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import co.xenastudios.core.text.Theme;
import co.xenastudios.zchat.config.Settings.AntiRepeat;
import co.xenastudios.zchat.config.Settings.ClearChat;
import co.xenastudios.zchat.config.Settings.Cooldown;
import co.xenastudios.zchat.config.Settings.Filter;
import co.xenastudios.zchat.config.Settings.FilterMode;
import co.xenastudios.zchat.config.Settings.Formatting;
import co.xenastudios.zchat.config.Settings.Group;
import co.xenastudios.zchat.config.Settings.Mentions;
import co.xenastudios.zchat.config.Settings.Msg;
import co.xenastudios.zchat.config.Settings.MuteChat;
import co.xenastudios.zchat.config.Settings.PrivateMessages;
import co.xenastudios.zchat.config.Settings.Reply;
import co.xenastudios.zchat.config.Settings.SocialSpy;
import co.xenastudios.zchat.config.Settings.ToggleChat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** zChat's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record ZConfig(
    @Comment({
        "The look zChat's own messages share. The default messages use these as tags, so",
        "changing a colour here restyles them all. Chat formats can use them too. Write a",
        "style as MiniMessage tags: <gray>, <#B48CFF>, or <gold><bold>."})
    Theme theme,
    @Comment("Messages the commands share.") Messages messages,
    @Comment({
        "How chat looks. Formats can use <player> (the name), <displayname> (the name with",
        "any nickname colours), <message>, and PlaceholderAPI placeholders such as",
        "%vault_prefix% when PlaceholderAPI is installed."})
    Formatting formatting,
    @Comment({
        "Catches words you don't want in chat and private messages. Players with",
        "zchat.bypass.filter skip it."})
    Filter filter,
    @Comment("The shortest gap between one player's messages. zchat.bypass.cooldown skips it.") Cooldown cooldown,
    @Comment({
        "Stops a player sending the same message again soon after. Messages under three",
        "letters, like \"gg\", are let through. zchat.bypass.anti-repeat skips it."})
    AntiRepeat antiRepeat,
    @Comment({
        "Writing a player's name, or @name, highlights it and pings them. Players who've",
        "hidden chat aren't pinged."})
    Mentions mentions,
    @Comment({
        "Every command has the same settings:",
        "  enabled     false removes the command entirely",
        "  aliases     other names for it",
        "  permission  needed to use it; blank (\"\") lets anyone",
        "",
        "/clearchat: clears chat for everyone. zchat.bypass.clearchat keeps a player's."})
    ClearChat clearchat,
    @Comment("/mutechat: mutes chat for everyone but zchat.bypass.mute until it's run again.") MuteChat mutechat,
    @Comment("/togglechat: hides other players' chat for yourself, or shows it again.") ToggleChat togglechat,
    @Comment("Private messages. The filter applies to them; the mute and cooldown don't.") PrivateMessages privateMessages,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(ErrorMessages error) {}

    private static final String ARROW = " <dark_gray>»</dark_gray> <white><message></white>";
    private static final String PING = "entity.experience_orb.pickup 0.5 1.2";

    public static final ZConfig DEFAULTS = new ZConfig(
        Theme.DEFAULTS,
        new Messages(ErrorMessages.DEFAULTS),
        new Formatting(true, "zchat.chat.color", Message.of("<gray><player>:</gray> <white><message></white>"), groups()),
        new Filter(true, FilterMode.CENSOR, "*", true, List.of("badword1", "badword2"), List.of(),
            Message.of("<prefix><error>Your message was blocked by the chat filter.")),
        new Cooldown(true, 3, Message.of("<prefix><error>Wait <highlight><seconds>s</highlight> before chatting again.")),
        new AntiRepeat(true, 30, 90, Message.of("<prefix><error>You just said that.")),
        new Mentions(true, Message.of("<highlight>@<name></highlight>"), SoundEffect.parse(PING), "zchat.mention.everyone"),
        new ClearChat(true, List.of("cc"), "zchat.command.clearchat", 100,
            Message.of("<prefix>Chat was cleared by <highlight><player></highlight>.")),
        new MuteChat(true, List.of("mc"), "zchat.command.mutechat",
            Message.of("<prefix>Chat has been <error>muted</error> by <highlight><player></highlight>."),
            Message.of("<prefix>Chat has been <success>unmuted</success> by <highlight><player></highlight>."),
            Message.of("<prefix><error>Chat is muted right now.")),
        new ToggleChat(true, List.of("tc"), "zchat.command.togglechat", true,
            Message.of("<prefix>Chat is now <success>shown</success>."),
            Message.of("<prefix>Chat is now <error>hidden</error>. Run it again to show it.")),
        new PrivateMessages(
            new Msg(true, List.of("tell", "w", "whisper", "m", "pm"), "zchat.command.msg",
                Message.of("<highlight>You → <player></highlight><dark_gray>:</dark_gray> <white><message>"),
                Message.of("<highlight><player> → You</highlight><dark_gray>:</dark_gray> <white><message>"),
                SoundEffect.parse(PING)),
            new Reply(true, List.of("r"), "zchat.command.reply", Message.of("<prefix><error>You have nobody to reply to.")),
            new SocialSpy(true, List.of("spy"), "zchat.command.socialspy", true, true,
                Message.of("<dark_gray>[Spy]</dark_gray> <gray><sender> → <receiver>: <message>"),
                Message.of("<prefix>Social spy <success>on</success>."),
                Message.of("<prefix>Social spy <error>off</error>."))),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<ZConfig> SPEC = ConfigSpec.builder(ZConfig.class, DEFAULTS)
        .header(
            "zChat: format your chat. Keep it clean.",
            "Docs: https://docs.xenastudios.co/zchat/configuration/",
            "",
            "Apply changes with /zchat reload (or /zc reload). Everything applies, even turning",
            "commands on or off. A bad value falls back to its default with a warning in the",
            "console; zChat never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(2)
        // zChat 2's example patterns were placeholders; zChat 3 puts examples in words.
        .migrate(1, root -> {
            if (List.of("badword1", "badword2").equals(root.getStringList("filter.patterns"))) {
                root.set("filter.patterns", List.of());
            }
        })
        // zChat 1.x was a different plugin: no config-version, and none of zChat 2's sections.
        .foreign(ZConfig::fromZChatOne, "config.yml is from zChat 1.x, which zChat 3 can't carry forward.")
        .template(Group.class, new Group(0, null, Message.empty()))
        .apply(ZConfig::formerDefaults)
        .build();

    private static final List<String> SECTIONS = List.of(
        "theme", "messages", "formatting", "filter", "cooldown", "anti-repeat", "mentions",
        "clearchat", "mutechat", "togglechat", "private-messages", "update-checker");

    private static boolean fromZChatOne(ConfigurationSection root) {
        if (root.contains("config-version") || root.getKeys(false).isEmpty()) {
            return false;
        }
        return SECTIONS.stream().noneMatch(root::contains);
    }

    private static Map<String, Group> groups() {
        Map<String, Group> groups = new LinkedHashMap<>();
        groups.put("vip", new Group(10, "zchat.group.vip", Message.of("<gold>[VIP] <player></gold>" + ARROW)));
        groups.put("staff", new Group(20, "zchat.group.staff", Message.of("<aqua>[Staff] <player></aqua>" + ARROW)));
        groups.put("admin", new Group(30, "zchat.group.admin", Message.of("<red>[Admin] <player></red>" + ARROW)));
        return groups;
    }

    // zChat 2's messages. A carried-forward file still using one gets zChat 3's instead.
    private static ConfigSpec.Builder<ZConfig> formerDefaults(ConfigSpec.Builder<ZConfig> spec) {
        return spec
            .formerDefault("messages.error.player-only", "<red>Only players can use this command.</red>")
            .formerDefault("messages.error.no-permission", "<red>You don't have permission to do that.</red>")
            .formerDefault("filter.message-blocked", "<red>Your message was blocked by the chat filter.</red>")
            .formerDefault("cooldown.message", "<red>Please wait <yellow><seconds></yellow>s before chatting again.</red>")
            .formerDefault("clearchat.message-cleared", "<gray>Chat was cleared by <yellow><player></yellow>.</gray>")
            .formerDefault("mutechat.message-muted", "<gray>Chat has been <red>muted</red> by <yellow><player></yellow>.</gray>")
            .formerDefault("mutechat.message-unmuted", "<gray>Chat has been <green>unmuted</green> by <yellow><player></yellow>.</gray>")
            .formerDefault("mutechat.message-blocked", "<red>Chat is currently muted.</red>")
            .formerDefault("togglechat.message-on", "<gray>Chat is now <green>shown</green>.</gray>")
            .formerDefault("togglechat.message-off", "<gray>Chat is now <red>hidden</red>.</gray>");
    }
}
