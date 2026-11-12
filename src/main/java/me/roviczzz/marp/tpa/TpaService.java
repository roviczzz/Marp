package me.roviczzz.marp.tpa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.settings.PlayerSettingsService;
import me.roviczzz.marp.teleport.TeleportGuard;

/**
 * In-memory state machine for {@code /tpa}, {@code /tpahere}, {@code /tpaccept},
 * {@code /tpdeny}, and {@code /tpcancel}.
 */
public final class TpaService {

    public sealed interface SendOutcome {
        record Sent(TpaRequest request) implements SendOutcome {}

        record SelfRequest() implements SendOutcome {}

        record AlreadyPending(TpaRequest existing) implements SendOutcome {}

        record TargetToggledOff() implements SendOutcome {}

        record TargetBusy() implements SendOutcome {}

        record DenyCooldown(long remainingSeconds) implements SendOutcome {}

        record CrossWorldDisabled() implements SendOutcome {}

        record MoverInCombat(long remainingSeconds) implements SendOutcome {}

        record TargetInCombat() implements SendOutcome {}
    }

    public sealed interface AcceptOutcome {
        record Ready(TpaRequest request) implements AcceptOutcome {}

        record NonePending() implements AcceptOutcome {}

        record NoPendingFrom(String query) implements AcceptOutcome {}

        record Ambiguous(List<TpaRequest> pending) implements AcceptOutcome {}

        record CrossWorldDisabled() implements AcceptOutcome {}

        record MoverInCombat(long remainingSeconds) implements AcceptOutcome {}

        record TargetInCombat() implements AcceptOutcome {}

        record RequesterOffline(TpaRequest request) implements AcceptOutcome {}
    }

    public sealed interface DenyOutcome {
        record Denied(TpaRequest request) implements DenyOutcome {}

        record NonePending() implements DenyOutcome {}

        record NoPendingFrom(String query) implements DenyOutcome {}

        record Ambiguous(List<TpaRequest> pending) implements DenyOutcome {}
    }

    public sealed interface CancelOutcome {
        record Cancelled(TpaRequest request) implements CancelOutcome {}

        record NonePending() implements CancelOutcome {}
    }

    private record PairKey(UUID requester, UUID target) {}

    private final AtomicLong nextId = new AtomicLong(1L);
    private final Map<UUID, TpaRequest> outgoingByRequester = new ConcurrentHashMap<>();
    private final Map<PairKey, Long> denyCooldownUntil = new ConcurrentHashMap<>();
    private final Supplier<MarpConfig> config;
    private final PlayerSettingsService settings;
    private final TeleportGuard guard;
    private final LongSupplier clock;

    public TpaService(
            Supplier<MarpConfig> config,
            PlayerSettingsService settings,
            TeleportGuard guard,
            LongSupplier clock) {
        this.config = config;
        this.settings = settings;
        this.guard = guard;
        this.clock = clock;
    }

    public SendOutcome sendRequest(
            TpaDirection direction,
            UUID requesterUuid,
            String requesterName,
            String requesterWorld,
            UUID targetUuid,
            String targetName,
            String targetWorld) {
        purgeExpired();
        if (requesterUuid.equals(targetUuid)) {
            return new SendOutcome.SelfRequest();
        }
        if (settings.isTpaDisabled(targetUuid)) {
            return new SendOutcome.TargetToggledOff();
        }
        TpaRequest existing = outgoingByRequester.get(requesterUuid);
        if (existing != null) {
            return new SendOutcome.AlreadyPending(existing);
        }

        long now = clock.getAsLong();
        MarpConfig cfg = config.get();
        long denyUntil = denyCooldownUntil.getOrDefault(new PairKey(requesterUuid, targetUuid), 0L);
        if (denyUntil > now) {
            long rem = (denyUntil - now + 999L) / 1000L;
            return new SendOutcome.DenyCooldown(rem);
        }
        if (!cfg.tpaCrossWorld() && !requesterWorld.equals(targetWorld)) {
            return new SendOutcome.CrossWorldDisabled();
        }

        if (direction == TpaDirection.TPA) {
            long moverCombat = guard.combatRemainingSeconds(requesterUuid);
            if (moverCombat > 0L) {
                return new SendOutcome.MoverInCombat(moverCombat);
            }
            if (cfg.tpaBlockIfTargetInCombat() && guard.combatRemainingSeconds(targetUuid) > 0L) {
                return new SendOutcome.TargetInCombat();
            }
        } else {
            long moverCombat = guard.combatRemainingSeconds(targetUuid);
            if (moverCombat > 0L) {
                return new SendOutcome.TargetInCombat();
            }
            if (cfg.tpaBlockIfTargetInCombat() && guard.combatRemainingSeconds(requesterUuid) > 0L) {
                return new SendOutcome.MoverInCombat(guard.combatRemainingSeconds(requesterUuid));
            }
        }

        if (incomingFor(targetUuid).size() >= cfg.tpaMaxPendingIncoming()) {
            return new SendOutcome.TargetBusy();
        }

        long expiresAt = now + cfg.tpaRequestTimeoutSeconds() * 1000L;
        TpaRequest request = new TpaRequest(
                nextId.getAndIncrement(),
                direction,
                requesterUuid,
                requesterName,
                targetUuid,
                targetName,
                now,
                expiresAt);
        TpaRequest raced = outgoingByRequester.putIfAbsent(requesterUuid, request);
        if (raced != null) {
            return new SendOutcome.AlreadyPending(raced);
        }
        return new SendOutcome.Sent(request);
    }

