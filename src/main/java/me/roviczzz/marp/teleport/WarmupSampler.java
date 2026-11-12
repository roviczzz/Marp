package me.roviczzz.marp.teleport;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.teleport.TeleportSession.CancelReason;

/**
 * Sampled warmup driver and last-ground tracker (DESIGN.md §9.3, §9.7).
 *
 * <p>One global task runs every {@code sample-interval-ticks}, checks squared
 * 3D distance for active sessions, decrements countdowns, and updates the
 * session-scoped last-ground snapshot used by {@code /back}.
 */
public final class WarmupSampler {

    public interface PlayerStateProvider {
        record PlayerSample(
                UUID uuid,
                LocationSnapshot location,
                boolean onGround,
                String feetMaterial,
                boolean dead) {}

        Collection<PlayerSample> onlinePlayers();

        Optional<PlayerSample> sample(UUID uuid);
    }

    private final TeleportSessionRegistry registry;
    private final BackService backService;
    private final PlayerStateProvider stateProvider;
    private final Supplier<MarpConfig> config;
    private final BiConsumer<UUID, CancelReason> cancelNotifier;
    private final BiConsumer<UUID, Integer> countdownNotifier;

    public WarmupSampler(
            TeleportSessionRegistry registry,
            BackService backService,
            PlayerStateProvider stateProvider,
            Supplier<MarpConfig> config,
            BiConsumer<UUID, CancelReason> cancelNotifier,
            BiConsumer<UUID, Integer> countdownNotifier) {
        this.registry = registry;
        this.backService = backService;
        this.stateProvider = stateProvider;
        this.config = config;
        this.cancelNotifier = cancelNotifier;
        this.countdownNotifier = countdownNotifier;
    }

    public void tick(int intervalTicks) {
        MarpConfig cfg = config.get();

        for (PlayerStateProvider.PlayerSample sample : stateProvider.onlinePlayers()) {
            if (!sample.dead() && sample.onGround() && !isHazard(sample.feetMaterial(), cfg)) {
                backService.recordLastGround(sample.uuid(), sample.location());
            }
        }

        double tolerance = cfg.movementTolerance();
        double maxDistSq = tolerance * tolerance;

        for (TeleportSession session : registry.all()) {
            if (session.cancelled() || session.arriving()) {
                continue;
            }
            Optional<PlayerStateProvider.PlayerSample> sampleOpt = stateProvider.sample(session.player());
            if (sampleOpt.isEmpty() || sampleOpt.get().dead()) {
                registry.cancelToken(session.player(), session.token(), CancelReason.SILENT, cancelNotifier);
                continue;
            }

            PlayerStateProvider.PlayerSample sample = sampleOpt.get();
            if (cfg.cancelOnMove()) {
                boolean moved = !sample.location().world().equals(session.origin().world())
                        || sample.location().squaredDistance(session.origin()) > maxDistSq;
                if (moved) {
                    registry.cancelToken(session.player(), session.token(), CancelReason.MOVED, cancelNotifier);
                    continue;
                }
            }

            if (session.advanceTicks(intervalTicks)) {
                int remaining = session.remainingSeconds();
                if (remaining <= 0) {
                    Runnable callback = session.countdownFinishedCallback();
                    if (callback != null) {
                        callback.run();
                    }
                } else {
                    countdownNotifier.accept(session.player(), remaining);
                }
            }
        }
    }

    public boolean onDamage(UUID player, String damageCauseName) {
        MarpConfig cfg = config.get();
        if (!cfg.cancelOnDamage() || damageCauseName == null) {
            return false;
        }
        if (cfg.ignoredDamageCauses().contains(damageCauseName.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return registry.cancelActive(player, CancelReason.DAMAGED, cancelNotifier);
    }

    public boolean onCommand(UUID player, String rawCommandLine) {
        MarpConfig cfg = config.get();
        if (!cfg.cancelOnCommand() || rawCommandLine == null) {
            return false;
        }
        String cleaned = rawCommandLine.trim().toLowerCase(Locale.ROOT);
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1).trim();
        }
        if (cleaned.isEmpty()) {
            return false;
        }
        if (cleaned.equals("marp cancel")
                || cleaned.equals("marpadmin cancel")
                || cleaned.startsWith("marp:marp cancel")
                || cleaned.startsWith("marp:marpadmin cancel")) {
            return false;
        }
        String label = cleaned.split("\\s+", 2)[0];
        int colon = label.indexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) {
            label = label.substring(colon + 1);
        }
        if (cfg.allowedCommands().contains(label) || cfg.allowedCommands().contains(cleaned)) {
            return false;
        }
        return registry.cancelActive(player, CancelReason.COMMAND, cancelNotifier);
    }

    public boolean onExternalTeleport(UUID player) {
        if (registry.isArriving(player)) {
            return false;
        }
        if (!config.get().cancelOnTeleport()) {
            return false;
        }
        return registry.cancelActive(player, CancelReason.TELEPORTED_ELSEWHERE, cancelNotifier);
    }

    public boolean onQuit(UUID player) {
        if (!config.get().cancelOnQuit()) {
            return false;
        }
        return registry.cancelActive(player, CancelReason.SILENT, null);
    }

    public boolean onDeath(UUID player) {
        if (!config.get().cancelOnDeath()) {
            return false;
        }
        return registry.cancelActive(player, CancelReason.SILENT, null);
    }

    public boolean cancelExplicit(UUID player) {
        return registry.cancelActive(player, CancelReason.EXPLICIT, cancelNotifier);
    }

    private static boolean isHazard(String materialName, MarpConfig config) {
        if (materialName == null) {
            return false;
        }
        return config.hazardMaterials().contains(materialName.toUpperCase(Locale.ROOT));
    }
}
