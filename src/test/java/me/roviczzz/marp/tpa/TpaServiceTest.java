package me.roviczzz.marp.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.model.PlayerSettings;
import me.roviczzz.marp.settings.PlayerSettingsService;
import me.roviczzz.marp.storage.repo.PlayerSettingsRepository;
import me.roviczzz.marp.teleport.CombatTracker;
import me.roviczzz.marp.tpa.TpaService.AcceptOutcome;
import me.roviczzz.marp.tpa.TpaService.CancelOutcome;
import me.roviczzz.marp.tpa.TpaService.DenyOutcome;
import me.roviczzz.marp.tpa.TpaService.SendOutcome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TpaServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CHARLIE = UUID.randomUUID();

    @Test
    void enforcesSelfRequestOneOutgoingExpiryAmbiguityAndDenyCooldown() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("tpa.request-timeout-seconds", 60);
        yaml.set("tpa.deny-cooldown-seconds", 30);
        AtomicLong clock = new AtomicLong(10_000L);
        PlayerSettingsService settings = new PlayerSettingsService(new InMemorySettingsRepo(), clock::get);
        CombatTracker combat = new CombatTracker(() -> new MarpConfig(yaml), clock::get);
        TpaService tpa = new TpaService(() -> new MarpConfig(yaml), settings, combat, clock::get);

        // Self request blocked.
        assertInstanceOf(
                SendOutcome.SelfRequest.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", ALICE, "Alice", "world"));

        // Alice -> Bob succeeds; Alice -> Charlie blocked by 1-outgoing-request rule.
        TpaRequest first = assertInstanceOf(
                SendOutcome.Sent.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", BOB, "Bob", "world")).request();
        assertInstanceOf(
                SendOutcome.AlreadyPending.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", CHARLIE, "Charlie", "world"));

        // Charlie -> Bob allowed simultaneously; Bob bare /tpaccept is ambiguous.
        assertInstanceOf(
                SendOutcome.Sent.class,
                tpa.sendRequest(TpaDirection.TPA_HERE, CHARLIE, "Charlie", "world", BOB, "Bob", "world"));
        AcceptOutcome ambiguous = tpa.prepareAccept(BOB, null, "world", u -> "world");
        assertEquals(2, assertInstanceOf(AcceptOutcome.Ambiguous.class, ambiguous).pending().size());

        // Explicit /tpaccept Alice resolves Alice's request; /tpaccept Charlie remains pending.
        AcceptOutcome aliceReady = tpa.prepareAccept(BOB, "alice", "world", u -> "world");
        TpaRequest readyReq = assertInstanceOf(AcceptOutcome.Ready.class, aliceReady).request();
        assertEquals(first.id(), readyReq.id());
        assertTrue(tpa.consumeAccepted(readyReq.id()));

        // Bob denies Charlie -> triggers 30s deny cooldown from Charlie -> Bob.
        assertInstanceOf(DenyOutcome.Denied.class, tpa.deny(BOB, null));
        SendOutcome blockedByDeny = tpa.sendRequest(
                TpaDirection.TPA, CHARLIE, "Charlie", "world", BOB, "Bob", "world");
        assertEquals(30L, assertInstanceOf(SendOutcome.DenyCooldown.class, blockedByDeny).remainingSeconds());

        // Advance past 30s deny cooldown -> Charlie can request again, then /tpcancel clears it.
        clock.addAndGet(31_000L);
        assertInstanceOf(
                SendOutcome.Sent.class,
                tpa.sendRequest(TpaDirection.TPA, CHARLIE, "Charlie", "world", BOB, "Bob", "world"));
        assertInstanceOf(CancelOutcome.Cancelled.class, tpa.cancel(CHARLIE));

        // Expiry drains after 60s.
        TpaRequest expiring = assertInstanceOf(
                SendOutcome.Sent.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", BOB, "Bob", "world")).request();
        clock.addAndGet(61_000L);
        List<TpaRequest> expired = tpa.drainExpired();
        assertEquals(1, expired.size());
        assertEquals(expiring.id(), expired.get(0).id());
        assertInstanceOf(AcceptOutcome.NonePending.class, tpa.prepareAccept(BOB, null, "world", u -> "world"));
    }

    @Test
    void enforcesToggleCrossWorldCombatTagAndQuitCleanup() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("tpa.cross-world", false);
        yaml.set("tpa.combat-tag-seconds", 10);
        AtomicLong clock = new AtomicLong(1_000L);
        PlayerSettingsService settings = new PlayerSettingsService(new InMemorySettingsRepo(), clock::get);
        CombatTracker combat = new CombatTracker(() -> new MarpConfig(yaml), clock::get);
        TpaService tpa = new TpaService(() -> new MarpConfig(yaml), settings, combat, clock::get);

        // /tptoggle blocks incoming requests.
        settings.setTpaDisabled(BOB, true).join();
        assertInstanceOf(
                SendOutcome.TargetToggledOff.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", BOB, "Bob", "world"));
        settings.setTpaDisabled(BOB, false).join();

        // Cross-world disabled blocks across dimensions.
        assertInstanceOf(
                SendOutcome.CrossWorldDisabled.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", BOB, "Bob", "world_nether"));

        // Combat tag blocks mover until tag expires.
        combat.recordCombat(ALICE, CHARLIE);
        SendOutcome inCombat = tpa.sendRequest(
                TpaDirection.TPA, ALICE, "Alice", "world", BOB, "Bob", "world");
        assertEquals(10L, assertInstanceOf(SendOutcome.MoverInCombat.class, inCombat).remainingSeconds());
        clock.addAndGet(11_000L);

        assertInstanceOf(
                SendOutcome.Sent.class,
                tpa.sendRequest(TpaDirection.TPA, ALICE, "Alice", "world", BOB, "Bob", "world"));
        List<TpaRequest> quitCleared = tpa.onQuit(BOB);
        assertEquals(1, quitCleared.size());
        assertTrue(tpa.outgoingFor(ALICE).isEmpty());
    }

    private static final class InMemorySettingsRepo implements PlayerSettingsRepository {
        private final java.util.Map<UUID, PlayerSettings> store = new java.util.HashMap<>();

        @Override
        public CompletableFuture<Optional<PlayerSettings>> find(UUID player) {
            return CompletableFuture.completedFuture(Optional.ofNullable(store.get(player)));
        }

        @Override
        public CompletableFuture<Void> upsert(PlayerSettings settings) {
            store.put(settings.player(), settings);
            return CompletableFuture.completedFuture(null);
        }
    }
}