    /**
     * Validates and resolves a pending request for acceptance without removing it
     * yet so transient preflight failures (cooldowns, unsafe target) leave the
     * request retryable until {@link #consumeAccepted(long)} is called.
     */
    public AcceptOutcome prepareAccept(
            UUID targetUuid,
            String rawRequesterOrNull,
            String targetWorld,
            Function<UUID, String> onlineWorldLookup) {
        purgeExpired();
        List<TpaRequest> incoming = incomingFor(targetUuid);
        if (incoming.isEmpty()) {
            return new AcceptOutcome.NonePending();
        }

        MatchResult matched = matchIncoming(incoming, rawRequesterOrNull);
        if (matched instanceof MatchResult.NotFound nf) {
            return new AcceptOutcome.NoPendingFrom(nf.query());
        }
        if (matched instanceof MatchResult.Ambiguous amb) {
            return new AcceptOutcome.Ambiguous(amb.matches());
        }

        TpaRequest request = ((MatchResult.Single) matched).request();
        String requesterWorld = onlineWorldLookup.apply(request.requesterUuid());
        if (requesterWorld == null) {
            outgoingByRequester.remove(request.requesterUuid(), request);
            return new AcceptOutcome.RequesterOffline(request);
        }

        MarpConfig cfg = config.get();
        if (!cfg.tpaCrossWorld() && !targetWorld.equals(requesterWorld)) {
            return new AcceptOutcome.CrossWorldDisabled();
        }

        long moverCombat = guard.combatRemainingSeconds(request.moverUuid());
        if (moverCombat > 0L) {
            return new AcceptOutcome.MoverInCombat(moverCombat);
        }
        if (cfg.tpaBlockIfTargetInCombat() && guard.combatRemainingSeconds(request.anchorUuid()) > 0L) {
            return new AcceptOutcome.TargetInCombat();
        }

        return new AcceptOutcome.Ready(request);
    }

    public boolean consumeAccepted(long requestId) {
        for (TpaRequest req : outgoingByRequester.values()) {
            if (req.id() == requestId) {
                return outgoingByRequester.remove(req.requesterUuid(), req);
            }
        }
        return false;
    }

    public DenyOutcome deny(UUID targetUuid, String rawRequesterOrNull) {
        purgeExpired();
        List<TpaRequest> incoming = incomingFor(targetUuid);
        if (incoming.isEmpty()) {
            return new DenyOutcome.NonePending();
        }

        MatchResult matched = matchIncoming(incoming, rawRequesterOrNull);
        if (matched instanceof MatchResult.NotFound nf) {
            return new DenyOutcome.NoPendingFrom(nf.query());
        }
        if (matched instanceof MatchResult.Ambiguous amb) {
            return new DenyOutcome.Ambiguous(amb.matches());
        }

        TpaRequest request = ((MatchResult.Single) matched).request();
        outgoingByRequester.remove(request.requesterUuid(), request);
        int denySeconds = config.get().tpaDenyCooldownSeconds();
        if (denySeconds > 0) {
            denyCooldownUntil.put(
                    new PairKey(request.requesterUuid(), request.targetUuid()),
                    clock.getAsLong() + denySeconds * 1000L);
        }
        return new DenyOutcome.Denied(request);
    }

