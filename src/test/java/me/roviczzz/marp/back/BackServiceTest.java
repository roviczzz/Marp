package me.roviczzz.marp.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import me.roviczzz.marp.back.BackService.FallbackDecision;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.storage.repo.BackRepository;
import me.roviczzz.marp.teleport.TeleportReason;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BackServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final LocationSnapshot DEATH_LOC = new LocationSnapshot("world", 10, 64, 10, 0, 0);
    private static final LocationSnapshot HOME_ORIGIN = new LocationSnapshot("world", 50, 70, 50, 0, 0);
    private static final LocationSnapshot SAFE_GROUND = new LocationSnapshot("world", 12, 65, 12, 0, 0);

    @Test
    void deathThenHomeNewestWinsAndBackNeverRecordsItself() {
        FakeBackRepository repo = new FakeBackRepository();
        AtomicLong clock = new AtomicLong(100L);
        YamlConfiguration yaml = new YamlConfiguration();
        BackService service = new BackService(repo, () -> new MarpConfig(yaml), clock::get);

        service.recordDeath(PLAYER, DEATH_LOC, "LAVA").join();
        assertEquals(DEATH_LOC, service.resolve(PLAYER).join().orElseThrow().location());

        // Later /home teleport becomes the newest back target.
        clock.set(200L);
        service.recordTeleportOrigin(PLAYER, HOME_ORIGIN, TeleportReason.HOME).join();
        assertEquals(HOME_ORIGIN, service.resolve(PLAYER).join().orElseThrow().location());

        // /back itself must not record a new origin, avoiding ping-pong.
        clock.set(300L);
        LocationSnapshot afterBack = new LocationSnapshot("world", 99, 64, 99, 0, 0);
        service.recordTeleportOrigin(PLAYER, afterBack, TeleportReason.BACK).join();
        assertEquals(HOME_ORIGIN, service.resolve(PLAYER).join().orElseThrow().location());

        // With consume: false (default), target stays after use.
        service.consumeIfConfigured(PLAYER, BackKind.TELEPORT).join();
        assertEquals(HOME_ORIGIN, service.resolve(PLAYER).join().orElseThrow().location());

        // With consume: true, consuming removes the TELEPORT target and reveals the older DEATH target.
        yaml.set("back.consume", true);
        service.consumeIfConfigured(PLAYER, BackKind.TELEPORT).join();
        assertEquals(DEATH_LOC, service.resolve(PLAYER).join().orElseThrow().location());
    }

    @Test
    void unreachableTargetFallsBackToLastGroundUnlessDisabledOrMissing() {
        FakeBackRepository repo = new FakeBackRepository();
        YamlConfiguration yaml = new YamlConfiguration();
        BackService service = new BackService(repo, () -> new MarpConfig(yaml), () -> 100L);

        // No ground recorded yet -> NoGroundRecorded.
        assertInstanceOf(FallbackDecision.NoGroundRecorded.class, service.resolveUnreachableFallback(PLAYER));

        // Record ground -> UseLastGround.
        service.recordLastGround(PLAYER, SAFE_GROUND);
        FallbackDecision decision = service.resolveUnreachableFallback(PLAYER);
        assertEquals(SAFE_GROUND, assertInstanceOf(FallbackDecision.UseLastGround.class, decision).location());

        // Fallback disabled -> Disabled.
        yaml.set("back.unreachable-fallback", "BLOCK");
        assertInstanceOf(FallbackDecision.Disabled.class, service.resolveUnreachableFallback(PLAYER));

        // Quit clears transient last-ground.
        yaml.set("back.unreachable-fallback", "LAST_GROUND");
        service.clearPlayerTransient(PLAYER);
        assertTrue(service.lastGround(PLAYER).isEmpty());
    }

    private static final class FakeBackRepository implements BackRepository {
        private final Map<UUID, Map<BackKind, BackEntry>> store = new HashMap<>();

        @Override
        public CompletableFuture<Optional<BackEntry>> find(UUID player, BackKind kind) {
            Map<BackKind, BackEntry> map = store.get(player);
            return CompletableFuture.completedFuture(
                    map == null ? Optional.empty() : Optional.ofNullable(map.get(kind)));
        }

        @Override
        public CompletableFuture<List<BackEntry>> findAll(UUID player) {
            Map<BackKind, BackEntry> map = store.get(player);
            return CompletableFuture.completedFuture(
                    map == null ? List.of() : List.copyOf(map.values()));
        }

        @Override
        public CompletableFuture<Void> upsert(BackEntry entry) {
            store.computeIfAbsent(entry.player(), ignored -> new EnumMap<>(BackKind.class))
                    .put(entry.kind(), entry);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> delete(UUID player, BackKind kind) {
            Map<BackKind, BackEntry> map = store.get(player);
            return CompletableFuture.completedFuture(map != null && map.remove(kind) != null);
        }
    }
}
