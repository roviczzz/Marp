package me.roviczzz.marp.favorite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;

/** Per-player in-memory cache of ordered favorite entries. */
public final class FavoriteCache {

    private final Map<UUID, List<FavoriteEntry>> byPlayer = new ConcurrentHashMap<>();

    public boolean isLoaded(UUID player) {
        return byPlayer.containsKey(player);
    }

    public List<FavoriteEntry> list(UUID player) {
        List<FavoriteEntry> entries = byPlayer.get(player);
        return entries == null ? List.of() : entries;
    }

    public boolean isFavorite(UUID player, FavoriteKind kind, long targetId) {
        for (FavoriteEntry entry : list(player)) {
            if (entry.kind() == kind && entry.targetId() == targetId) {
                return true;
            }
        }
        return false;
    }

    public void putAll(UUID player, Collection<FavoriteEntry> entries) {
        byPlayer.put(player, List.copyOf(entries));
    }

    public void add(UUID player, FavoriteEntry entry) {
        byPlayer.compute(player, (ignored, current) -> {
            List<FavoriteEntry> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.removeIf(e -> e.kind() == entry.kind() && e.targetId() == entry.targetId());
            next.add(entry);
            return List.copyOf(next);
        });
    }

    public void remove(UUID player, FavoriteKind kind, long targetId) {
        byPlayer.computeIfPresent(player, (ignored, current) -> {
            List<FavoriteEntry> next = new ArrayList<>(current.size());
            for (FavoriteEntry entry : current) {
                if (!(entry.kind() == kind && entry.targetId() == targetId)) {
                    next.add(entry);
                }
            }
            return List.copyOf(next);
        });
    }

    public void removeTargetEverywhere(FavoriteKind kind, long targetId) {
        for (UUID player : byPlayer.keySet()) {
            remove(player, kind, targetId);
        }
    }

    public void clear(UUID player) {
        byPlayer.remove(player);
    }

    public void clearAll() {
        byPlayer.clear();
    }
}
