package me.roviczzz.marp.model;

/**
 * A world-relative position with no Bukkit types attached.
 *
 * <p>Stored values keep full precision; display code rounds, and the safety
 * check reads the block coordinates.
 */
public record LocationSnapshot(String world, double x, double y, double z, float yaw, float pitch) {

    public LocationSnapshot {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
    }

    public double squaredDistance(LocationSnapshot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
