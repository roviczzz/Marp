package me.roviczzz.marp.teleport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.back.BackService.FallbackDecision;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MarpConfig.SafetyMode;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.teleport.TeleportPlatform.PlayerContext;
import me.roviczzz.marp.teleport.TeleportSession.CancelReason;
import me.roviczzz.marp.util.Scheduler;

/**
 * Orchestrates the 12-step teleport pipeline from DESIGN.md §9.2.
 *
 * <p>Homes, warps, and {@code /back} all enter through {@link #requestTeleport}
 * so warmup, cancellation, cooldowns, collision-box safety, and failure
 * rollback behave identically across features.
 */
public final class TeleportService {

    public sealed interface Outcome {
        record Teleported(LocationSnapshot destination, boolean adjusted) implements Outcome {}

        record Cancelled(CancelReason reason) implements Outcome {}

        record OnCooldown(long remainingSeconds) implements Outcome {}

        record Unsafe(String reason) implements Outcome {}

        record WorldMissing(String world) implements Outcome {}

        record CrossWorldDisabled() implements Outcome {}

        record AlreadyThere() implements Outcome {}

        record Failed() implements Outcome {}

        record Offline() implements Outcome {}
    }

    private sealed interface SafetyDecision {
        record Proceed(
                LocationSnapshot location,
                boolean adjusted,
                String warnReason,
                boolean usedLastGroundFallback) implements SafetyDecision {}

        record Rejected(String reason, String messageKey, Map<String, String> placeholders)
                implements SafetyDecision {}
    }

    private final TeleportSessionRegistry sessions;
    private final CooldownService cooldowns;
    private final BackService backService;
    private final TeleportPlatform platform;
    private final Scheduler scheduler;
    private final Supplier<MarpConfig> config;

    public TeleportService(
            TeleportSessionRegistry sessions,
            CooldownService cooldowns,
            BackService backService,
            TeleportPlatform platform,
            Scheduler scheduler,
            Supplier<MarpConfig> config) {
        this.sessions = sessions;
        this.cooldowns = cooldowns;
        this.backService = backService;
        this.platform = platform;
        this.scheduler = scheduler;
        this.config = config;
    }

    public TeleportSessionRegistry sessions() {
        return sessions;
    }

    public CooldownService cooldowns() {
        return cooldowns;
    }

    public CompletableFuture<Outcome> requestTeleport(
            UUID playerUuid,
            TeleportReason reason,
            String targetName,
            LocationSnapshot rawTarget,
            BackKind backKind) {
        return requestTeleport(playerUuid, reason, targetName, rawTarget, backKind, false);
    }

    public CompletableFuture<Outcome> requestTeleport(
            UUID playerUuid,
            TeleportReason reason,
            String targetName,
            LocationSnapshot rawTarget,
            BackKind backKind,
            boolean requireValidBed) {
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        scheduler.runOnMain(() -> startOnMain(
                playerUuid, reason, targetName, rawTarget, backKind, null, requireValidBed, result));
        return result;
    }

    public CompletableFuture<Outcome> requestTpaTeleport(
            UUID moverUuid,
            UUID anchorUuid,
            String anchorName,
            LocationSnapshot initialAnchorTarget) {
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        scheduler.runOnMain(() -> startOnMain(
                moverUuid, TeleportReason.TPA, anchorName, initialAnchorTarget, null, anchorUuid, false, result));
        return result;
    }

