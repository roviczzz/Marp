package me.roviczzz.marp.teleport;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.teleport.TeleportSession.CancelReason;

/**
 * One-session-per-player registry with monotonic tokens.
 *
 * <p>Replacing a session silently invalidates the previous one so it can never
 * emit a late cancellation or completion message.
 */
public final class TeleportSessionRegistry {

    private final AtomicLong nextToken = new AtomicLong(1L);
    private final Map<UUID, TeleportSession> active = new ConcurrentHashMap<>();

    public TeleportSession start(
            UUID player,
            TeleportReason reason,
            String targetName,
            LocationSnapshot origin,
            LocationSnapshot target,
            BackKind backKind,
            int warmupSeconds) {
        long token = nextToken.getAndIncrement();
        TeleportSession session = new TeleportSession(
                token, player, reason, targetName, origin, target, backKind, warmupSeconds);
        TeleportSession previous = active.put(player, session);
        if (previous != null) {
            previous.markCancelled();
            previous.completion().complete(new TeleportService.Outcome.Cancelled(CancelReason.SILENT));
        }
        return session;
    }

    public Optional<TeleportSession> get(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public Collection<TeleportSession> all() {
        return List.copyOf(active.values());
    }

    public boolean isCurrent(TeleportSession session) {
        if (session == null || session.cancelled()) {
            return false;
        }
        TeleportSession current = active.get(session.player());
        return current != null && current.token() == session.token();
    }

    public boolean isArriving(UUID player) {
        TeleportSession current = active.get(player);
        return current != null && !current.cancelled() && current.arriving();
    }

    public boolean cancelToken(
            UUID player,
            long expectedToken,
            CancelReason reason,
            BiConsumer<UUID, CancelReason> onCancelled) {
        TeleportSession[] cancelledHolder = new TeleportSession[1];
        active.computeIfPresent(player, (ignored, current) -> {
            if (current.token() != expectedToken || current.cancelled() || current.arriving()) {
                return current;
            }
            current.markCancelled();
            cancelledHolder[0] = current;
            return null;
        });
        TeleportSession cancelled = cancelledHolder[0];
        if (cancelled == null) {
            return false;
        }
        if (onCancelled != null && reason.messageKey() != null) {
            onCancelled.accept(player, reason);
        }
        cancelled.completion().complete(new TeleportService.Outcome.Cancelled(reason));
        return true;
    }

    public boolean cancelActive(
            UUID player,
            CancelReason reason,
            BiConsumer<UUID, CancelReason> onCancelled) {
        TeleportSession current = active.get(player);
        if (current == null) {
            return false;
        }
        return cancelToken(player, current.token(), reason, onCancelled);
    }

    public void removeIfMatch(TeleportSession session) {
        if (session == null) {
            return;
        }
        active.computeIfPresent(
                session.player(),
                (ignored, current) -> current.token() == session.token() ? null : current);
    }

    public void cancelAllSilently() {
        for (TeleportSession session : all()) {
            cancelToken(session.player(), session.token(), CancelReason.SILENT, null);
        }
        active.clear();
    }
}
