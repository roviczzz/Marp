package me.roviczzz.marp.model;

import java.util.UUID;

/** One back target. At most one {@link BackKind#DEATH} and one {@link BackKind#TELEPORT} per player. */
public record BackEntry(UUID player, BackKind kind, LocationSnapshot location, String cause, long createdAt) {

    public BackEntry {
        if (player == null) {
            throw new IllegalArgumentException("player is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (location == null) {
            throw new IllegalArgumentException("location is required");
        }
    }
}