    private void startOnMain(
            UUID playerUuid,
            TeleportReason reason,
            String targetName,
            LocationSnapshot rawTarget,
            BackKind backKind,
            UUID anchorUuid,
            boolean requireValidBed,
            CompletableFuture<Outcome> outerFuture) {
        Optional<PlayerContext> ctxOpt = platform.player(playerUuid);
        if (ctxOpt.isEmpty()) {
            outerFuture.complete(new Outcome.Offline());
            return;
        }
        PlayerContext ctx = ctxOpt.get();
        MarpConfig cfg = config.get();

        // Cross-world check
        if (!ctx.location().world().equals(rawTarget.world())) {
            if ((reason == TeleportReason.HOME && !cfg.homesCrossWorld())
                    || (reason == TeleportReason.BACK && !cfg.backCrossWorld())
                    || (reason == TeleportReason.TPA && !cfg.tpaCrossWorld())) {
                platform.sendMessage(playerUuid, Messages.CROSS_WORLD_DISABLED, Map.of());
                outerFuture.complete(new Outcome.CrossWorldDisabled());
                return;
            }
        }

        // Loaded-world check (never auto-load a world for a teleport)
        if (!platform.isWorldLoaded(rawTarget.world())) {
            platform.sendMessage(playerUuid, Messages.WORLD_MISSING, Map.of("world", rawTarget.world()));
            outerFuture.complete(new Outcome.WorldMissing(rawTarget.world()));
            return;
        }

        // /back already-there short-circuit (within 1 block)
        if (reason == TeleportReason.BACK
                && ctx.location().world().equals(rawTarget.world())
                && ctx.location().squaredDistance(rawTarget) <= 1.0) {
            platform.sendMessage(playerUuid, Messages.BACK_ALREADY_THERE, Map.of());
            outerFuture.complete(new Outcome.AlreadyThere());
            return;
        }

        // Cooldown check (cooldown starts on arrival, never on request)
        long cooldownRemaining = cooldowns.remainingSeconds(playerUuid, reason, ctx.bypassCooldown());
        if (cooldownRemaining > 0L) {
            platform.sendMessage(
                    playerUuid,
                    Messages.TELEPORT_COOLDOWN,
                    Map.of("seconds", Long.toString(cooldownRemaining)));
            outerFuture.complete(new Outcome.OnCooldown(cooldownRemaining));
            return;
        }

        int warmupSeconds = ctx.bypassWarmup() ? 0 : warmupSecondsFor(reason, cfg);
        if (cfg.closeInventory()) {
            platform.closeMarpMenuIfOpen(playerUuid);
        }

        TeleportSession session = sessions.start(
                playerUuid,
                reason,
                targetName,
                ctx.location(),
                rawTarget,
                backKind,
                warmupSeconds,
                anchorUuid,
                requireValidBed);
        session.completion().whenComplete((outcome, error) -> {
            if (error != null) {
                outerFuture.completeExceptionally(error);
            } else {
                outerFuture.complete(outcome);
            }
        });

        evaluateWithFallback(playerUuid, reason, rawTarget, ctx.bypassSafety(), cfg, true, requireValidBed)
                .whenComplete((decision, error) -> scheduler.runOnMain(() -> {
                    if (!sessions.isCurrent(session)) {
                        return;
                    }
                    if (platform.player(playerUuid).isEmpty()) {
                        sessions.removeIfMatch(session);
                        session.completion().complete(new Outcome.Offline());
                        return;
                    }
                    if (error != null || decision == null) {
                        sessions.removeIfMatch(session);
                        platform.sendMessage(playerUuid, Messages.TELEPORT_FAILED, Map.of());
                        session.completion().complete(new Outcome.Failed());
                        return;
                    }
                    if (decision instanceof SafetyDecision.Rejected rejected) {
                        sessions.removeIfMatch(session);
                        platform.sendMessage(playerUuid, rejected.messageKey(), rejected.placeholders());
                        session.completion().complete(new Outcome.Unsafe(rejected.reason()));
                        return;
                    }
                    SafetyDecision.Proceed proceed = (SafetyDecision.Proceed) decision;
                    session.updateTarget(proceed.location(), proceed.adjusted());
                    if (proceed.usedLastGroundFallback()) {
                        platform.sendMessage(playerUuid, Messages.BACK_FALLBACK_LAST_GROUND, Map.of());
                    }
                    if (proceed.warnReason() != null) {
                        platform.sendMessage(
                                playerUuid,
                                Messages.TELEPORT_UNSAFE,
                                Map.of("reason", proceed.warnReason()));
                    }
                    if (warmupSeconds > 0) {
                        session.onCountdownFinished(() -> executeArrival(session));
                        platform.sendMessage(
                                playerUuid,
                                Messages.TELEPORT_WARMUP,
                                Map.of("seconds", Integer.toString(warmupSeconds)));
                        platform.sendCountdown(playerUuid, warmupSeconds, cfg);
                    } else {
                        executeArrival(session);
                    }
                }));
    }

