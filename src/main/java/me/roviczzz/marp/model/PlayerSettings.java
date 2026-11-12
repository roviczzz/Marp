package me.roviczzz.marp.model;

import java.util.UUID;

/** Immutable per-player feature toggle preferences. */
public record PlayerSettings(
        UUID player,
        boolean tpaDisabled,
        boolean bedHomeDisabled,
        long updatedAt) {

    public static PlayerSettings defaults(UUID player) {
        return new PlayerSettings(player, false, false, 0L);
    }

    public PlayerSettings withTpaDisabled(boolean disabled, long updatedAt) {
        return new PlayerSettings(player, disabled, bedHomeDisabled, updatedAt);
    }

    public PlayerSettings withBedHomeDisabled(boolean disabled, long updatedAt) {
        return new PlayerSettings(player, tpaDisabled, disabled, updatedAt);
    }
}
