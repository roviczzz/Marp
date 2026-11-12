package me.roviczzz.marp.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v1 from DESIGN.md §5.2.
 *
 * <p>No extra indexes are added on {@code homes(player_uuid, name_key)}: the
 * {@code UNIQUE} constraint already builds the composite index used for player
 * lookups. {@code AUTOINCREMENT} is kept deliberately so a stale menu row can
 * never resolve to a recycled id.
 */
public final class V1InitialSchema implements Migration {

    @Override
    public int version() {
        return 1;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS homes (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid    TEXT    NOT NULL,
                        name           TEXT    NOT NULL,
                        name_key       TEXT    NOT NULL,
                        world          TEXT    NOT NULL,
                        x              REAL    NOT NULL,
                        y              REAL    NOT NULL,
                        z              REAL    NOT NULL,
                        yaw            REAL    NOT NULL,
                        pitch          REAL    NOT NULL,
                        icon_material  TEXT    NOT NULL,
                        icon_item      BLOB,
                        display_name   TEXT,
                        lore           TEXT,
                        rev            INTEGER NOT NULL DEFAULT 0,
                        created_at     INTEGER NOT NULL,
                        updated_at     INTEGER NOT NULL,
                        UNIQUE (player_uuid, name_key)
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS warps (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        name           TEXT    NOT NULL,
                        name_key       TEXT    NOT NULL UNIQUE,
                        world          TEXT    NOT NULL,
                        x              REAL    NOT NULL,
                        y              REAL    NOT NULL,
                        z              REAL    NOT NULL,
                        yaw            REAL    NOT NULL,
                        pitch          REAL    NOT NULL,
                        icon_material  TEXT    NOT NULL,
                        icon_item      BLOB,
                        display_name   TEXT,
                        lore           TEXT,
                        permission     TEXT,
                        created_by     TEXT,
                        rev            INTEGER NOT NULL DEFAULT 0,
                        created_at     INTEGER NOT NULL,
                        updated_at     INTEGER NOT NULL
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS back_locations (
                        player_uuid  TEXT    NOT NULL,
                        kind         TEXT    NOT NULL,
                        world        TEXT    NOT NULL,
                        x            REAL    NOT NULL,
                        y            REAL    NOT NULL,
                        z            REAL    NOT NULL,
                        yaw          REAL    NOT NULL,
                        pitch        REAL    NOT NULL,
                        cause        TEXT,
                        created_at   INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, kind)
                    )
                    """);
        }
    }
}