    private void executeArrival(TeleportSession session) {
        scheduler.runOnMain(() -> {
            if (!sessions.isCurrent(session)) {
                return;
            }
            UUID playerUuid = session.player();
            Optional<PlayerContext> ctxOpt = platform.player(playerUuid);
            if (ctxOpt.isEmpty()) {
                sessions.removeIfMatch(session);
                session.completion().complete(new Outcome.Offline());
                return;
            }
            PlayerContext ctx = ctxOpt.get();
            MarpConfig cfg = config.get();

            // For TPA, re-resolve the anchor player's live location at arrival.
            if (session.anchorPlayer() != null) {
                UUID anchorUuid = session.anchorPlayer();
                Optional<PlayerContext> anchorOpt = platform.player(anchorUuid);
                if (anchorOpt.isEmpty()) {
                    sessions.removeIfMatch(session);
                    platform.clearActionBar(playerUuid);
                    CancelReason cancelReason = platform.isPlayerDead(anchorUuid)
                            ? CancelReason.TARGET_DIED
                            : CancelReason.TARGET_OFFLINE;
                    String msgKey = cancelReason.messageKey();
                    if (msgKey != null) {
                        platform.sendMessage(playerUuid, msgKey, Map.of("player", session.targetName()));
                    }
                    session.completion().complete(new Outcome.Cancelled(cancelReason));
                    return;
                }
                LocationSnapshot liveAnchor = anchorOpt.get().location();
                if (!cfg.tpaCrossWorld() && !ctx.location().world().equals(liveAnchor.world())) {
                    sessions.removeIfMatch(session);
                    platform.clearActionBar(playerUuid);
                    platform.sendMessage(playerUuid, Messages.CROSS_WORLD_DISABLED, Map.of());
                    session.completion().complete(new Outcome.CrossWorldDisabled());
                    return;
                }
                if (!platform.isWorldLoaded(liveAnchor.world())) {
                    sessions.removeIfMatch(session);
                    platform.clearActionBar(playerUuid);
                    platform.sendMessage(playerUuid, Messages.WORLD_MISSING, Map.of("world", liveAnchor.world()));
                    session.completion().complete(new Outcome.WorldMissing(liveAnchor.world()));
                    return;
                }
                session.resetTargetForArrival(liveAnchor);
            }

            // Final safety re-check: chunks or blocks may have changed during warmup.
            evaluateWithFallback(
                    playerUuid,
                    session.reason(),
                    session.target(),
                    ctx.bypassSafety(),
                    cfg,
                    false,
                    session.requireValidBed())
                    .whenComplete((decision, error) -> scheduler.runOnMain(() -> {
                        if (!sessions.isCurrent(session)) {
                            return;
                        }
                        Optional<PlayerContext> arrivalCtxOpt = platform.player(playerUuid);
                        if (arrivalCtxOpt.isEmpty()) {
                            sessions.removeIfMatch(session);
                            platform.clearActionBar(playerUuid);
                            session.completion().complete(new Outcome.Offline());
                            return;
                        }
                        if (error != null || decision == null) {
                            sessions.removeIfMatch(session);
                            platform.clearActionBar(playerUuid);
                            platform.sendMessage(playerUuid, Messages.TELEPORT_FAILED, Map.of());
                            session.completion().complete(new Outcome.Failed());
                            return;
                        }
                        if (decision instanceof SafetyDecision.Rejected rejected) {
                            sessions.removeIfMatch(session);
                            platform.clearActionBar(playerUuid);
                            platform.sendMessage(playerUuid, rejected.messageKey(), rejected.placeholders());
                            session.completion().complete(new Outcome.Unsafe(rejected.reason()));
                            return;
                        }

                        SafetyDecision.Proceed proceed = (SafetyDecision.Proceed) decision;
                        session.updateTarget(proceed.location(), proceed.adjusted());
                        LocationSnapshot finalTarget = session.target();
                        PlayerContext arrivalCtx = arrivalCtxOpt.get();
                        LocationSnapshot preTeleportOrigin = arrivalCtx.location();

                        session.markArriving();
                        platform.preparePlayerForArrival(playerUuid, cfg);

                        platform.teleportAsync(playerUuid, finalTarget)
                                .whenComplete((succeeded, tpError) -> scheduler.runOnMain(() -> {
                                    try {
                                        if (platform.player(playerUuid).isEmpty()) {
                                            session.completion().complete(new Outcome.Offline());
                                            return;
                                        }
                                        if (tpError != null || !Boolean.TRUE.equals(succeeded)) {
                                            platform.sendMessage(playerUuid, Messages.TELEPORT_FAILED, Map.of());
                                            session.completion().complete(new Outcome.Failed());
                                            return;
                                        }
                                        // Commit /back and cooldown only after a confirmed arrival.
                                        if (session.reason() != TeleportReason.BACK) {
                                            backService.recordTeleportOrigin(
                                                    playerUuid, preTeleportOrigin, session.reason());
                                        } else if (session.backKind() != null) {
                                            backService.consumeIfConfigured(playerUuid, session.backKind());
                                        }
                                        cooldowns.start(
                                                playerUuid,
                                                session.reason(),
                                                cooldownSecondsFor(session.reason(), cfg),
                                                arrivalCtx.bypassCooldown());
                                        if (session.adjusted()) {
                                            platform.sendMessage(playerUuid, Messages.TELEPORT_ADJUSTED, Map.of());
                                        }
                                        platform.sendMessage(
                                                playerUuid,
                                                Messages.TELEPORT_TELEPORTED,
                                                Map.of("name", session.targetName()));
                                        platform.playArrivalEffects(playerUuid, cfg);
                                        session.completion().complete(
                                                new Outcome.Teleported(finalTarget, session.adjusted()));
                                    } finally {
                                        sessions.removeIfMatch(session);
                                        platform.clearActionBar(playerUuid);
                                    }
                                }));
                    }));
        });
    }

