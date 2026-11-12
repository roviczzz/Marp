package me.roviczzz.marp.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Isolates the small differences between SQLite and a future MySQL backend.
 *
 * <p>v0.1 ships only the SQLite implementation; keeping the upsert syntax and
 * pragmas here means repositories do not need to change when MySQL is added.
 */
public enum SqlDialect {
    SQLITE,
    MYSQL;

    public void applyPragmas(Connection connection, int busyTimeoutMs, boolean wal) throws SQLException {
        if (this != SQLITE) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            if (wal) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
            }
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA busy_timeout = " + Math.max(0, busyTimeoutMs));
        }
    }

    public void checkpointTruncate(Connection connection) throws SQLException {
        if (this != SQLITE) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    public String upsertHomeSql() {
        return switch (this) {
            case SQLITE -> """
                    INSERT INTO homes (
                        player_uuid, name, name_key, world, x, y, z, yaw, pitch,
                        icon_material, icon_item, display_name, lore, is_bed, rev, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    ON CONFLICT (player_uuid, name_key) DO UPDATE SET
                        world = excluded.world,
                        x = excluded.x,
                        y = excluded.y,
                        z = excluded.z,
                        yaw = excluded.yaw,
                        pitch = excluded.pitch,
                        rev = homes.rev + 1,
                        updated_at = excluded.updated_at
                    """;
            case MYSQL -> """
                    INSERT INTO homes (
                        player_uuid, name, name_key, world, x, y, z, yaw, pitch,
                        icon_material, icon_item, display_name, lore, is_bed, rev, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        world = VALUES(world),
                        x = VALUES(x),
                        y = VALUES(y),
                        z = VALUES(z),
                        yaw = VALUES(yaw),
                        pitch = VALUES(pitch),
                        rev = rev + 1,
                        updated_at = VALUES(updated_at)
                    """;
        };
    }

    public String upsertWarpSql() {
        return switch (this) {
            case SQLITE -> """
                    INSERT INTO warps (
                        name, name_key, world, x, y, z, yaw, pitch,
                        icon_material, icon_item, display_name, lore, permission, created_by,
                        rev, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    ON CONFLICT (name_key) DO UPDATE SET
                        world = excluded.world,
                        x = excluded.x,
                        y = excluded.y,
                        z = excluded.z,
                        yaw = excluded.yaw,
                        pitch = excluded.pitch,
                        rev = warps.rev + 1,
                        updated_at = excluded.updated_at
                    """;
            case MYSQL -> """
                    INSERT INTO warps (
                        name, name_key, world, x, y, z, yaw, pitch,
                        icon_material, icon_item, display_name, lore, permission, created_by,
                        rev, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        world = VALUES(world),
                        x = VALUES(x),
                        y = VALUES(y),
                        z = VALUES(z),
                        yaw = VALUES(yaw),
                        pitch = VALUES(pitch),
                        rev = rev + 1,
                        updated_at = VALUES(updated_at)
                    """;
        };
    }

    public String upsertBackSql() {
        return switch (this) {
            case SQLITE -> """
                    INSERT INTO back_locations (
                        player_uuid, kind, world, x, y, z, yaw, pitch, cause, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (player_uuid, kind) DO UPDATE SET
                        world = excluded.world,
                        x = excluded.x,
                        y = excluded.y,
                        z = excluded.z,
                        yaw = excluded.yaw,
                        pitch = excluded.pitch,
                        cause = excluded.cause,
                        created_at = excluded.created_at
                    """;
            case MYSQL -> """
                    INSERT INTO back_locations (
                        player_uuid, kind, world, x, y, z, yaw, pitch, cause, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        world = VALUES(world),
                        x = VALUES(x),
                        y = VALUES(y),
                        z = VALUES(z),
                        yaw = VALUES(yaw),
                        pitch = VALUES(pitch),
                        cause = VALUES(cause),
                        created_at = VALUES(created_at)
                    """;
        };
    }

    public String upsertPlayerSettingsSql() {
        return switch (this) {
            case SQLITE -> """
                    INSERT INTO player_settings (
                        player_uuid, tpa_disabled, bed_home_disabled, updated_at
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT (player_uuid) DO UPDATE SET
                        tpa_disabled = excluded.tpa_disabled,
                        bed_home_disabled = excluded.bed_home_disabled,
                        updated_at = excluded.updated_at
                    """;
            case MYSQL -> """
                    INSERT INTO player_settings (
                        player_uuid, tpa_disabled, bed_home_disabled, updated_at
                    ) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        tpa_disabled = VALUES(tpa_disabled),
                        bed_home_disabled = VALUES(bed_home_disabled),
                        updated_at = VALUES(updated_at)
                    """;
        };
    }

    public String insertFavoriteIgnoreSql() {
        return switch (this) {
            case SQLITE -> """
                    INSERT INTO favorites (
                        player_uuid, target_kind, target_id, sort_order, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (player_uuid, target_kind, target_id) DO NOTHING
                    """;
            case MYSQL -> """
                    INSERT IGNORE INTO favorites (
                        player_uuid, target_kind, target_id, sort_order, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """;
        };
    }
}
