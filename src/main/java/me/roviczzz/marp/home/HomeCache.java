package me.roviczzz.marp.home;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player in-memory cache of home {@code name_key}s used for tab completion.
 *
 * <p>Completion runs on the main thread and must never wait on the database; a
 * cold lookup returns an empty list immediately.
 */
public final class HomeCache {

    private final Map<UUID, Set<String>> byPlayer = new ConcurrentHashMap<>();

    public boolean isLoaded(UUID player) {
        return byPlayer.containsKey(player);
    }

    public List<String> names(UUID player) {
        Set<String> names = byPlayer.get(player);
        if (names == null) {
            return List.of();
        }
        synchronized (names) {
            return List.copyOf(names);
        }
    }

    public void putAll(UUID player, Collection<String> nameKeys) {
        Set<String> sorted = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        for (String key : nameKeys) {
            if (key != null && !key.isBlank()) {
                sorted.add(key.toLowerCase(Locale.ROOT));
            }
        }
        byPlayer.put(player, sorted);
    }

    public void add(UUID player, String nameKey) {
        if (nameKey == null || nameKey.isBlank()) {
            return;
        }
        Set<String> names = byPlayer.computeIfAbsent(
                player, ignored -> Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
        names.add(nameKey.toLowerCase(Locale.ROOT));
    }

    public void remove(UUID player, String nameKey) {
        if (nameKey == null) {
            return;
        }
        Set<String> names = byPlayer.get(player);
        if (names != null) {
            names.remove(nameKey.toLowerCase(Locale.ROOT));
        }
    }

    public void clear(UUID player) {
        byPlayer.remove(player);
    }

    public void clearAll() {
        byPlayer.clear();
    }

    public int cachedPlayers() {
        return byPlayer.size();
    }

    public List<String> suggest(UUID player, String rawPrefix, int maxResults) {
        List<String> snapshot = names(player);
        if (snapshot.isEmpty() || maxResults <= 0) {
            return List.of();
        }
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String name : snapshot) {
            if (name.startsWith(prefix)) {
                matches.add(name);
                if (matches.size() >= maxResults) {
                    break;
                }
            }
        }
        return matches;
    }
}
