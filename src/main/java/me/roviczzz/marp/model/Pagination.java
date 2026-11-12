package me.roviczzz.marp.model;

/** Pure page math shared by every list menu. */
public final class Pagination {

    public static final int MIN_ROWS = 3;
    public static final int MAX_ROWS = 6;
    private static final int COLUMNS = 9;
    private static final int NAV_ROWS = 1;

    private Pagination() {
    }

    /** Content slots per page for a chest with {@code rows} rows and one navigation row. */
    public static int pageSize(int rows) {
        int clamped = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        return (clamped - NAV_ROWS) * COLUMNS;
    }

    public static Page page(int requestedIndex, int total, int rows) {
        return Page.of(requestedIndex, total, pageSize(rows));
    }

    /** Page index to show after a deletion: step back when the current page became empty. */
    public static int pageAfterDelete(int currentIndex, int remainingTotal, int rows) {
        Page page = page(currentIndex, remainingTotal, rows);
        if (currentIndex > 0 && page.start() >= Math.max(0, remainingTotal)) {
            return currentIndex - 1;
        }
        return page.index();
    }
}
