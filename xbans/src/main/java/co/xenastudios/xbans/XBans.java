package co.xenastudios.xbans;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.xbans.command.ImportCommand;
import co.xenastudios.xbans.command.IpCommands;
import co.xenastudios.xbans.command.LookupCommands;
import co.xenastudios.xbans.command.PunishCommands;
import co.xenastudios.xbans.command.RevokeCommands;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.enforce.Gate;
import co.xenastudios.xbans.enforce.MuteGuard;
import co.xenastudios.xbans.enforce.Sessions;
import co.xenastudios.xbans.ip.Alts;
import co.xenastudios.xbans.lookup.Cursors;
import co.xenastudios.xbans.lookup.Texts;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.network.Network;
import co.xenastudios.xbans.storage.Store;
import net.kyori.adventure.text.Component;
import org.bukkit.permissions.PermissionDefault;

/** xBans: bans, mutes and warnings, the LiteBans way. Not final: MockBukkit subclasses it in tests. */
public class XBans extends XenaPlugin<XBConfig> {

    private Store store;
    private Sessions sessions;
    private Texts texts;
    private Punisher punisher;
    private Profiles profiles;
    private Cursors cursors;
    private Network network;
    private Alts alts;
    private Api api;
    private co.xenastudios.xbans.importer.Imports imports;

    @Override
    protected ConfigSpec<XBConfig> configSpec() {
        return XBConfig.SPEC;
    }

    @Override
    protected void setup(Setup setup, XBConfig config) {
        if (store == null) {
            sessions = new Sessions();
            texts = new Texts(this::config);
            punisher = new Punisher(this);
            profiles = new Profiles(tasks());
            network = new Network(this);
            store = new Store(tasks(), getLogger(), getDataFolder().toPath(), backend -> {
                network.subscribe(backend);
                if (cursors != null) {
                    cursors.clear();
                }
            });
            cursors = new Cursors(store);
            alts = new Alts(this);
            api = new Api(this);
            imports = new co.xenastudios.xbans.importer.Imports(this);
        }
        store.origin(config.scopes().serverName());
        store.sync(new Store.Wanted(config.storage(), config.network(), config.ips().hash()));

        // Enforcement can't be turned off: it's what the plugin is for.
        setup.module("enforce.login", () -> new Gate(this));
        setup.module("enforce.chat", () -> new MuteGuard.Chat(this));
        setup.module("enforce.legacy-chat", () -> new MuteGuard.LegacyChat(this));
        if (config.mutes().blockSigns()) {
            setup.module("enforce.signs", () -> new MuteGuard.Signs(this));
        }
        if (config.mutes().blockBooks()) {
            setup.module("enforce.books", () -> new MuteGuard.Books(this));
        }
        if (config.mutes().blockAnvils()) {
            setup.module("enforce.anvils", () -> new MuteGuard.Anvils(this));
        }
        setup.module("api", () -> api);
        setup.permission(Gate.NOTIFY_BANNED_JOIN, PermissionDefault.OP);

        PunishCommands.register(setup, this, config);
        RevokeCommands.register(setup, this, config);
        LookupCommands.register(setup, this, config);
        IpCommands.register(setup, this, config);
        setup.subcommand(ImportCommand.spec(this));
    }

    @Override
    protected void shutdown() {
        if (store != null) {
            store.close();
        }
    }

    /** What a banned player sees: the ban, temporary ban or IP ban screen. */
    public Component screen(Entry ban, long now) {
        XBConfig.Screens screens = config().screens();
        var message = ban.punishment().ipBased() ? screens.ipBan()
            : ban.punishment().isPermanent() ? screens.ban() : screens.tempBan();
        return message.render(texts.of(ban, now));
    }

    public Store store() {
        return store;
    }

    public Sessions sessions() {
        return sessions;
    }

    public Texts texts() {
        return texts;
    }

    public Punisher punisher() {
        return punisher;
    }

    public Profiles profiles() {
        return profiles;
    }

    public Cursors cursors() {
        return cursors;
    }

    public co.xenastudios.xbans.importer.Imports imports() {
        return imports;
    }

    public Alts alts() {
        return alts;
    }
}
