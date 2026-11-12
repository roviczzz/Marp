package me.roviczzz.marp.back;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MarpConfig.UnreachableFallback;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.storage.repo.BackRepository;
import me.roviczzz.marp.teleport.TeleportReason;

/**
 * Records death points and pre-teleport origins, resolves the active back
 * target, and holds the session-scoped last-ground fallback.
 */
public final class BackService {

    public sealed interface FallbackDecision {
        record UseLastGround(LocationSnapshot location) implements FallbackDecision {}

        record NoGroundRecorded() implements FallbackDecision {}

        record Disabled() implements FallbackDecision {}
    }

    private final BackRepository repository;
    private final Supplier<MarpConfig> config;
    private final LongSupplier clock;
    private final Map<UUID, Map<BackKind, BackEntry>> memoryEntries = new ConcurrentHashMap<>();
    private final Map<UUID, LocationSnapshot> lastGround = new ConcurrentHashMap<>();

    public BackService(
            BackRepository repository,
            Supplier<MarpConfig> config,
            LongSupplier clock) {
        this.repository = repository;
        this.config = config;
        this.clock = clock;
    }

    public CompletableFuture<Void> recordDeath(UUID player, LocationSnapshot location, String cause) {
        MarpConfig cfg = config.get();
        if (!cfg.backEnabled() || !cfg.recordDeaths()) {
            return CompletableFuture.completedFuture(null);
        }
        BackEntry entry = new BackEntry(
                player, BackKind.DEATH, location, cause == null ? "DEATH" : cause, clock.getAsLong());
        putMemory(entry);
        return cfg.backPersist()
                ? repository.upsert(entry)
                : CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> recordTeleportOrigin(
            UUID player, LocationSnapshot origin, TeleportReason reason) {
        // /back must never record itself, or repeated use ping-pongs between two points.
        if (reason == TeleportReason.BACK) {
            return CompletableFuture.completedFuture(null);
        }
        MarpConfig cfg = config.get();
        if (!cfg.backEnabled() || !cfg.recordMarpTeleports()) {
            return CompletableFuture.completedFuture(null);
        }
        BackEntry entry = new BackEntry(
                player, BackKind.TELEPORT, origin, reason.name(), clock.getAsLong());
        putMemory(entry);
        return cfg.backPersist()
                ? repository.upsert(entry)
                : CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> recordExternalTeleportOrigin(UUID player, LocationSnapshot origin) {
        MarpConfig cfg = config.get();
        if (!cfg.backEnabled() || !cfg.recordExternalTeleports()) {
            return CompletableFuture.completedFuture(null);
        }
        BackEntry entry = new BackEntry(
                player, BackKind.TELEPORT, origin, "EXTERNAL", clock.getAsLong());
        putMemory(entry);
        return cfg.backPersist()
                ? repository.upsert(entry)
                : CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Optional<BackEntry>> resolve(UUID player) {
        MarpConfig cfg = config.get();
        if (!cfg.backPersist()) {
            Map<BackKind, BackEntry> map = memoryEntries.get(player);
            if (map == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            synchronized (map) {
                return CompletableFuture.completedFuture(BackResolver.resolve(
                        map.get(BackKind.DEATH), map.get(BackKind.TELEPORT), cfg.backPreference()));
            }
        }
        return repository.findAll(player).thenApply(entries -> {
            BackEntry death = null;
            BackEntry teleport = null;
            for (BackEntry entry : entries) {
                if (entry.kind() == BackKind.DEATH) {
                    death = entry;
                } else if (entry.kind() == BackKind.TELEPORT) {
                    teleport = entry;
                }
            }
            return BackResolver.resolve(death, teleport, cfg.backPreference());
        });
    }

    public CompletableFuture<Void> consumeIfConfigured(UUID player, BackKind kind) {
        MarpConfig cfg = config.get();
        if (!cfg.backConsume()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<BackKind, BackEntry> map = memoryEntries.get(player);
        if (map != null) {
            synchronized (map) {
                map.remove(kind);
            }
        }
        return cfg.backPersist()
                ? repository.delete(player, kind).thenApply(ignored -> null)
                : CompletableFuture.completedFuture(null);
    }

    public void recordLastGround(UUID player, LocationSnapshot location) {
        lastGround.put(player, location);
    }

    public Optional<LocationSnapshot> lastGround(UUID player) {
        return Optional.ofNullable(lastGround.get(player));
    }

    public FallbackDecision resolveUnreachableFallback(UUID player) {
        if (config.get().unreachableFallback() == UnreachableFallback.BLOCK) {
            return new FallbackDecision.Disabled();
        }
        LocationSnapshot ground = lastGround.get(player);
        return ground != null
                ? new FallbackDecision.UseLastGround(ground)
                : new FallbackDecision.NoGroundRecorded();
    }

    public void clearPlayerTransient(UUID player) {
        lastGround.remove(player);
        if (!config.get().backPersist()) {
            memoryEntries.remove(player);
        }
    }

    private void putMemory(BackEntry entry) {
        Map<BackKind, BackEntry> map = memoryEntries.computeIfAbsent(
                entry.player(), ignored -> new EnumMap<>(BackKind.class));
        synchronized (map) {
            map.put(entry.kind(), entry);
        }
    }
}
