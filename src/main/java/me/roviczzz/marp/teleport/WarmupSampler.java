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
 * 3D distance for active sessions, decrements countdowns, drives 1-second
 * request expiry ticks, and updates the session-scoped last-ground snapshot.
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
    private final BiConsumer<TeleportSession, CancelReason> anchorCancelNotifier;
    private final Runnable oneSecondHook;
    private int oneSecondTickAccumulator;

    public WarmupSampler(
            TeleportSessionRegistry registry,
            BackService backService,
            PlayerStateProvider stateProvider,
            Supplier<MarpConfig> config,
            BiConsumer<UUID, CancelReason> cancelNotifier,
            BiConsumer<UUID, Integer> countdownNotifier) {
        this(
                registry,
                backService,
                stateProvider,
                config,
                cancelNotifier,
                countdownNotifier,
                (session, reason) -> {
                    if (reason.messageKey() != null) {
                        cancelNotifier.accept(session.player(), reason);
                    }
                },
                () -> {
                });
    }

    public WarmupSampler(
            TeleportSessionRegistry registry,
            BackService backService,
            PlayerStateProvider stateProvider,
            Supplier<MarpConfig> config,
            BiConsumer<UUID, CancelReason> cancelNotifier,
            BiConsumer<UUID, Integer> countdownNotifier,
            BiConsumer<TeleportSession, CancelReason> anchorCancelNotifier,
            Runnable oneSecondHook) {
        this.registry = registry;
        this.backService = backService;
        this.stateProvider = stateProvider;
        this.config = config;
        this.cancelNotifier = cancelNotifier;
        this.countdownNotifier = countdownNotifier;
        this.anchorCancelNotifier = anchorCancelNotifier;
        this.oneSecondHook = oneSecondHook;
    }

    public void tick(int intervalTicks) {
        MarpConfig cfg = config.get();

        oneSecondTickAccumulator += Math.max(1, intervalTicks);
        while (oneSecondTickAccumulator >= 20) {
            oneSecondTickAccumulator -= 20;
            oneSecondHook.run();
        }

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

            if (session.anchorPlayer() != null) {
                Optional<PlayerStateProvider.PlayerSample> anchorOpt = stateProvider.sample(session.anchorPlayer());
                if (anchorOpt.isEmpty()) {
                    if (registry.cancelToken(session.player(), session.token(), CancelReason.TARGET_OFFLINE, null)) {
                        anchorCancelNotifier.accept(session, CancelReason.TARGET_OFFLINE);
                    }
                    continue;
                }
                PlayerStateProvider.PlayerSample anchorSample = anchorOpt.get();
                if (anchorSample.dead()) {
                    if (registry.cancelToken(session.player(), session.token(), CancelReason.TARGET_DIED, null)) {
                        anchorCancelNotifier.accept(session, CancelReason.TARGET_DIED);
                    }
                    continue;
                }
                if (!cfg.tpaCrossWorld()
                        && !anchorSample.location().world().equals(sample.location().world())) {
                    if (registry.cancelToken(session.player(), session.token(), CancelReason.CROSS_WORLD, null)) {
                        anchorCancelNotifier.accept(session, CancelReason.CROSS_WORLD);
                    }
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
        int anchorCancelled = registry.cancelByAnchor(
                player, CancelReason.TARGET_OFFLINE, anchorCancelNotifier);
        if (!config.get().cancelOnQuit()) {
            return anchorCancelled > 0;
        }
        return registry.cancelActive(player, CancelReason.SILENT, null) || anchorCancelled > 0;
    }

    public boolean onDeath(UUID player) {
        int anchorCancelled = registry.cancelByAnchor(
                player, CancelReason.TARGET_DIED, anchorCancelNotifier);
        if (!config.get().cancelOnDeath()) {
            return anchorCancelled > 0;
        }
        return registry.cancelActive(player, CancelReason.SILENT, null) || anchorCancelled > 0;
    }

    public int onAnchorWorldChange(UUID anchorUuid, String newWorldName) {
        if (config.get().tpaCrossWorld() || anchorUuid == null || newWorldName == null) {
            return 0;
        }
        int cancelled = 0;
        for (TeleportSession session : registry.all()) {
            if (!anchorUuid.equals(session.anchorPlayer())) {
                continue;
            }
            Optional<PlayerStateProvider.PlayerSample> moverSample = stateProvider.sample(session.player());
            if (moverSample.isPresent() && !moverSample.get().location().world().equals(newWorldName)) {
                if (registry.cancelToken(session.player(), session.token(), CancelReason.CROSS_WORLD, null)) {
                    anchorCancelNotifier.accept(session, CancelReason.CROSS_WORLD);
                    cancelled++;
                }
            }
        }
        return cancelled;
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
