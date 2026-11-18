package co.xenastudios.zchat;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.zchat.chat.ChatFilter;
import co.xenastudios.zchat.chat.ChatListener;
import co.xenastudios.zchat.chat.ChatState;
import co.xenastudios.zchat.chat.Formats;
import co.xenastudios.zchat.chat.MuteProviders;
import co.xenastudios.zchat.chat.PapiWatch;
import co.xenastudios.zchat.command.ChatCommands;
import co.xenastudios.zchat.command.FilterCommands;
import co.xenastudios.zchat.command.MessageCommands;
import co.xenastudios.zchat.config.Settings;
import co.xenastudios.zchat.config.ZConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** zChat: format your chat. Keep it clean. Not final: MockBukkit subclasses it in tests. */
public class ZChat extends XenaPlugin<ZConfig> {

    /** What chat needs from the config, worked out once per load rather than per message. */
    public record Compiled(Formats formats, ChatFilter filter) {}

    private final ChatState state = new ChatState();
    private volatile Compiled compiled;
    private NamespacedKey hiddenChatKey;
    private NamespacedKey socialSpyKey;
    private MuteProviders mutes;
    private Api api;

    @Override
    protected ConfigSpec<ZConfig> configSpec() {
        return ZConfig.SPEC;
    }

    @Override
    protected List<String> adminAliases() {
        return List.of("zc");
    }

    @Override
    protected int bStatsId() {
        return 21533;
    }

    @Override
    protected void setup(Setup setup, ZConfig config) {
        if (hiddenChatKey == null) {
            hiddenChatKey = new NamespacedKey(this, "chat_hidden");
            socialSpyKey = new NamespacedKey(this, "social_spy");
            mutes = new MuteProviders(getLogger());
            api = new Api(this);
            // Anyone already online, such as after the plugin is loaded on a running server.
            for (Player player : Bukkit.getOnlinePlayers()) {
                state.joined(player);
                tasks().entity(player, () -> ChatListener.restore(this, player));
            }
        }
        // The developer API, first so it's there even if a later feature fails to set up.
        setup.module("api", () -> api);
        if (!config.mutechat().enabled() && state.muted()) {
            // Turning /mutechat off would otherwise leave chat muted with no command to undo it.
            state.muted(false);
            getLogger().info("mutechat is off, so chat is unmuted.");
        }
        List<String> warnings = new ArrayList<>();
        compiled = new Compiled(Formats.compile(config.formatting()), ChatFilter.compile(config.filter(), warnings));
        warnings.forEach(warning -> getLogger().warning("config.yml: " + warning));
        Formats.refreshPapi();
        if (compiled.formats().enabled() && compiled.formats().usesPlaceholderApi()) {
            if (!Formats.papiInstalled()) {
                getLogger().warning("config.yml: a chat format uses %placeholders%, but PlaceholderAPI isn't installed, so they show nothing.");
            }
            // Only while a format needs it: notices PlaceholderAPI being enabled or disabled later.
            setup.module("papi-watch", () -> new PapiWatch(this));
        }

        setup.module("chat", () -> new ChatListener(this));
        declarePermissions(setup, config);
        ChatCommands.register(setup, this, config);
        MessageCommands.register(setup, this, config);
        FilterCommands.register(setup, this, config);
    }

    private static void declarePermissions(Setup setup, ZConfig config) {
        setup.permission(ChatListener.BYPASS_MUTE, PermissionDefault.OP);
        setup.permission(ChatListener.BYPASS_COOLDOWN, PermissionDefault.OP);
        setup.permission(ChatListener.BYPASS_FILTER, PermissionDefault.OP);
        setup.permission(ChatListener.BYPASS_ANTI_REPEAT, PermissionDefault.OP);
        setup.permission(config.formatting().colorPermission(), PermissionDefault.OP);
        setup.permission(config.mentions().everyonePermission(), PermissionDefault.OP);
        // Groups are given out on purpose, so operators don't all get the admin format.
        for (Map.Entry<String, Settings.Group> group : config.formatting().groups().entrySet()) {
            setup.permission(Formats.permission(group.getKey(), group.getValue()), PermissionDefault.FALSE);
        }
    }

    /** The chat state: the mute, hidden chat, cooldowns and reply targets. */
    public ChatState state() {
        return state;
    }

    /** Other plugins' mute providers, registered through the API. */
    public MuteProviders mutes() {
        return mutes;
    }

    public Compiled compiled() {
        return compiled;
    }

    public NamespacedKey hiddenChatKey() {
        return hiddenChatKey;
    }

    public NamespacedKey socialSpyKey() {
        return socialSpyKey;
    }
}
