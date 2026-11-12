package me.roviczzz.marp.teleport;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;

/**
 * In-flight teleport state for a single player.
 *
 * <p>Every session carries a monotonic {@code token}. When a new request
 * replaces an existing session, the old token becomes stale so late callbacks
 * cannot send duplicate cancellation or arrival messages.
 */
public final class TeleportSession {

    public enum CancelReason {
        MOVED(Messages.TELEPORT_MOVED),
        DAMAGED(Messages.TELEPORT_DAMAGED),
        COMMAND(Messages.TELEPORT_COMMAND),
        TELEPORTED_ELSEWHERE(Messages.TELEPORT_TELEPORTED_ELSEWHERE),
        EXPLICIT(Messages.CANCELLED),
        TARGET_OFFLINE(Messages.TPA_TARGET_OFFLINE),
        TARGET_DIED(Messages.TPA_TARGET_DIED),
        CROSS_WORLD(Messages.CROSS_WORLD_DISABLED),
        SILENT(null);

        private final String messageKey;

        CancelReason(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final long token;
    private final UUID player;
    private final TeleportReason reason;
    private final String targetName;
    private final LocationSnapshot origin;
    private final BackKind backKind;
    private final UUID anchorPlayer;
    private final boolean requireValidBed;
    private final CompletableFuture<TeleportService.Outcome> completion = new CompletableFuture<>();

    private volatile LocationSnapshot target;
    private volatile boolean adjusted;
    private volatile int remainingSeconds;
    private volatile int elapsedTicksInCurrentSecond;
    private volatile boolean arriving;
    private volatile boolean cancelled;
    private volatile Runnable countdownFinishedCallback;

    public TeleportSession(
            long token,
            UUID player,
            TeleportReason reason,
            String targetName,
            LocationSnapshot origin,
            LocationSnapshot target,
            BackKind backKind,
            int warmupSeconds) {
        this(token, player, reason, targetName, origin, target, backKind, warmupSeconds, null);
    }

    public TeleportSession(
            long token,
            UUID player,
            TeleportReason reason,
            String targetName,
            LocationSnapshot origin,
            LocationSnapshot target,
            BackKind backKind,
            int warmupSeconds,
            UUID anchorPlayer) {
        this(token, player, reason, targetName, origin, target, backKind, warmupSeconds, anchorPlayer, false);
    }

    public TeleportSession(
            long token,
            UUID player,
            TeleportReason reason,
            String targetName,
            LocationSnapshot origin,
            LocationSnapshot target,
            BackKind backKind,
            int warmupSeconds,
            UUID anchorPlayer,
            boolean requireValidBed) {
        this.token = token;
        this.player = player;
        this.reason = reason;
        this.targetName = targetName;
        this.origin = origin;
        this.target = target;
        this.backKind = backKind;
        this.anchorPlayer = anchorPlayer;
        this.requireValidBed = requireValidBed;
        this.remainingSeconds = Math.max(0, warmupSeconds);
    }

    public long token() {
        return token;
    }

    public UUID player() {
        return player;
    }

    public TeleportReason reason() {
        return reason;
    }

    public String targetName() {
        return targetName;
    }

    public LocationSnapshot origin() {
        return origin;
    }

    public LocationSnapshot target() {
        return target;
    }

    public void updateTarget(LocationSnapshot target, boolean adjusted) {
        this.target = target;
        if (adjusted) {
            this.adjusted = true;
        }
    }

    public void resetTargetForArrival(LocationSnapshot target) {
        this.target = target;
        this.adjusted = false;
    }

    public boolean adjusted() {
        return adjusted;
    }

    public BackKind backKind() {
        return backKind;
    }

    public UUID anchorPlayer() {
        return anchorPlayer;
    }

    public boolean requireValidBed() {
        return requireValidBed;
    }

    public int remainingSeconds() {
        return remainingSeconds;
    }

    public boolean arriving() {
        return arriving;
    }

    public void markArriving() {
        this.arriving = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    void markCancelled() {
        this.cancelled = true;
    }

    public CompletableFuture<TeleportService.Outcome> completion() {
        return completion;
    }

    public void onCountdownFinished(Runnable callback) {
        this.countdownFinishedCallback = callback;
    }

    Runnable countdownFinishedCallback() {
        return countdownFinishedCallback;
    }

    /**
     * Advances the warmup clock by {@code deltaTicks}. Returns {@code true} each
     * time a full second (20 ticks) elapses.
     */
    boolean advanceTicks(int deltaTicks) {
        elapsedTicksInCurrentSecond += Math.max(1, deltaTicks);
        if (elapsedTicksInCurrentSecond >= 20) {
            elapsedTicksInCurrentSecond -= 20;
            remainingSeconds = Math.max(0, remainingSeconds - 1);
            return true;
        }
        return false;
    }
}
