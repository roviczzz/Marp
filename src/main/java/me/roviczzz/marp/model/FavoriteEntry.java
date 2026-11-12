package me.roviczzz.marp.model;

import java.util.UUID;

/** An immutable favorite mapping to a home or warp by stable record id. */
public record FavoriteEntry(
        UUID player,
        FavoriteKind kind,
        long targetId,
        int sortOrder,
        long createdAt) {}
