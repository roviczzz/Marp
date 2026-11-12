package me.roviczzz.marp.teleport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.model.LocationSnapshot;

/**
 * Seam between {@link TeleportService} and the Paper server API.
 *
 * <p>Tests provide an in-memory implementation so the full pipeline —
 * validation, cooldowns, safety, warmup, arrival, and failure rollback — can be
 * exercised without a server.
 */
public interface TeleportPlatform {

    record PlayerContext(
            UUID uuid,
            LocationSnapshot location,
            boolean bypassCooldown,
            boolean bypassWarmup,
            boolean bypassSafety) {}

    Optional<PlayerContext> player(UUID uuid);

    default boolean isPlayerDead(UUID uuid) {
        return false;
    }

    boolean isWorldLoaded(String worldName);

    CompletableFuture<SafetyResult> evaluateSafety(LocationSnapshot target, MarpConfig config);

    default CompletableFuture<Boolean> validateBedRespawn(UUID playerUuid, LocationSnapshot target) {
        return CompletableFuture.completedFuture(true);
    }

    void preparePlayerForArrival(UUID uuid, MarpConfig config);

    CompletableFuture<Boolean> teleportAsync(UUID uuid, LocationSnapshot target);

    void sendMessage(UUID uuid, String messageKey, Map<String, String> placeholders);

    void sendCountdown(UUID uuid, int seconds, MarpConfig config);

    void clearActionBar(UUID uuid);

    void playArrivalEffects(UUID uuid, MarpConfig config);

    void closeMarpMenuIfOpen(UUID uuid);
}
