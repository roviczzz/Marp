package me.roviczzz.marp.storage.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.storage.StorageException;

/** JDBC implementation of {@link WarpRepository}. */
public final class SqlWarpRepository implements WarpRepository {

    private final SqlStorage storage;

    public SqlWarpRepository(SqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<List<Warp>> findAll() {
        return storage.query(conn -> {
            String sql = "SELECT * FROM warps ORDER BY name_key ASC, id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                List<Warp> warps = new ArrayList<>();
                while (rs.next()) {
                    warps.add(mapRow(rs));
                }
                return warps;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Warp>> findByKey(String nameKey) {
        String key = nameKey.toLowerCase(Locale.ROOT);
        return storage.query(conn -> selectByKey(conn, key));
    }

    @Override
    public CompletableFuture<Optional<Warp>> findById(long id) {
        return storage.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM warps WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<Warp>> findPage(int offset, int limit) {
        return storage.query(conn -> {
            String sql = "SELECT * FROM warps ORDER BY name_key ASC, id ASC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, Math.max(1, limit));
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    List<Warp> warps = new ArrayList<>();
                    while (rs.next()) {
                        warps.add(mapRow(rs));
                    }
                    return warps;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> count() {
        return storage.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM warps");
                    ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    @Override
    public CompletableFuture<Warp> insert(Warp warp) {
        String key = warp.nameKey().toLowerCase(Locale.ROOT);
        return storage.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(storage.dialect().upsertWarpSql())) {
                LocationSnapshot loc = warp.location();
                ps.setString(1, warp.name());
                ps.setString(2, key);
                ps.setString(3, loc.world());
                ps.setDouble(4, loc.x());
                ps.setDouble(5, loc.y());
                ps.setDouble(6, loc.z());
                ps.setFloat(7, loc.yaw());
                ps.setFloat(8, loc.pitch());
                ps.setString(9, warp.icon().material());
                ps.setBytes(10, warp.icon().itemBytes());
                ps.setString(11, warp.displayName());
                ps.setString(12, SqlHomeRepository.encodeLore(warp.lore()));
                ps.setString(13, warp.permission());
                ps.setString(14, warp.createdBy() == null ? null : warp.createdBy().toString());
                ps.setLong(15, warp.createdAt());
                ps.setLong(16, warp.updatedAt());
                ps.executeUpdate();
            }
            return selectByKey(conn, key)
                    .orElseThrow(() -> new StorageException("Upserted warp row could not be read back"));
        });
    }

    @Override
    public CompletableFuture<Boolean> update(Warp warp, long expectedRev) {
        return storage.inTransaction(conn -> {
            String sql = """
                    UPDATE warps SET
                        world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?,
                        icon_material = ?, icon_item = ?, display_name = ?, lore = ?,
                        permission = ?, rev = rev + 1, updated_at = ?
                    WHERE id = ? AND rev = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                LocationSnapshot loc = warp.location();
                ps.setString(1, loc.world());
                ps.setDouble(2, loc.x());
                ps.setDouble(3, loc.y());
                ps.setDouble(4, loc.z());
                ps.setFloat(5, loc.yaw());
                ps.setFloat(6, loc.pitch());
                ps.setString(7, warp.icon().material());
                ps.setBytes(8, warp.icon().itemBytes());
                ps.setString(9, warp.displayName());
                ps.setString(10, SqlHomeRepository.encodeLore(warp.lore()));
                ps.setString(11, warp.permission());
                ps.setLong(12, warp.updatedAt());
                ps.setLong(13, warp.id());
                ps.setLong(14, expectedRev);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(long id) {
        return storage.inTransaction(conn -> {
            boolean deleted;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM warps WHERE id = ?")) {
                ps.setLong(1, id);
                deleted = ps.executeUpdate() == 1;
            }
            if (deleted) {
                try (PreparedStatement favPs = conn.prepareStatement(
                        "DELETE FROM favorites WHERE target_kind = 'WARP' AND target_id = ?")) {
                    favPs.setLong(1, id);
                    favPs.executeUpdate();
                }
            }
            return deleted;
        });
    }

    private Optional<Warp> selectByKey(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM warps WHERE name_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private static Warp mapRow(ResultSet rs) throws SQLException {
        LocationSnapshot loc = new LocationSnapshot(
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"));
        IconSpec icon = new IconSpec(rs.getString("icon_material"), rs.getBytes("icon_item"));
        String createdByRaw = rs.getString("created_by");
        UUID createdBy = createdByRaw == null || createdByRaw.isBlank() ? null : UUID.fromString(createdByRaw);
        return new Warp(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("name_key"),
                loc,
                icon,
                rs.getString("display_name"),
                SqlHomeRepository.decodeLore(rs.getString("lore")),
                rs.getString("permission"),
                createdBy,
                rs.getLong("rev"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
