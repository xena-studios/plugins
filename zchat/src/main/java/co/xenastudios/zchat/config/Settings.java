package co.xenastudios.zchat.config;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import java.util.List;
import java.util.Map;

/** The record for each part of zChat's config. */
public final class Settings {

    private Settings() {}

    public record Formatting(
        @Comment("false leaves chat as the server shows it.") boolean enabled,
        @Comment({
            "Players with this permission can use colour and style tags, such as <red> or",
            "<bold>, in their own messages. Never clicks or hovers. Blank lets nobody."})
        String colorPermission,
        @Comment("The format for players in no group below.") Message defaultFormat,
        @Comment({
            "Each group's format goes to players with its permission (zchat.group.<name> if left",
            "out). A player in several groups gets the one with the highest weight."})
        Map<String, Group> groups) {}

    /**
     * A chat format for players with a permission.
     *
     * @param permission null when left out, meaning {@code zchat.group.<name>}
     */
    public record Group(int weight, String permission, Message format) {}

    public enum FilterMode { CENSOR, BLOCK }

    public record Filter(
        boolean enabled,
        @Comment("censor hides what matched and lets the message through; block stops the message.") FilterMode mode,
        String censorChar,
        @Comment({
            "Also catch words dressed up to get past the filter: s p a c e d out, b4dw0rd,",
            "baaadword, bädword. false matches words only as typed."})
        boolean smart,
        @Comment({
            "Words to catch, matched as whole words. Put * at either end to also catch longer",
            "words: \"bad*\" catches badly."})
        List<String> words,
        @Comment({
            "Java regular expressions, matched anywhere in the message as typed, ignoring case.",
            "For anything words can't express. Not smart."})
        List<String> patterns,
        Message messageBlocked) {

        public Filter {
            if (censorChar.codePointCount(0, censorChar.length()) != 1) {
                throw new IllegalArgumentException("censor-char must be one character");
            }
        }
    }

    public record Cooldown(
        boolean enabled,
        @Range(min = 0, max = 3600) double seconds,
        @Comment("<seconds> is the whole seconds left.") Message message) {}

    public record AntiRepeat(
        boolean enabled,
        @Comment("How long a message counts as recent.") @Range(min = 1, max = 3600) int seconds,
        @Comment({
            "How alike two messages must be, in percent, to count as a repeat. 100 catches",
            "only exact repeats; lower also catches small changes."})
        @Range(min = 50, max = 100) int similarity,
        Message message) {}

    public record Mentions(
        boolean enabled,
        @Comment("How a mention looks, to everyone. <name> is the mentioned player's name.") Message format,
        @Comment("Played to the mentioned player. Blank for none.") SoundEffect sound,
        @Comment({
            "Players with this permission can write @everyone to mention everyone. Blank lets",
            "nobody."})
        String everyonePermission) {}

    public record ClearChat(
        boolean enabled, List<String> aliases, String permission,
        @Comment("How many blank lines push chat out of view.") @Range(min = 1, max = 500) int lines,
        @Comment("<player> is who cleared it.") Message messageCleared) implements Toggle {}

    public record MuteChat(
        boolean enabled, List<String> aliases, String permission,
        @Comment("<player> is who muted it.") Message messageMuted,
        Message messageUnmuted,
        @Comment("Shown to a player who talks while chat is muted.") Message messageBlocked) implements Toggle {}

    public record ToggleChat(
        boolean enabled, List<String> aliases, String permission,
        @Comment("Keep a player's choice when they rejoin.") boolean remember,
        Message messageOn, Message messageOff) implements Toggle {}

    public record PrivateMessages(
        @Comment({
            "/msg <player> <message>. Replaces Minecraft's /msg, /tell and /w. <player> is the",
            "other player and <message> what was said."})
        Msg msg,
        @Comment("/reply <message>: answers whoever you last messaged, or who last messaged you.") Reply reply,
        @Comment("/socialspy: shows staff every private message. <sender> and <receiver> are filled in.") SocialSpy socialspy) {}

    public record Msg(
        boolean enabled, List<String> aliases, String permission,
        Message formatSent, Message formatReceived,
        @Comment("Played to whoever receives a message. Blank for none.") SoundEffect sound) implements Toggle {}

    public record Reply(
        boolean enabled, List<String> aliases, String permission,
        @Comment("Nobody has messaged you, or you them.") Message messageNobody) implements Toggle {}

    public record SocialSpy(
        boolean enabled, List<String> aliases, String permission,
        @Comment("Keep a player's choice when they rejoin.") boolean remember,
        @Comment("Also log every private message to the console.") boolean logToConsole,
        Message format, Message messageOn, Message messageOff) implements Toggle {}
}