    private CompletableFuture<SafetyDecision> evaluateWithFallback(
            UUID playerUuid,
            TeleportReason reason,
            LocationSnapshot target,
            boolean bypassSafety,
            MarpConfig cfg,
            boolean allowBackFallback,
            boolean requireValidBed) {
        CompletableFuture<Boolean> bedCheck = (requireValidBed && cfg.bedHomeRequireValidBed())
                ? platform.validateBedRespawn(playerUuid, target)
                : CompletableFuture.completedFuture(true);
        return bedCheck.thenCompose(bedOk -> {
            if (!Boolean.TRUE.equals(bedOk)) {
                return CompletableFuture.completedFuture(new SafetyDecision.Rejected(
                        "bed", Messages.BED_MISSING_OR_OBSTRUCTED, Map.of()));
            }
            if (bypassSafety || !cfg.safetyEnabled()) {
                return CompletableFuture.completedFuture(
                        new SafetyDecision.Proceed(target, false, null, false));
            }
            return platform.evaluateSafety(target, cfg).thenCompose(result -> {
            if (result instanceof SafetyResult.Safe safe) {
                return CompletableFuture.completedFuture(
                        new SafetyDecision.Proceed(safe.location(), false, null, false));
            }
            if (result instanceof SafetyResult.Adjusted adjusted) {
                return CompletableFuture.completedFuture(
                        new SafetyDecision.Proceed(adjusted.location(), true, null, false));
            }
            SafetyResult.Unsafe unsafe = (SafetyResult.Unsafe) result;
            if (cfg.safetyMode() == SafetyMode.WARN) {
                return CompletableFuture.completedFuture(
                        new SafetyDecision.Proceed(target, false, unsafe.reason(), false));
            }

            // /back unreachable-target fallback (void/lava death with no nearby safe spot)
            if (allowBackFallback && reason == TeleportReason.BACK) {
                FallbackDecision fallback = backService.resolveUnreachableFallback(playerUuid);
                if (fallback instanceof FallbackDecision.UseLastGround useGround) {
                    return platform.evaluateSafety(useGround.location(), cfg).thenApply(groundResult -> {
                        if (groundResult instanceof SafetyResult.Safe safeGround) {
                            return new SafetyDecision.Proceed(safeGround.location(), false, null, true);
                        }
                        if (groundResult instanceof SafetyResult.Adjusted adjustedGround) {
                            return new SafetyDecision.Proceed(adjustedGround.location(), true, null, true);
                        }
                        return rejectForUnsafe(unsafe.reason(), cfg);
                    });
                }
                if (fallback instanceof FallbackDecision.NoGroundRecorded) {
                    return CompletableFuture.completedFuture(new SafetyDecision.Rejected(
                            unsafe.reason(), Messages.BACK_FALLBACK_NONE, Map.of()));
                }
            }

            return CompletableFuture.completedFuture(rejectForUnsafe(unsafe.reason(), cfg));
        });
        });
    }

    private static SafetyDecision.Rejected rejectForUnsafe(String reasonCode, MarpConfig cfg) {
        if ("border".equals(reasonCode)) {
            return new SafetyDecision.Rejected(reasonCode, Messages.TELEPORT_WORLD_BORDER, Map.of());
        }
        if (cfg.safetyMode() == SafetyMode.SEARCH) {
            return new SafetyDecision.Rejected(reasonCode, Messages.TELEPORT_UNSAFE_NO_SPOT, Map.of());
        }
        return new SafetyDecision.Rejected(
                reasonCode, Messages.TELEPORT_UNSAFE, Map.of("reason", reasonCode));
    }

    private static int warmupSecondsFor(TeleportReason reason, MarpConfig cfg) {
        return switch (reason) {
            case HOME -> cfg.warmupHomeSeconds();
            case WARP -> cfg.warmupWarpSeconds();
            case BACK -> cfg.warmupBackSeconds();
            case TPA -> cfg.warmupTpaSeconds();
        };
    }

    private static int cooldownSecondsFor(TeleportReason reason, MarpConfig cfg) {
        return switch (reason) {
            case HOME -> cfg.cooldownHomeSeconds();
            case WARP -> cfg.cooldownWarpSeconds();
            case BACK -> cfg.cooldownBackSeconds();
            case TPA -> cfg.cooldownTpaSeconds();
        };
    }
}
