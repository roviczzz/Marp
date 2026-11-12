package me.roviczzz.marp.storage.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.storage.SqlStorage;

/** JDBC implementation of {@link BackRepository}. */
public final class SqlBackRepository implements BackRepository {

    private final SqlStorage storage;

    public SqlBackRepository(SqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Optional<BackEntry>> find(UUID player, BackKind kind) {
        return storage.query(conn -> {
            String sql = "SELECT * FROM back_locations WHERE player_uuid = ? AND kind = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                ps.setString(2, kind.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<BackEntry>> findAll(UUID player) {
        return storage.query(conn -> {
            String sql = "SELECT * FROM back_locations WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<BackEntry> entries = new ArrayList<>(2);
                    while (rs.next()) {
                        entries.add(mapRow(rs));
                    }
                    return entries;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> upsert(BackEntry entry) {
        return storage.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(storage.dialect().upsertBackSql())) {
                LocationSnapshot loc = entry.location();
                ps.setString(1, entry.player().toString());
                ps.setString(2, entry.kind().name());
                ps.setString(3, loc.world());
                ps.setDouble(4, loc.x());
                ps.setDouble(5, loc.y());
                ps.setDouble(6, loc.z());
                ps.setFloat(7, loc.yaw());
                ps.setFloat(8, loc.pitch());
                ps.setString(9, entry.cause());
                ps.setLong(10, entry.createdAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID player, BackKind kind) {
        return storage.inTransaction(conn -> {
            String sql = "DELETE FROM back_locations WHERE player_uuid = ? AND kind = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                ps.setString(2, kind.name());
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static BackEntry mapRow(ResultSet rs) throws SQLException {
        LocationSnapshot loc = new LocationSnapshot(
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"));
        return new BackEntry(
                UUID.fromString(rs.getString("player_uuid")),
                BackKind.valueOf(rs.getString("kind")),
                loc,
                rs.getString("cause"),
                rs.getLong("created_at"));
    }
}
