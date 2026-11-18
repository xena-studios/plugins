package co.xenastudios.xbans.api;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * One page of a player's or a staff member's history, newest first.
 *
 * @param punishments this page's punishments
 * @param page this page's number, from 1
 * @param pages how many pages there are, at least 1
 * @param total how many punishments there are on every page together
 * @since 1.0.0
 */
public record HistoryPage(@NotNull List<Punishment> punishments, int page, int pages, long total) {

    /**
     * Copies the list, so the page can't change.
     *
     * @param punishments this page's punishments
     * @param page this page's number
     * @param pages how many pages there are
     * @param total how many punishments there are
     * @since 1.0.0
     */
    public HistoryPage {
        punishments = List.copyOf(punishments);
    }
}
