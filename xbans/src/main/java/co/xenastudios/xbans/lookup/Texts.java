package co.xenastudios.xbans.lookup;

import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Entry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** The placeholders punishments fill messages with, from the live config. */
public final class Texts {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Supplier<XBConfig> config;
    private volatile Formatter formatter;

    private record Formatter(String pattern, DateTimeFormatter format) {}

    public Texts(Supplier<XBConfig> config) {
        this.config = config;
    }

    public String date(Instant at) {
        String pattern = config.get().lists().dateFormat();
        Formatter current = formatter;
        if (current == null || !current.pattern().equals(pattern)) {
            DateTimeFormatter format;
            try {
                format = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault());
            } catch (IllegalArgumentException e) {
                format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
            }
            current = new Formatter(pattern, format);
            formatter = current;
        }
        return current.format().format(at);
    }

    /** How long a punishment lasts: "7d", or the config's word for permanent. */
    public String length(Punishment p) {
        if (p.expires() == null) {
            return "permanent";
        }
        return Durations.format(Duration.between(p.created(), p.expires()));
    }

    /** "for 7d" or "permanently", for broadcasts. */
    public String forDuration(Punishment p) {
        XBConfig.Broadcasts words = config.get().broadcasts();
        if (p.expires() == null) {
            return words.permanently();
        }
        return words.forTime().replace("<time>", Durations.format(Duration.between(p.created(), p.expires())));
    }

    public String typeWord(Punishment p) {
        XBConfig.TypeWords words = config.get().messages().types();
        return switch (p.type()) {
            case BAN -> p.ipBased() ? words.ipBan() : words.ban();
            case MUTE -> p.ipBased() ? words.ipMute() : words.mute();
            case WARN -> words.warn();
            case KICK -> words.kick();
        };
    }

    public String status(Entry entry, long now) {
        XBConfig.StatusWords words = config.get().messages().status();
        Punishment p = entry.punishment();
        if (!p.type().lasts()) {
            return words.kick();
        }
        if (p.removal() != null) {
            return words.revoked().replace("<staff>", MINI.escapeTags(p.removal().staffName()));
        }
        if (entry.active(now)) {
            long left = entry.left(now);
            return words.active().replace("<time>", left < 0 ? "permanent" : Durations.format(Duration.ofMillis(left)));
        }
        return words.ended();
    }

    /** Every placeholder a punishment fills: for screens, broadcasts and list lines. */
    public TagResolver of(Entry entry, long now) {
        Punishment p = entry.punishment();
        long left = entry.left(now);
        XBConfig current = config.get();
        return TagResolver.resolver(
            Placeholders.text("id", p.id()),
            Placeholders.text("player", p.targetName()),
            Placeholders.text("staff", p.staffName()),
            Placeholders.text("reason", p.reason()),
            Placeholders.text("scope", p.isGlobal() ? "global" : p.scope()),
            Placeholders.text("date", date(p.created())),
            Placeholders.text("expires", p.expires() == null ? "never" : date(p.expires())),
            Placeholders.text("time-left", left < 0 ? "forever" : Durations.format(Duration.ofMillis(left))),
            Placeholders.text("length", length(p)),
            Placeholders.text("duration", forDuration(p)),
            Placeholders.text("appeal", current.screens().appealLink()),
            // Words from the config are MiniMessage, so they're parsed, not inserted as text.
            TagResolver.resolver("type", Tag.selfClosingInserting(MINI.deserialize(typeWord(p)))),
            TagResolver.resolver("status", Tag.selfClosingInserting(MINI.deserialize(status(entry, now)))));
    }
}
