package co.xenastudios.xlimbo.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.config.Codec;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Theme;
import co.xenastudios.xlimbo.config.Settings.ActionBar;
import co.xenastudios.xlimbo.config.Settings.AutoJoin;
import co.xenastudios.xlimbo.config.Settings.Border;
import co.xenastudios.xlimbo.config.Settings.BuildGuard;
import co.xenastudios.xlimbo.config.Settings.Death;
import co.xenastudios.xlimbo.config.Settings.Entities;
import co.xenastudios.xlimbo.config.Settings.Join;
import co.xenastudios.xlimbo.config.Settings.JoinCommand;
import co.xenastudios.xlimbo.config.Settings.Player;
import co.xenastudios.xlimbo.config.Settings.Protections;
import co.xenastudios.xlimbo.config.Settings.Queue;
import co.xenastudios.xlimbo.config.Settings.Quit;
import co.xenastudios.xlimbo.config.Settings.ServerCheck;
import co.xenastudios.xlimbo.config.Settings.Void;
import co.xenastudios.xlimbo.config.Settings.World;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;

/** xLimbo's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XLConfig(
    @Comment({
        "The look xLimbo's messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#B48CFF>, or <gold><bold>."})
    Theme theme,
    @Comment("Messages the commands share.") Messages messages,
    @Comment({
        "The void world players wait in: one floor layer, nothing else. Changing its name,",
        "floor-block or floor-y rebuilds it on /xlimbo reload; players are moved out and back."})
    World world,
    @Comment("What arriving players get.") Player player,
    Join join,
    Quit quit,
    Death death,
    @Comment({
        "The server /join and automatic returns send players to: its name in your proxy's",
        "config, such as \"main\"."})
    String mainServer,
    @Comment("Send players back on their own this long after they arrive.") AutoJoin autoJoin,
    @Comment({
        "Watch the main server, and send everyone back as soon as it's up, instead of waiting",
        "for /join or auto-join."})
    ServerCheck serverCheck,
    @Comment("How automatic returns are paced.") Queue queue,
    @Comment("/join: sends you to main-server. Everyone has it by default.") JoinCommand joinCommand,
    @Comment("A hint repeated on every player's action bar.") ActionBar actionBar,
    @Comment({
        "Players here are in creative, so these stop them breaking, lagging or escaping the",
        "server. Each can be turned off; one that's off costs nothing."})
    Protections protections,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(ErrorMessages error) {}

    /** Blocks as Minecraft names them: {@code glass}, {@code white_stained_glass}. */
    static final Codec<Material> MATERIAL = new Codec<>() {
        @Override
        public Material decode(Object raw) {
            Material material = Material.matchMaterial(raw.toString().trim());
            if (material == null) {
                throw new IllegalArgumentException("isn't a block, such as glass or white_stained_glass");
            }
            return material;
        }

        @Override
        public Object encode(Material value) {
            return value.name().toLowerCase(Locale.ROOT);
        }
    };

    public static final XLConfig DEFAULTS = new XLConfig(
        Theme.DEFAULTS,
        new Messages(ErrorMessages.DEFAULTS),
        new World("xlimbo", Material.PURPLE_STAINED_GLASS, 64, false, false, new Border(10_000, 64), 4, 4),
        new Player(true, true, true),
        new Join(true, Message.of("<prefix>The server is unavailable right now, so you're waiting here. Type <highlight>/join</highlight> to go back."), false),
        new Quit(true),
        new Death(true),
        "main",
        new AutoJoin(false, 30, Message.of("<prefix>Sending you back to the server...")),
        new ServerCheck(false, "127.0.0.1:25566", 5, Message.of("<prefix><success>The server is back up!</success> Sending everyone back...")),
        new Queue(5, Message.of("<gray>Sending you back: <highlight>#<position></highlight> of <total> in line")),
        new JoinCommand(true, List.of("server", "lobby"), "xlimbo.join", 3,
            Message.of("<prefix>Connecting you to <highlight><server></highlight>..."),
            Message.of("<prefix><error>Wait <highlight><seconds>s</highlight> before trying again.")),
        new ActionBar(true, 40, Message.of("<gray>Type <highlight>/join</highlight> to go back to the server")),
        new Protections(true, new Void(true, 0, true), true, true, true, true, true, true, true, true,
            new Entities(true, 300), new BuildGuard(false, 30)),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XLConfig> SPEC = ConfigSpec.builder(XLConfig.class, DEFAULTS)
        .header(
            "xLimbo: never drop a player again.",
            "Docs: https://docs.xenastudios.co/xlimbo/configuration/",
            "",
            "Apply changes with /xlimbo reload. Everything applies, even the world's name and",
            "floor. A bad value falls back to its default with a warning in the console; xLimbo",
            "never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(2)
        .codec(Material.class, MATERIAL)
        // 1.x spread arrivals up to 4000 blocks apart: a fresh chunk for nearly every player.
        .migrate(1, root -> {
            if (root.getInt("world.border.spawn-radius") == 4000) {
                root.set("world.border.spawn-radius", 64);
            }
            // 1.x read 0 as "unlimited"; 2.0 says so with enabled: false.
            if (root.contains("protections.build-guard.max-places-per-second") && root.getInt("protections.build-guard.max-places-per-second") == 0) {
                root.set("protections.build-guard.enabled", false);
                root.set("protections.build-guard.max-places-per-second", 30);
            }
        })
        .apply(XLConfig::formerDefaults)
        .build();

    // 1.x's messages. A carried-forward file still using one gets 2.0's instead.
    private static ConfigSpec.Builder<XLConfig> formerDefaults(ConfigSpec.Builder<XLConfig> spec) {
        return spec
            .formerDefault("world.floor-block", "GLASS")
            .formerDefault("join.custom-join-message",
                "<gray>Welcome to <aqua>xLimbo</aqua>. Type <yellow>/join</yellow> to return to the server.</gray>")
            .formerDefault("auto-join.message", "<green>Sending you to the server...</green>")
            .formerDefault("join-command.message-sending", "<green>Connecting you to <aqua><server></aqua>...</green>")
            .formerDefault("join-command.message-cooldown", "<red>Please wait <yellow><seconds></yellow>s before trying again.</red>")
            .formerDefault("action-bar.message", "<gray>Use <yellow>/join</yellow> to join the server!</gray>");
    }
}
