package me.roviczzz.marp.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;

/**
 * Pure, dependency-free PvP combat tag tracker driven by a millisecond clock.
 */
public final class CombatTracker implements TeleportGuard {

    private final Supplier<MarpConfig> config;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastCombatMillis = new ConcurrentHashMap<>();

    public CombatTracker(Supplier<MarpConfig> config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    public void recordCombat(UUID attacker, UUID victim) {
        if (attacker == null || victim == null || attacker.equals(victim)) {
            return;
        }
        if (config.get().tpaCombatTagSeconds() <= 0) {
            return;
        }
        long now = clock.getAsLong();
        lastCombatMillis.put(attacker, now);
        lastCombatMillis.put(victim, now);
    }

    @Override
    public long combatRemainingSeconds(UUID player) {
        int tagSeconds = config.get().tpaCombatTagSeconds();
        if (tagSeconds <= 0 || player == null) {
            return 0L;
        }
        Long hitAt = lastCombatMillis.get(player);
        if (hitAt == null) {
            return 0L;
        }
        long elapsed = clock.getAsLong() - hitAt;
        long totalMillis = tagSeconds * 1000L;
        long remainingMillis = totalMillis - elapsed;
        if (remainingMillis <= 0L) {
            lastCombatMillis.remove(player, hitAt);
            return 0L;
        }
        return (remainingMillis + 999L) / 1000L;
    }

    public void clear(UUID player) {
        if (player != null) {
            lastCombatMillis.remove(player);
        }
    }

    public void clearAll() {
        lastCombatMillis.clear();
    }
}
