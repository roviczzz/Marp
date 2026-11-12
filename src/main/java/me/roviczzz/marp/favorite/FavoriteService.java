package me.roviczzz.marp.favorite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.storage.repo.FavoriteRepository;

/** Coordinates favorite mutations, limits, caching, and pinned sorting. */
public final class FavoriteService {

    public sealed interface ToggleOutcome {
        record Added(FavoriteEntry entry) implements ToggleOutcome {}

        record Removed(FavoriteKind kind, long targetId) implements ToggleOutcome {}

        record LimitReached(int limit) implements ToggleOutcome {}

        record LimitZero() implements ToggleOutcome {}
    }

    private final FavoriteRepository repository;
    private final FavoriteCache cache;
    private final Supplier<MarpConfig> config;
    private final LongSupplier clock;

    public FavoriteService(
            FavoriteRepository repository,
            FavoriteCache cache,
            Supplier<MarpConfig> config,
            LongSupplier clock) {
        this.repository = repository;
        this.cache = cache;
        this.config = config;
        this.clock = clock;
    }

    public FavoriteCache cache() {
        return cache;
    }

    public CompletableFuture<List<FavoriteEntry>> loadCache(UUID player) {
        return repository.findByPlayer(player).thenApply(entries -> {
            cache.putAll(player, entries);
            return entries;
        });
    }

    public CompletableFuture<List<FavoriteEntry>> list(UUID player) {
        return loadCache(player);
    }

    public boolean isFavorite(UUID player, FavoriteKind kind, long targetId) {
        return cache.isFavorite(player, kind, targetId);
    }

    public CompletableFuture<ToggleOutcome> toggle(
            UUID player, FavoriteKind kind, long targetId, int limit) {
        return list(player).thenCompose(currentList -> {
            boolean currentlyFavorite = currentList.stream()
                    .anyMatch(e -> e.kind() == kind && e.targetId() == targetId);

            if (currentlyFavorite) {
                return repository.remove(player, kind, targetId).thenApply(removed -> {
                    if (removed) {
                        cache.remove(player, kind, targetId);
                    }
                    return new ToggleOutcome.Removed(kind, targetId);
                });
            }

            if (limit <= 0) {
                return CompletableFuture.completedFuture(new ToggleOutcome.LimitZero());
            }
            if (currentList.size() >= limit) {
                return CompletableFuture.completedFuture(new ToggleOutcome.LimitReached(limit));
            }

            long now = clock.getAsLong();
            return repository.add(player, kind, targetId, now).thenApply(added -> {
                cache.add(player, added);
                return new ToggleOutcome.Added(added);
            });
        });
    }

    public List<Home> sortHomesWithFavorites(UUID player, List<Home> allHomes) {
        if (allHomes == null || allHomes.isEmpty()) {
            return List.of();
        }
        List<FavoriteEntry> favs = cache.list(player).stream()
                .filter(e -> e.kind() == FavoriteKind.HOME)
                .toList();
        if (favs.isEmpty()) {
            return allHomes;
        }

        Map<Long, Home> homeById = allHomes.stream()
                .collect(Collectors.toMap(Home::id, h -> h, (a, b) -> a));
        List<Home> sorted = new ArrayList<>(allHomes.size());
        for (FavoriteEntry fav : favs) {
            Home match = homeById.remove(fav.targetId());
            if (match != null) {
                sorted.add(match);
            }
        }
        sorted.addAll(homeById.values());
        return List.copyOf(sorted);
    }

    public List<Warp> sortWarpsWithFavorites(UUID player, List<Warp> visibleWarps) {
        if (visibleWarps == null || visibleWarps.isEmpty()) {
            return List.of();
        }
        List<FavoriteEntry> favs = cache.list(player).stream()
                .filter(e -> e.kind() == FavoriteKind.WARP)
                .toList();
        if (favs.isEmpty()) {
            return visibleWarps;
        }

        Map<Long, Warp> warpById = visibleWarps.stream()
                .collect(Collectors.toMap(Warp::id, w -> w, (a, b) -> a));
        List<Warp> sorted = new ArrayList<>(visibleWarps.size());
        for (FavoriteEntry fav : favs) {
            Warp match = warpById.remove(fav.targetId());
            if (match != null) {
                sorted.add(match);
            }
        }
        sorted.addAll(warpById.values());
        return List.copyOf(sorted);
    }

    public Optional<Long> topFavoriteHomeId(UUID player) {
        return cache.list(player).stream()
                .filter(e -> e.kind() == FavoriteKind.HOME)
                .map(FavoriteEntry::targetId)
                .findFirst();
    }
}