    public CancelOutcome cancel(UUID requesterUuid) {
        purgeExpired();
        TpaRequest removed = outgoingByRequester.remove(requesterUuid);
        if (removed == null) {
            return new CancelOutcome.NonePending();
        }
        return new CancelOutcome.Cancelled(removed);
    }

    public Optional<TpaRequest> outgoingFor(UUID requesterUuid) {
        purgeExpired();
        return Optional.ofNullable(outgoingByRequester.get(requesterUuid));
    }

    public List<TpaRequest> incomingFor(UUID targetUuid) {
        purgeExpired();
        List<TpaRequest> matches = new ArrayList<>();
        for (TpaRequest req : outgoingByRequester.values()) {
            if (req.targetUuid().equals(targetUuid)) {
                matches.add(req);
            }
        }
        matches.sort(Comparator.comparingLong(TpaRequest::createdAt).thenComparingLong(TpaRequest::id));
        return matches;
    }

    public List<String> suggestIncomingRequesters(UUID targetUuid, String rawPrefix, int maxResults) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (TpaRequest req : incomingFor(targetUuid)) {
            if (req.requesterName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(req.requesterName());
                if (out.size() >= maxResults) {
                    break;
                }
            }
        }
        return out;
    }

    public List<TpaRequest> drainExpired() {
        long now = clock.getAsLong();
        List<TpaRequest> expired = new ArrayList<>();
        Iterator<Map.Entry<UUID, TpaRequest>> it = outgoingByRequester.entrySet().iterator();
        while (it.hasNext()) {
            TpaRequest req = it.next().getValue();
            if (req.expiresAt() <= now) {
                if (outgoingByRequester.remove(req.requesterUuid(), req)) {
                    expired.add(req);
                }
            }
        }
        denyCooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        return expired;
    }

    public List<TpaRequest> onQuit(UUID quitterUuid) {
        List<TpaRequest> cancelled = new ArrayList<>();
        TpaRequest outgoing = outgoingByRequester.remove(quitterUuid);
        if (outgoing != null) {
            cancelled.add(outgoing);
        }
        for (TpaRequest req : List.copyOf(outgoingByRequester.values())) {
            if (req.targetUuid().equals(quitterUuid)) {
                if (outgoingByRequester.remove(req.requesterUuid(), req)) {
                    cancelled.add(req);
                }
            }
        }
        return cancelled;
    }

    public void clearAll() {
        outgoingByRequester.clear();
        denyCooldownUntil.clear();
    }

    private void purgeExpired() {
        long now = clock.getAsLong();
        for (TpaRequest req : List.copyOf(outgoingByRequester.values())) {
            if (req.expiresAt() <= now) {
                outgoingByRequester.remove(req.requesterUuid(), req);
            }
        }
    }

    private sealed interface MatchResult {
        record Single(TpaRequest request) implements MatchResult {}

        record NotFound(String query) implements MatchResult {}

        record Ambiguous(List<TpaRequest> matches) implements MatchResult {}
    }

    private static MatchResult matchIncoming(List<TpaRequest> incoming, String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            if (incoming.size() == 1) {
                return new MatchResult.Single(incoming.get(0));
            }
            return new MatchResult.Ambiguous(List.copyOf(incoming));
        }
        String q = rawQuery.trim().toLowerCase(Locale.ROOT);
        for (TpaRequest req : incoming) {
            if (req.requesterName().toLowerCase(Locale.ROOT).equals(q)) {
                return new MatchResult.Single(req);
            }
        }
        List<TpaRequest> prefixMatches = new ArrayList<>();
        for (TpaRequest req : incoming) {
            if (req.requesterName().toLowerCase(Locale.ROOT).startsWith(q)) {
                prefixMatches.add(req);
            }
        }
        if (prefixMatches.size() == 1) {
            return new MatchResult.Single(prefixMatches.get(0));
        }
        if (prefixMatches.size() > 1) {
            return new MatchResult.Ambiguous(List.copyOf(prefixMatches));
        }
        return new MatchResult.NotFound(rawQuery);
    }
}
