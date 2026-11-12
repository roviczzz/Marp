package me.roviczzz.marp.storage.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.storage.StorageException;

/** JDBC implementation of {@link FavoriteRepository}. */
public final class SqlFavoriteRepository implements FavoriteRepository {

    private final SqlStorage storage;

    public SqlFavoriteRepository(SqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<List<FavoriteEntry>> findByPlayer(UUID player) {
        return storage.query(conn -> {
            String sql = """
                    SELECT * FROM favorites
                    WHERE player_uuid = ?
                    ORDER BY sort_order ASC, created_at ASC
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<FavoriteEntry> entries = new ArrayList<>();
                    while (rs.next()) {
                        entries.add(mapRow(rs));
                    }
                    return entries;
                }
            }
        });
    }

    @Override
    public CompletableFuture<FavoriteEntry> add(UUID player, FavoriteKind kind, long targetId, long createdAt) {
        return storage.inTransaction(conn -> {
            Optional<FavoriteEntry> existing = selectOne(conn, player, kind, targetId);
            if (existing.isPresent()) {
                return existing.get();
            }
            int nextOrder = nextSortOrder(conn, player);
            try (PreparedStatement ps = conn.prepareStatement(storage.dialect().insertFavoriteIgnoreSql())) {
                ps.setString(1, player.toString());
                ps.setString(2, kind.name());
                ps.setLong(3, targetId);
                ps.setInt(4, nextOrder);
                ps.setLong(5, createdAt);
                ps.executeUpdate();
            }
            return selectOne(conn, player, kind, targetId)
                    .orElseThrow(() -> new StorageException("Inserted favorite row could not be read back"));
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(UUID player, FavoriteKind kind, long targetId) {
        return storage.inTransaction(conn -> {
            String sql = "DELETE FROM favorites WHERE player_uuid = ? AND target_kind = ? AND target_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                ps.setString(2, kind.name());
                ps.setLong(3, targetId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countByPlayer(UUID player) {
        return storage.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM favorites WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteByTarget(FavoriteKind kind, long targetId) {
        return storage.inTransaction(conn -> {
            String sql = "DELETE FROM favorites WHERE target_kind = ? AND target_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, kind.name());
                ps.setLong(2, targetId);
                return ps.executeUpdate();
            }
        });
    }

    private static int nextSortOrder(Connection conn, UUID player) throws SQLException {
        String sql = "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM favorites WHERE player_uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static Optional<FavoriteEntry> selectOne(
            Connection conn, UUID player, FavoriteKind kind, long targetId) throws SQLException {
        String sql = "SELECT * FROM favorites WHERE player_uuid = ? AND target_kind = ? AND target_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, kind.name());
            ps.setLong(3, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private static FavoriteEntry mapRow(ResultSet rs) throws SQLException {
        return new FavoriteEntry(
                UUID.fromString(rs.getString("player_uuid")),
                FavoriteKind.valueOf(rs.getString("target_kind")),
                rs.getLong("target_id"),
                rs.getInt("sort_order"),
                rs.getLong("created_at"));
    }
}
