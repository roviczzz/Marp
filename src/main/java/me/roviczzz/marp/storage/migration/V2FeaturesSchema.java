package me.roviczzz.marp.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v2: adds {@code homes.is_bed}, {@code player_settings}, and
 * {@code favorites} with target lookup index.
 */
public final class V2FeaturesSchema implements Migration {

    @Override
    public int version() {
        return 2;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE homes ADD COLUMN is_bed INTEGER NOT NULL DEFAULT 0");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_settings (
                        player_uuid        TEXT    PRIMARY KEY,
                        tpa_disabled       INTEGER NOT NULL DEFAULT 0,
                        bed_home_disabled  INTEGER NOT NULL DEFAULT 0,
                        updated_at         INTEGER NOT NULL
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS favorites (
                        player_uuid  TEXT    NOT NULL,
                        target_kind  TEXT    NOT NULL,
                        target_id    INTEGER NOT NULL,
                        sort_order   INTEGER NOT NULL,
                        created_at   INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, target_kind, target_id)
                    )
                    """);

            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_favorites_target
                    ON favorites (target_kind, target_id)
                    """);
        }
    }
}
