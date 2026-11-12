package me.roviczzz.marp.storage.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.storage.StorageException;

/** JDBC implementation of {@link HomeRepository}. */
public final class SqlHomeRepository implements HomeRepository {

    private final SqlStorage storage;

    public SqlHomeRepository(SqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<List<Home>> findByPlayer(UUID player) {
        return storage.query(conn -> {
            String sql = "SELECT * FROM homes WHERE player_uuid = ? ORDER BY name_key ASC, id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Home> homes = new ArrayList<>();
                    while (rs.next()) {
                        homes.add(mapRow(rs));
                    }
                    return homes;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Home>> findByPlayerAndName(UUID player, String nameKey) {
        String key = nameKey.toLowerCase(Locale.ROOT);
        return storage.query(conn -> selectByPlayerAndKey(conn, player, key));
    }

    @Override
    public CompletableFuture<Optional<Home>> findById(long id) {
        return storage.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM homes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countByPlayer(UUID player) {
        return storage.query(conn -> {
            try (PreparedStatement ps =
                    conn.prepareStatement("SELECT COUNT(*) FROM homes WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countAll() {
        return storage.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM homes");
                    ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    @Override
    public CompletableFuture<Home> insert(Home home) {
        String key = home.nameKey().toLowerCase(Locale.ROOT);
        return storage.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(storage.dialect().upsertHomeSql())) {
                LocationSnapshot loc = home.location();
                ps.setString(1, home.owner().toString());
                ps.setString(2, home.name());
                ps.setString(3, key);
                ps.setString(4, loc.world());
                ps.setDouble(5, loc.x());
                ps.setDouble(6, loc.y());
                ps.setDouble(7, loc.z());
                ps.setFloat(8, loc.yaw());
                ps.setFloat(9, loc.pitch());
                ps.setString(10, home.icon().material());
                ps.setBytes(11, home.icon().itemBytes());
                ps.setString(12, home.displayName());
                ps.setString(13, encodeLore(home.lore()));
                ps.setLong(14, home.createdAt());
                ps.setLong(15, home.updatedAt());
                ps.executeUpdate();
            }
            return selectByPlayerAndKey(conn, home.owner(), key)
                    .orElseThrow(() -> new StorageException("Upserted home row could not be read back"));
        });
    }

    @Override
    public CompletableFuture<Boolean> update(Home home, long expectedRev) {
        return storage.inTransaction(conn -> {
            String sql = """
                    UPDATE homes SET
                        world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?,
                        icon_material = ?, icon_item = ?, display_name = ?, lore = ?,
                        rev = rev + 1, updated_at = ?
                    WHERE id = ? AND player_uuid = ? AND rev = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                LocationSnapshot loc = home.location();
                ps.setString(1, loc.world());
                ps.setDouble(2, loc.x());
                ps.setDouble(3, loc.y());
                ps.setDouble(4, loc.z());
                ps.setFloat(5, loc.yaw());
                ps.setFloat(6, loc.pitch());
                ps.setString(7, home.icon().material());
                ps.setBytes(8, home.icon().itemBytes());
                ps.setString(9, home.displayName());
                ps.setString(10, encodeLore(home.lore()));
                ps.setLong(11, home.updatedAt());
                ps.setLong(12, home.id());
                ps.setString(13, home.owner().toString());
                ps.setLong(14, expectedRev);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(long id, UUID owner) {
        return storage.inTransaction(conn -> {
            try (PreparedStatement ps =
                    conn.prepareStatement("DELETE FROM homes WHERE id = ? AND player_uuid = ?")) {
                ps.setLong(1, id);
                ps.setString(2, owner.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    private Optional<Home> selectByPlayerAndKey(Connection conn, UUID player, String key) throws SQLException {
        String sql = "SELECT * FROM homes WHERE player_uuid = ? AND name_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private static Home mapRow(ResultSet rs) throws SQLException {
        LocationSnapshot loc = new LocationSnapshot(
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"));
        IconSpec icon = new IconSpec(rs.getString("icon_material"), rs.getBytes("icon_item"));
        return new Home(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("name"),
                rs.getString("name_key"),
                loc,
                icon,
                rs.getString("display_name"),
                decodeLore(rs.getString("lore")),
                rs.getLong("rev"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    static String encodeLore(List<String> lore) {
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lore.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lore.get(i).replace("\r\n", "\n").replace('\r', '\n'));
        }
        return sb.toString();
    }

    static List<String> decodeLore(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(raw.split("\n", -1));
    }
}
