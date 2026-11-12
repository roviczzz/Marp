package me.roviczzz.marp.util;

import me.roviczzz.marp.model.LocationSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Converts between Bukkit {@link Location}s (main thread only) and immutable
 * {@link LocationSnapshot}s.
 */
public final class LocationCodec {

    private LocationCodec() {
    }

    public static LocationSnapshot fromLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location has no world");
        }
        return new LocationSnapshot(
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    /**
     * Resolves a snapshot against currently loaded worlds. Never auto-loads an
     * unloaded world.
     */
    public static Location toLocation(LocationSnapshot snapshot) {
        World world = Bukkit.getWorld(snapshot.world());
        if (world == null) {
            return null;
        }
        return new Location(
                world,
                snapshot.x(),
                snapshot.y(),
                snapshot.z(),
                snapshot.yaw(),
                snapshot.pitch());
    }
}
