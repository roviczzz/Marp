package me.roviczzz.marp.settings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import me.roviczzz.marp.model.PlayerSettings;
import me.roviczzz.marp.storage.repo.PlayerSettingsRepository;

/**
 * Manages per-player toggle preferences with a non-blocking in-memory cache
 * backed by asynchronous SQLite persistence.
 */
public final class PlayerSettingsService {

    private final PlayerSettingsRepository repository;
    private final LongSupplier clock;
    private final Map<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();

    public PlayerSettingsService(PlayerSettingsRepository repository, LongSupplier clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CompletableFuture<PlayerSettings> load(UUID player) {
        return repository.find(player).thenApply(opt -> {
            PlayerSettings resolved = opt.orElseGet(() -> PlayerSettings.defaults(player));
            cache.put(player, resolved);
            return resolved;
        });
    }

    public boolean isLoaded(UUID player) {
        return cache.containsKey(player);
    }

    public PlayerSettings current(UUID player) {
        return cache.getOrDefault(player, PlayerSettings.defaults(player));
    }

    public boolean isTpaDisabled(UUID player) {
        return current(player).tpaDisabled();
    }

    public boolean isBedHomeDisabled(UUID player) {
        return current(player).bedHomeDisabled();
    }

    public CompletableFuture<PlayerSettings> setTpaDisabled(UUID player, boolean disabled) {
        return resolveBase(player).thenCompose(base -> {
            PlayerSettings updated = base.withTpaDisabled(disabled, clock.getAsLong());
            cache.put(player, updated);
            return repository.upsert(updated).thenApply(ignored -> updated);
        });
    }

    public CompletableFuture<PlayerSettings> toggleTpa(UUID player) {
        return resolveBase(player).thenCompose(base -> {
            PlayerSettings updated = base.withTpaDisabled(!base.tpaDisabled(), clock.getAsLong());
            cache.put(player, updated);
            return repository.upsert(updated).thenApply(ignored -> updated);
        });
    }

    public CompletableFuture<PlayerSettings> setBedHomeDisabled(UUID player, boolean disabled) {
        return resolveBase(player).thenCompose(base -> {
            PlayerSettings updated = base.withBedHomeDisabled(disabled, clock.getAsLong());
            cache.put(player, updated);
            return repository.upsert(updated).thenApply(ignored -> updated);
        });
    }

    public CompletableFuture<PlayerSettings> toggleBedHome(UUID player) {
        return resolveBase(player).thenCompose(base -> {
            PlayerSettings updated = base.withBedHomeDisabled(!base.bedHomeDisabled(), clock.getAsLong());
            cache.put(player, updated);
            return repository.upsert(updated).thenApply(ignored -> updated);
        });
    }

    public void clear(UUID player) {
        cache.remove(player);
    }

    public void clearAll() {
        cache.clear();
    }

    private CompletableFuture<PlayerSettings> resolveBase(UUID player) {
        PlayerSettings cached = cache.get(player);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return load(player);
    }
}
