package co.xenastudios.xmoney.api;

import java.util.List;

/**
 * One page of a player's history, newest first.
 *
 * @param entries the changes on this page
 * @param page this page, from 1
 * @param pages how many pages there are, at least 1
 * @param total how many changes there are on every page together
 * @since 1.0.0
 */
public record HistoryPage(List<HistoryEntry> entries, int page, int pages, long total) {}
