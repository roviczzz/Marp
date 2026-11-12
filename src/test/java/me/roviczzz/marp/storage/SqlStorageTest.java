package me.roviczzz.marp.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.roviczzz.marp.favorite.FavoriteCache;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.PlayerSettings;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.settings.PlayerSettingsService;
import me.roviczzz.marp.storage.migration.MigrationRunner;
import me.roviczzz.marp.storage.migration.V1InitialSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void dbExecutorRunsOnNamedThreadAndHonoursShutdownBound() throws Exception {
        DbExecutor executor = new DbExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            String threadName = executor.submit(() -> Thread.currentThread().getName()).join();
            assertEquals(DbExecutor.THREAD_NAME, threadName);

            CountDownLatch started = new CountDownLatch(1);
            executor.submitRun(() -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            long before = System.nanoTime();
            boolean terminated = executor.shutdown(Duration.ofMillis(80));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertFalse(terminated);
            assertTrue(elapsedMs < 1_000L, "shutdown waited too long: " + elapsedMs + "ms");
        } finally {
            release.countDown();
            executor.shutdown(Duration.ofSeconds(2));
        }
    }

    @Test
    void v1SchemaAppliesPragmasTablesAndUniqueConstraint() throws Exception {
        Path dbFile = tempDir.resolve("schema.db");
        SqlStorage storage = new SqlStorage(dbFile, 5000, true);
        try {
            storage.init().join();
            assertTrue(storage.isReady());

            String journalMode = storage.query(conn -> {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
                    return rs.next() ? rs.getString(1) : "";
                }
            }).join();
            assertEquals("wal", journalMode.toLowerCase());

            int version = storage.query(MigrationRunner::readVersion).join();
            assertEquals(2, version);

            List<String> tables = storage.query(conn -> {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                    return names;
                }
            }).join();
            assertTrue(tables.containsAll(List.of(
                    "marp_schema", "homes", "warps", "back_locations", "player_settings", "favorites")));

            List<String> indexes = storage.query(conn -> {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type = 'index'")) {
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                    return names;
                }
            }).join();
            assertTrue(indexes.contains("idx_favorites_target"));
        } finally {
            assertTrue(storage.shutdown(Duration.ofSeconds(5)));
        }
    }

    @Test
    void migrationRunnerUpgradesV1ToV2AndRefusesNewerSchema() throws Exception {
        Path dbFile = tempDir.resolve("migrate.db");
        UUID owner = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement stmt = conn.createStatement()) {
            new MigrationRunner(List.of(new V1InitialSchema())).migrate(conn);
            assertEquals(1, MigrationRunner.readVersion(conn));
            stmt.execute("""
                    INSERT INTO homes (
                        player_uuid, name, name_key, world, x, y, z, yaw, pitch,
                        icon_material, rev, created_at, updated_at
                    ) VALUES ('%s', 'base', 'base', 'world', 1, 64, 1, 0, 0, 'GRASS_BLOCK', 0, 10, 10)
                    """.formatted(owner));

            assertEquals(2, MigrationRunner.standard().migrate(conn));
            assertEquals(2, MigrationRunner.readVersion(conn));

            try (ResultSet rs = stmt.executeQuery("SELECT name_key, is_bed FROM homes WHERE player_uuid = '" + owner + "'")) {
                assertTrue(rs.next());
                assertEquals("base", rs.getString("name_key"));
                assertEquals(0, rs.getInt("is_bed"));
            }

            stmt.execute("UPDATE marp_schema SET version = 3");
            assertThrows(StorageException.class, () -> MigrationRunner.standard().migrate(conn));
            assertEquals(3, MigrationRunner.readVersion(conn));
        }

        // SqlStorage.init must also fail cleanly on the newer file and close the connection.
        SqlStorage storage = new SqlStorage(dbFile, 5000, true);
        try {
            assertThrows(Exception.class, () -> storage.init().join());
            assertFalse(storage.isReady());
        } finally {
            storage.shutdown(Duration.ofSeconds(2));
        }
        assertTrue(Files.deleteIfExists(dbFile));
    }

    @Test
    void unwritablePathFailsCleanlyWithoutLocking() throws Exception {
        Path blocker = tempDir.resolve("not-a-directory");
        Files.writeString(blocker, "file");
        SqlStorage storage = new SqlStorage(blocker.resolve("marp.db"), 5000, true);
        try {
            assertThrows(Exception.class, () -> storage.init().join());
            assertFalse(storage.isReady());
        } finally {
            storage.shutdown(Duration.ofSeconds(2));
        }
        assertTrue(Files.deleteIfExists(blocker));
    }

    @Test
    void homeRepositoryUpsertOptimisticLockAndOwnershipDelete() {
        SqlStorage storage = new SqlStorage(tempDir.resolve("homes.db"), 5000, true);
        try {
            storage.init().join();
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            LocationSnapshot firstLoc = new LocationSnapshot("world", 1, 64, 1, 0, 0);
            LocationSnapshot movedLoc = new LocationSnapshot("world_nether", 10, 70, -5, 90, 0);

            Home initial = new Home(
                    0L,
                    owner,
                    "base",
                    "base",
                    firstLoc,
                    new IconSpec("DIAMOND_SWORD", new byte[] {1, 2, 3}),
                    "<gold>Base",
                    List.of("line 1", "line 2"),
                    0L,
                    100L,
                    100L);
            Home created = storage.homes().insert(initial).join();
            assertTrue(created.id() > 0);
            assertEquals(0L, created.rev());

            // Case-insensitive lookup.
            assertEquals(
                    Optional.of(created),
                    storage.homes().findByPlayerAndName(owner, "BASE").join());

            // Duplicate-key upsert moves the home and preserves icon/display/lore while bumping rev.
            Home overwrite = new Home(
                    0L,
                    owner,
                    "base",
                    "base",
                    movedLoc,
                    IconSpec.material("GRASS_BLOCK"),
                    null,
                    List.of(),
                    0L,
                    200L,
                    200L);
            Home updated = storage.homes().insert(overwrite).join();
            assertEquals(created.id(), updated.id());
            assertEquals(1L, updated.rev());
            assertEquals(movedLoc, updated.location());
            assertEquals("<gold>Base", updated.displayName());
            assertEquals(List.of("line 1", "line 2"), updated.lore());
            assertEquals("DIAMOND_SWORD", updated.icon().material());

            // Stale rev is rejected; matching rev succeeds and bumps rev to 2.
            Home edited = updated.withMetadata(IconSpec.material("BEACON"), "Edited", List.of("ok"), updated.rev(), 300L);
            assertFalse(storage.homes().update(edited, 0L).join());
            assertTrue(storage.homes().update(edited, 1L).join());
            assertEquals(2L, storage.homes().findById(created.id()).join().orElseThrow().rev());

            // Another player cannot delete the home.
            assertFalse(storage.homes().delete(created.id(), stranger).join());
            assertEquals(1, storage.homes().countByPlayer(owner).join());
            assertTrue(storage.homes().delete(created.id(), owner).join());
            assertEquals(0, storage.homes().countByPlayer(owner).join());
        } finally {
            storage.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void warpRepositoryGlobalUniquenessUpsertAndPaging() {
        SqlStorage storage = new SqlStorage(tempDir.resolve("warps.db"), 5000, true);
        try {
            storage.init().join();
            UUID admin = UUID.randomUUID();
            LocationSnapshot first = new LocationSnapshot("world", 0, 64, 0, 0, 0);
            LocationSnapshot moved = new LocationSnapshot("world", 50, 70, 50, 180, 0);

            Warp spawn = new Warp(
                    0L,
                    "spawn",
                    "spawn",
                    first,
                    IconSpec.material("ENDER_PEARL"),
                    "<aqua>Spawn",
                    List.of("Hub"),
                    null,
                    admin,
                    0L,
                    10L,
                    10L);
            Warp created = storage.warps().insert(spawn).join();
            assertEquals(0L, created.rev());

            Warp relocated = new Warp(
                    0L, "spawn", "SPAWN", moved, IconSpec.material("STONE"), null, List.of(), null, admin, 0L, 20L, 20L);
            Warp afterMove = storage.warps().insert(relocated).join();
            assertEquals(created.id(), afterMove.id());
            assertEquals(1L, afterMove.rev());
            assertEquals(moved, afterMove.location());
            assertEquals("<aqua>Spawn", afterMove.displayName());

            storage.warps().insert(new Warp(
                    0L, "arena", "arena", first, IconSpec.material("IRON_SWORD"), null, List.of(), null, admin, 0L, 30L, 30L)).join();

            assertEquals(2, storage.warps().count().join());
            List<Warp> page1 = storage.warps().findPage(0, 1).join();
            List<Warp> page2 = storage.warps().findPage(1, 1).join();
            assertEquals("arena", page1.get(0).nameKey());
            assertEquals("spawn", page2.get(0).nameKey());
        } finally {
            storage.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void backRepositoryKeepsDeathAndTeleportIndependently() {
        SqlStorage storage = new SqlStorage(tempDir.resolve("back.db"), 5000, true);
        try {
            storage.init().join();
            UUID player = UUID.randomUUID();
            LocationSnapshot firstDeath = new LocationSnapshot("world", 1, 64, 1, 0, 0);
            LocationSnapshot secondDeath = new LocationSnapshot("world", 2, 64, 2, 0, 0);
            LocationSnapshot teleOrigin = new LocationSnapshot("world", 9, 64, 9, 0, 0);

            storage.back().upsert(new BackEntry(player, BackKind.DEATH, firstDeath, "FALL", 100L)).join();
            storage.back().upsert(new BackEntry(player, BackKind.DEATH, secondDeath, "LAVA", 200L)).join();
            storage.back().upsert(new BackEntry(player, BackKind.TELEPORT, teleOrigin, "HOME", 150L)).join();

            assertEquals(2, storage.back().findAll(player).join().size());
            assertEquals(secondDeath, storage.back().find(player, BackKind.DEATH).join().orElseThrow().location());

            assertTrue(storage.back().delete(player, BackKind.DEATH).join());
            assertTrue(storage.back().find(player, BackKind.DEATH).join().isEmpty());
            assertTrue(storage.back().find(player, BackKind.TELEPORT).join().isPresent());
        } finally {
            storage.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void bedHomeCountAndTransactionalFavoriteCleanupAndSettingsPersistence() {
        Path dbFile = tempDir.resolve("v2features.db");
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        LocationSnapshot loc = new LocationSnapshot("world", 10, 64, 10, 0, 0);

        long homeId;
        long warpId;
        SqlStorage storage = new SqlStorage(dbFile, 5000, true);
        try {
            storage.init().join();

            Home manual = storage.homes().insert(new Home(
                    0L, player, "base", "base", loc, IconSpec.material("GRASS_BLOCK"), null, List.of(), false, 0L, 10L, 10L)).join();
            Home bed = storage.homes().insert(new Home(
                    0L, player, "bed", "bed", loc, IconSpec.material("RED_BED"), null, List.of(), true, 0L, 20L, 20L)).join();
            Home movedBed = storage.homes().insert(new Home(
                    0L, player, "bed", "bed", new LocationSnapshot("world", 20, 64, 20, 0, 0),
                    IconSpec.material("WHITE_BED"), null, List.of(), true, 0L, 30L, 30L)).join();
            assertTrue(movedBed.isBed());
            assertEquals(bed.id(), movedBed.id());
            assertEquals(2, storage.homes().countByPlayer(player).join());
            assertEquals(1, storage.homes().countManualByPlayer(player).join());

            Warp spawn = storage.warps().insert(new Warp(
                    0L, "spawn", "spawn", loc, IconSpec.material("ENDER_PEARL"), null, List.of(), null, player, 0L, 10L, 10L)).join();
            homeId = manual.id();
            warpId = spawn.id();

            FavoriteEntry f1 = storage.favorites().add(player, FavoriteKind.HOME, homeId, 100L).join();
            FavoriteEntry dup = storage.favorites().add(player, FavoriteKind.HOME, homeId, 150L).join();
            assertEquals(f1.sortOrder(), dup.sortOrder());
            FavoriteEntry f2 = storage.favorites().add(player, FavoriteKind.WARP, warpId, 200L).join();
            storage.favorites().add(other, FavoriteKind.WARP, warpId, 300L).join();
            assertEquals(0, f1.sortOrder());
            assertEquals(1, f2.sortOrder());
            assertEquals(2, storage.favorites().countByPlayer(player).join());

            // Deleting home or warp removes its favorite mappings in the same transaction.
            assertTrue(storage.homes().delete(homeId, player).join());
            assertEquals(1, storage.favorites().countByPlayer(player).join());
            assertTrue(storage.warps().delete(warpId).join());
            assertEquals(0, storage.favorites().countByPlayer(player).join());
            assertEquals(0, storage.favorites().countByPlayer(other).join());

            // PlayerSettingsService toggles and persists across restart.
            PlayerSettingsService settingsService = new PlayerSettingsService(storage.playerSettings(), () -> 500L);
            PlayerSettings toggled = settingsService.toggleTpa(player).join();
            assertTrue(toggled.tpaDisabled());
            assertFalse(toggled.bedHomeDisabled());
            PlayerSettings both = settingsService.setBedHomeDisabled(player, true).join();
            assertTrue(both.tpaDisabled());
            assertTrue(both.bedHomeDisabled());
        } finally {
            storage.shutdown(Duration.ofSeconds(5));
        }

        // Reopen database and confirm persisted settings and FavoriteCache.
        SqlStorage reopened = new SqlStorage(dbFile, 5000, true);
        try {
            reopened.init().join();
            PlayerSettingsService reloadedService = new PlayerSettingsService(reopened.playerSettings(), () -> 900L);
            PlayerSettings loaded = reloadedService.load(player).join();
            assertTrue(loaded.tpaDisabled());
            assertTrue(loaded.bedHomeDisabled());

            FavoriteCache favCache = new FavoriteCache();
            FavoriteEntry e = new FavoriteEntry(player, FavoriteKind.HOME, 99L, 0, 10L);
            favCache.putAll(player, List.of(e));
            assertTrue(favCache.isFavorite(player, FavoriteKind.HOME, 99L));
            favCache.removeTargetEverywhere(FavoriteKind.HOME, 99L);
            assertFalse(favCache.isFavorite(player, FavoriteKind.HOME, 99L));
        } finally {
            reopened.shutdown(Duration.ofSeconds(5));
        }
    }
}
