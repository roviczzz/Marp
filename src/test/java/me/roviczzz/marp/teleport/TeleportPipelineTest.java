package me.roviczzz.marp.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.storage.repo.BackRepository;
import me.roviczzz.marp.teleport.TeleportPlatform.PlayerContext;
import me.roviczzz.marp.teleport.TeleportService.Outcome;
import me.roviczzz.marp.teleport.TeleportSession.CancelReason;
import me.roviczzz.marp.teleport.WarmupSampler.PlayerStateProvider;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TeleportPipelineTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final LocationSnapshot ORIGIN = new LocationSnapshot("world", 0.5, 64.0, 0.5, 0, 0);
    private static final LocationSnapshot TARGET_A = new LocationSnapshot("world", 10.5, 64.0, 10.5, 0, 0);
    private static final LocationSnapshot TARGET_B = new LocationSnapshot("world", 20.5, 64.0, 20.5, 0, 0);

    @Test
    void replacingSessionInvalidatesOldTokenWithoutSendingCancelMessage() {
        TeleportSessionRegistry registry = new TeleportSessionRegistry();
        List<CancelReason> messages = new ArrayList<>();

        TeleportSession first = registry.start(
                PLAYER, TeleportReason.HOME, "first", ORIGIN, TARGET_A, null, 3);
        TeleportSession second = registry.start(
                PLAYER, TeleportReason.HOME, "second", ORIGIN, TARGET_B, null, 3);

        assertTrue(first.cancelled());
        assertFalse(registry.isCurrent(first));
        assertTrue(registry.isCurrent(second));

        // Attempting to cancel the stale first token sends no message and leaves second intact.
        assertFalse(registry.cancelToken(
                PLAYER, first.token(), CancelReason.MOVED, (u, reason) -> messages.add(reason)));
        assertTrue(messages.isEmpty());
        assertTrue(registry.isCurrent(second));
    }

    @Test
    void warmupZeroAndBypassArriveImmediatelyWhileFailedTeleportDoesNotStartCooldownOrRecordBack() {
        FakeTeleportEnv env = new FakeTeleportEnv();
        env.yaml.set("teleport.warmup.home", 0);

        Outcome ok = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_A, null).join();
        assertInstanceOf(Outcome.Teleported.class, ok);
        assertEquals(5L, env.cooldowns.remainingSeconds(PLAYER, TeleportReason.HOME, false));
        assertEquals(ORIGIN, env.backService.resolve(PLAYER).join().orElseThrow().location());
        assertTrue(env.registry.get(PLAYER).isEmpty());

        // Clear cooldown & back; test warmup > 0 with bypassWarmup = true.
        env.cooldowns.clearAll();
        env.backRepo.clear();
        env.yaml.set("teleport.warmup.home", 3);
        env.bypassWarmup = true;

        Outcome bypassed = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_B, null).join();
        assertInstanceOf(Outcome.Teleported.class, bypassed);
        assertTrue(env.registry.get(PLAYER).isEmpty());

        // Now force teleportAsync to return false: no cooldown starts, no back entry is written, session is cleaned.
        env.cooldowns.clearAll();
        env.backRepo.clear();
        env.teleportSucceeded = false;

        Outcome failed = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_A, null).join();
        assertInstanceOf(Outcome.Failed.class, failed);
        assertEquals(0L, env.cooldowns.remainingSeconds(PLAYER, TeleportReason.HOME, false));
        assertTrue(env.backService.resolve(PLAYER).join().isEmpty());
        assertTrue(env.registry.get(PLAYER).isEmpty());
        assertTrue(env.messagesSent.contains(Messages.TELEPORT_FAILED));
    }

    @Test
    void warmupSamplerHandlesCountdownMovementPoisonHitAndLastGround() {
        FakeTeleportEnv env = new FakeTeleportEnv();
        env.yaml.set("teleport.warmup.home", 2); // 2 seconds = 40 ticks

        // 1. Start a warmup, tick 1 second (5 * 4 ticks), then move > 0.5 blocks -> cancelled(MOVED).
        CompletableFuture<Outcome> movedFuture = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_A, null);
        for (int i = 0; i < 5; i++) {
            env.sampler.tick(4);
        }
        assertFalse(movedFuture.isDone());
        env.playerLocation = new LocationSnapshot("world", 1.5, 64.0, 0.5, 0, 0); // moved 1.0 block
        env.sampler.tick(4);
        assertEquals(
                new Outcome.Cancelled(CancelReason.MOVED),
                movedFuture.join());
        assertTrue(env.messagesSent.contains(Messages.TELEPORT_MOVED));
        assertTrue(env.registry.get(PLAYER).isEmpty());

        // 2. Poison tick does NOT cancel; entity attack DOES cancel.
        env.playerLocation = ORIGIN;
        CompletableFuture<Outcome> damageFuture = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_A, null);
        assertFalse(env.sampler.onDamage(PLAYER, "POISON"));
        assertFalse(env.sampler.onDamage(PLAYER, "FIRE_TICK"));
        assertFalse(damageFuture.isDone());
        assertTrue(env.sampler.onDamage(PLAYER, "ENTITY_ATTACK"));
        assertEquals(new Outcome.Cancelled(CancelReason.DAMAGED), damageFuture.join());
        assertTrue(env.messagesSent.contains(Messages.TELEPORT_DAMAGED));

        // 3. Standing still for the full 2 seconds completes the teleport; last-ground is recorded only outside hazards.
        env.backService.clearPlayerTransient(PLAYER);
        env.onGround = true;
        env.feetMaterial = "LAVA";
        env.sampler.tick(4);
        assertTrue(env.backService.lastGround(PLAYER).isEmpty());

        env.feetMaterial = "STONE";
        CompletableFuture<Outcome> fullWarmup = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_A, null);
        for (int i = 0; i < 10; i++) {
            env.sampler.tick(4);
        }
        assertInstanceOf(Outcome.Teleported.class, fullWarmup.join());
        assertEquals(ORIGIN, env.backService.lastGround(PLAYER).orElseThrow());
    }

    @Test
    void playerQuittingMidWarmupLeavesNoSessionAndReceivesNoMessage() {
        FakeTeleportEnv env = new FakeTeleportEnv();
        env.yaml.set("teleport.warmup.home", 3);

        CompletableFuture<Outcome> future = env.service.requestTeleport(
                PLAYER, TeleportReason.HOME, "base", TARGET_A, null);
        env.messagesSent.clear();

        // Player disconnects mid-warmup.
        env.online = false;
        env.sampler.tick(4);

        assertTrue(future.isDone());
        assertTrue(env.registry.get(PLAYER).isEmpty());
        assertTrue(env.messagesSent.isEmpty(), "Disconnected player should receive no message: " + env.messagesSent);
    }

    private static final class FakeTeleportEnv implements TeleportPlatform, PlayerStateProvider {
        final YamlConfiguration yaml = new YamlConfiguration();
        final AtomicLong clock = new AtomicLong(1_000L);
        final TeleportSessionRegistry registry = new TeleportSessionRegistry();
        final CooldownService cooldowns = new CooldownService(clock::get);
        final FakeBackRepo backRepo = new FakeBackRepo();
        final BackService backService = new BackService(backRepo, () -> new MarpConfig(yaml), clock::get);
        final List<String> messagesSent = new ArrayList<>();
        final TeleportService service;
        final WarmupSampler sampler;

        boolean online = true;
        LocationSnapshot playerLocation = ORIGIN;
        boolean onGround = true;
        String feetMaterial = "STONE";
        boolean bypassCooldown;
        boolean bypassWarmup;
        boolean bypassSafety;
        boolean teleportSucceeded = true;
        SafetyResult nextSafetyResult;

        FakeTeleportEnv() {
            this.service = new TeleportService(
                    registry,
                    cooldowns,
                    backService,
                    this,
                    Scheduler.direct(),
                    () -> new MarpConfig(yaml));
            this.sampler = new WarmupSampler(
                    registry,
                    backService,
                    this,
                    () -> new MarpConfig(yaml),
                    (uuid, reason) -> {
                        if (reason.messageKey() != null) {
                            sendMessage(uuid, reason.messageKey(), Map.of());
                        }
                    },
                    (uuid, seconds) -> sendCountdown(uuid, seconds, new MarpConfig(yaml)));
        }

        @Override
        public Optional<PlayerContext> player(UUID uuid) {
            if (!online || !uuid.equals(PLAYER)) {
                return Optional.empty();
            }
            return Optional.of(new PlayerContext(
                    PLAYER, playerLocation, bypassCooldown, bypassWarmup, bypassSafety));
        }

        @Override
        public boolean isWorldLoaded(String worldName) {
            return Set.of("world", "world_nether").contains(worldName);
        }

        @Override
        public CompletableFuture<SafetyResult> evaluateSafety(LocationSnapshot target, MarpConfig config) {
            SafetyResult result = nextSafetyResult != null ? nextSafetyResult : new SafetyResult.Safe(target);
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void preparePlayerForArrival(UUID uuid, MarpConfig config) {
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(UUID uuid, LocationSnapshot target) {
            if (teleportSucceeded) {
                playerLocation = target;
            }
            return CompletableFuture.completedFuture(teleportSucceeded);
        }

        @Override
        public void sendMessage(UUID uuid, String messageKey, Map<String, String> placeholders) {
            if (online) {
                messagesSent.add(messageKey);
            }
        }

        @Override
        public void sendCountdown(UUID uuid, int seconds, MarpConfig config) {
        }

        @Override
        public void clearActionBar(UUID uuid) {
        }

        @Override
        public void playArrivalEffects(UUID uuid, MarpConfig config) {
        }

        @Override
        public void closeMarpMenuIfOpen(UUID uuid) {
        }

        @Override
        public Collection<PlayerSample> onlinePlayers() {
            return sample(PLAYER).map(List::of).orElseGet(List::of);
        }

        @Override
        public Optional<PlayerSample> sample(UUID uuid) {
            if (!online || !uuid.equals(PLAYER)) {
                return Optional.empty();
            }
            return Optional.of(new PlayerSample(PLAYER, playerLocation, onGround, feetMaterial, false));
        }
    }

    private static final class FakeBackRepo implements BackRepository {
        private final Map<UUID, Map<BackKind, BackEntry>> store = new HashMap<>();

        void clear() {
            store.clear();
        }

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
