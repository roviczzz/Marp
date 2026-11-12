package me.roviczzz.marp.tpa;

import java.util.UUID;

/** Immutable pending teleport request between two online players. */
public record TpaRequest(
        long id,
        TpaDirection direction,
        UUID requesterUuid,
        String requesterName,
        UUID targetUuid,
        String targetName,
        long createdAt,
        long expiresAt) {

    public UUID moverUuid() {
        return direction == TpaDirection.TPA ? requesterUuid : targetUuid;
    }

    public UUID anchorUuid() {
        return direction == TpaDirection.TPA ? targetUuid : requesterUuid;
    }

    public String anchorName() {
        return direction == TpaDirection.TPA ? targetName : requesterName;
    }

    public int remainingSeconds(long nowMillis) {
        long remaining = expiresAt - nowMillis;
        if (remaining <= 0L) {
            return 0;
        }
        return (int) ((remaining + 999L) / 1000L);
    }
}
