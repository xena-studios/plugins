package co.xenastudios.xplots.rent;

import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.config.XPConfig;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Plot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** A plot's details as signs, menus and messages show them. */
public record Info(String plot, String price, String period, String owner, String time, int members, int slots,
                   String size, String memberNames, String slotPrice) {

    /** The plot's details now. {@code none} is what an empty member list says. */
    public static Info of(Plot plot, XPConfig.PlotSettings settings, Wallet wallet, long now, String none) {
        Lease lease = plot.lease();
        return new Info(
            plot.id(),
            wallet.format(settings.price()),
            time(settings.period().toMillis()),
            lease == null ? "" : lease.ownerName(),
            lease == null ? "" : time(lease.remaining(now)),
            lease == null ? 0 : lease.members().size(),
            lease == null ? settings.startingSlots() : lease.slots(),
            plot.box().size(),
            lease == null || lease.members().isEmpty() ? none : String.join(", ", lease.members().values()),
            wallet.format(settings.slotPrice()));
    }

    /**
     * Placeholders for every detail, then any extra. MiniMessage lets a later placeholder
     * replace an earlier one of the same name, so an extra (a pay button's own price and
     * time) wins over the plot's.
     */
    public TagResolver[] placeholders(TagResolver... extra) {
        List<TagResolver> all = new ArrayList<>(List.of(
            Placeholders.text("plot", plot),
            Placeholders.text("price", price),
            Placeholders.text("period", period),
            Placeholders.text("owner", owner),
            Placeholders.text("time", time),
            Placeholders.text("slots", members),
            Placeholders.text("max-slots", slots),
            Placeholders.text("size", size),
            Placeholders.text("members", memberNames)));
        all.addAll(List.of(extra));
        return all.toArray(TagResolver[]::new);
    }

    /**
     * A length of time to the minute: {@code 2d 4h}, {@code 45m}, {@code <1m}. Signs show
     * this, so they only need rewriting once a minute.
     */
    public static String time(long millis) {
        long minutes = millis / 60_000;
        return minutes < 1 ? "<1m" : Durations.format(Duration.ofMinutes(minutes));
    }
}
