package me.roviczzz.marp.model;

import java.util.List;

/**
 * An immutable, clamped window over a list.
 *
 * <p>{@code index} is zero-based; navigation labels add one. Both {@code start}
 * and {@code end} are safe sublist bounds even when the list is empty.
 */
public record Page(int index, int pages, int pageSize, int total, int start, int end) {

    public static Page of(int requestedIndex, int total, int pageSize) {
        int size = Math.max(1, pageSize);
        int safeTotal = Math.max(0, total);
        int pageCount = Math.max(1, (safeTotal + size - 1) / size);
        int clamped = Math.max(0, Math.min(requestedIndex, pageCount - 1));
        int from = clamped * size;
        int to = Math.min(from + size, safeTotal);
        return new Page(clamped, pageCount, size, safeTotal, from, to);
    }

    public int number() {
        return index + 1;
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return index < pages - 1;
    }

    public <T> List<T> slice(List<T> source) {
        if (source.isEmpty() || start >= source.size()) {
            return List.of();
        }
        return List.copyOf(source.subList(start, Math.min(end, source.size())));
    }
}
