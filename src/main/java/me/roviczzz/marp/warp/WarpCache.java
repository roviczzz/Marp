package me.roviczzz.marp.warp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import me.roviczzz.marp.model.Warp;

/**
 * In-memory snapshot of all warps, used by the warps menu and tab completion.
 *
 * <p>A cold cache returns empty suggestions immediately rather than blocking on
 * the database.
 */
public final class WarpCache {

    private static final Comparator<Warp> ORDER = Comparator
            .comparing((Warp w) -> w.nameKey().toLowerCase(Locale.ROOT))
            .thenComparingLong(Warp::id);

    private volatile List<Warp> warps = List.of();
    private volatile boolean loaded;

    public boolean isLoaded() {
        return loaded;
    }

    public List<Warp> all() {
        return loaded ? warps : List.of();
    }

    public Optional<Warp> findByKey(String nameKey) {
        if (!loaded || nameKey == null) {
            return Optional.empty();
        }
        String key = nameKey.toLowerCase(Locale.ROOT);
        for (Warp warp : warps) {
            if (warp.nameKey().equalsIgnoreCase(key)) {
                return Optional.of(warp);
            }
        }
        return Optional.empty();
    }

    public Optional<Warp> findById(long id) {
        if (!loaded) {
            return Optional.empty();
        }
        for (Warp warp : warps) {
            if (warp.id() == id) {
                return Optional.of(warp);
            }
        }
        return Optional.empty();
    }

    public synchronized void replaceAll(Collection<Warp> entries) {
        List<Warp> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);
        this.warps = List.copyOf(sorted);
        this.loaded = true;
    }

    public synchronized void put(Warp warp) {
        List<Warp> updated = new ArrayList<>();
        for (Warp existing : warps) {
            if (existing.id() != warp.id() && !existing.nameKey().equalsIgnoreCase(warp.nameKey())) {
                updated.add(existing);
            }
        }
        updated.add(warp);
        updated.sort(ORDER);
        this.warps = List.copyOf(updated);
        this.loaded = true;
    }

    public synchronized void remove(long id) {
        List<Warp> updated = new ArrayList<>();
        for (Warp existing : warps) {
            if (existing.id() != id) {
                updated.add(existing);
            }
        }
        this.warps = List.copyOf(updated);
    }

    public synchronized void clear() {
        this.warps = List.of();
        this.loaded = false;
    }

    public List<String> suggestVisible(String rawPrefix, Predicate<Warp> canAccess, int maxResults) {
        if (!loaded || maxResults <= 0) {
            return List.of();
        }
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Warp warp : warps) {
            if (warp.nameKey().startsWith(prefix) && canAccess.test(warp)) {
                matches.add(warp.nameKey());
                if (matches.size() >= maxResults) {
                    break;
                }
            }
        }
        return matches;
    }

    public List<String> suggestAll(String rawPrefix, int maxResults) {
        return suggestVisible(rawPrefix, ignored -> true, maxResults);
    }
}
