package me.roviczzz.marp.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory, per-player, per-action cooldowns.
 *
 * <p>The clock is injectable so tests are deterministic. Cooldowns start on a
 * successful arrival, never on command use, and a bypass skips both the check
 * and the start.
 */
public final class CooldownService {

    private final LongSupplier clock;
    private final Map<String, Long> expiresAt = new ConcurrentHashMap<>();

    public CooldownService(LongSupplier clock) {
        this.clock = clock;
    }

    public long remainingMillis(UUID player, TeleportReason reason, boolean bypass) {
        if (bypass) {
            return 0L;
        }
        Long expiry = expiresAt.get(key(player, reason));
        if (expiry == null) {
            return 0L;
        }
        long remaining = expiry - clock.getAsLong();
        if (remaining <= 0) {
            expiresAt.remove(key(player, reason));
            return 0L;
        }
        return remaining;
    }

    public long remainingSeconds(UUID player, TeleportReason reason, boolean bypass) {
        long millis = remainingMillis(player, reason, bypass);
        return millis == 0 ? 0 : (millis + 999) / 1000;
    }

    public void start(UUID player, TeleportReason reason, int seconds, boolean bypass) {
        if (bypass || seconds <= 0) {
            return;
        }
        expiresAt.put(key(player, reason), clock.getAsLong() + seconds * 1000L);
    }

    public void clear(UUID player) {
        String prefix = player + ":";
        expiresAt.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clearAll() {
        expiresAt.clear();
    }

    private static String key(UUID player, TeleportReason reason) {
        return player + ":" + reason.name();
    }
}
