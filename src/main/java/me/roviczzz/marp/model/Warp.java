package me.roviczzz.marp.model;

import java.util.List;
import java.util.UUID;

/** An immutable server warp. */
public record Warp(
        long id,
        String name,
        String nameKey,
        LocationSnapshot location,
        IconSpec icon,
        String displayName,
        List<String> lore,
        String permission,
        UUID createdBy,
        long rev,
        long createdAt,
        long updatedAt) {

    public Warp {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public Warp withLocation(LocationSnapshot location, long updatedAt) {
        return new Warp(
                id, name, nameKey, location, icon, displayName, lore, permission, createdBy, rev, createdAt, updatedAt);
    }

    public Warp withMetadata(IconSpec icon, String displayName, List<String> lore, long rev, long updatedAt) {
        return new Warp(
                id, name, nameKey, location, icon, displayName, lore, permission, createdBy, rev, createdAt, updatedAt);
    }
}
