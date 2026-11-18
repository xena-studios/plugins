package co.xenastudios.xbans.lookup;

import co.xenastudios.core.text.ChatPages;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pages of a long history by key range rather than by offset. Each list someone pages
 * through remembers where each page it showed ended, so "Next page" starts below the last
 * id shown: a single index range, as quick on page 300 as on page 1, and steady while new
 * punishments arrive. A page jumped to directly finds its start from the index alone.
 */
public final class Cursors {

    static final int KEEP = 500;

    // For each list (viewer and filter), the id each page starts below.
    private final Map<String, Map<Integer, Long>> starts = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<Integer, Long>> eldest) {
            return size() > KEEP;
        }
    });

    private final Store store;

    public Cursors(Store store) {
        this.store = store;
    }

    /**
     * One page of a list.
     *
     * @param viewer who's paging, so two staff paging the same list don't share cursors
     */
    public CompletableFuture<ChatPages.Page<Entry>> page(String viewer, Store.Filter filter, int page, int perPage) {
        String key = viewer + "|" + filter;
        int number = Math.max(1, page);
        return store.read((c, b) -> {
            long total = Store.count(c, b, filter);
            int pages = ChatPages.pages(total, perPage);
            int shown = Math.min(number, pages);
            Long start = startOf(key, shown);
            if (start == null) {
                start = Store.boundary(c, b, filter, ChatPages.offset(shown, perPage));
            }
            List<Entry> rows = start == Long.MIN_VALUE ? List.of() : Store.page(c, b, filter, start, perPage);
            remember(key, shown, start, rows);
            return ChatPages.of(rows, total, shown, perPage);
        });
    }

    private Long startOf(String key, int page) {
        if (page == 1) {
            return Long.MAX_VALUE;
        }
        Map<Integer, Long> known = starts.get(key);
        synchronized (starts) {
            return known == null ? null : known.get(page);
        }
    }

    private void remember(String key, int page, long start, List<Entry> rows) {
        synchronized (starts) {
            Map<Integer, Long> known = starts.computeIfAbsent(key, k -> new java.util.HashMap<>());
            known.put(page, start);
            if (!rows.isEmpty()) {
                known.put(page + 1, rows.get(rows.size() - 1).id());
            }
        }
    }

    /** Thrown out when the database changes under it, so cursors from one database never reach another. */
    public void clear() {
        starts.clear();
    }

    // For tests.
    int remembered() {
        return starts.size();
    }

}
