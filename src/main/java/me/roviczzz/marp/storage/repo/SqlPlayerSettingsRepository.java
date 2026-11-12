package me.roviczzz.marp.storage.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.PlayerSettings;
import me.roviczzz.marp.storage.SqlStorage;

/** JDBC implementation of {@link PlayerSettingsRepository}. */
public final class SqlPlayerSettingsRepository implements PlayerSettingsRepository {

    private final SqlStorage storage;

    public SqlPlayerSettingsRepository(SqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Optional<PlayerSettings>> find(UUID player) {
        return storage.query(conn -> {
            String sql = "SELECT * FROM player_settings WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> upsert(PlayerSettings settings) {
        return storage.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(storage.dialect().upsertPlayerSettingsSql())) {
                ps.setString(1, settings.player().toString());
                ps.setInt(2, settings.tpaDisabled() ? 1 : 0);
                ps.setInt(3, settings.bedHomeDisabled() ? 1 : 0);
                ps.setLong(4, settings.updatedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static PlayerSettings mapRow(ResultSet rs) throws SQLException {
        return new PlayerSettings(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getInt("tpa_disabled") != 0,
                rs.getInt("bed_home_disabled") != 0,
                rs.getLong("updated_at"));
    }
}
