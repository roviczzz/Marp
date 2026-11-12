package me.roviczzz.marp.model;

import java.util.List;
import java.util.UUID;

/** An immutable player home. */
public record Home(
        long id,
        UUID owner,
        String name,
        String nameKey,
        LocationSnapshot location,
        IconSpec icon,
        String displayName,
        List<String> lore,
        long rev,
        long createdAt,
        long updatedAt) {

    public Home {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public Home withLocation(LocationSnapshot location, long updatedAt) {
        return new Home(id, owner, name, nameKey, location, icon, displayName, lore, rev, createdAt, updatedAt);
    }

    public Home withMetadata(IconSpec icon, String displayName, List<String> lore, long rev, long updatedAt) {
        return new Home(id, owner, name, nameKey, location, icon, displayName, lore, rev, createdAt, updatedAt);
    }
}
