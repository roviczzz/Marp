package me.roviczzz.marp.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CooldownServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void activeCooldownRoundsSecondsUp() {
        AtomicLong now = new AtomicLong(1_000L);
        CooldownService service = new CooldownService(now::get);

        assertEquals(0L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));

        service.start(PLAYER, TeleportReason.HOME, 5, false);
        assertEquals(5L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));

        now.set(1_001L);
        assertEquals(5L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));

        now.set(5_001L);
        assertEquals(1L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));

        now.set(6_000L);
        assertEquals(0L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));
    }

    @Test
    void bypassSkipsBothTheCheckAndTheStart() {
        AtomicLong now = new AtomicLong(0L);
        CooldownService service = new CooldownService(now::get);

        service.start(PLAYER, TeleportReason.HOME, 10, true);
        assertEquals(0L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));

        service.start(PLAYER, TeleportReason.HOME, 10, false);
        assertEquals(0L, service.remainingSeconds(PLAYER, TeleportReason.HOME, true));
    }

    @Test
    void reasonsAreIndependent() {
        AtomicLong now = new AtomicLong(0L);
        CooldownService service = new CooldownService(now::get);

        service.start(PLAYER, TeleportReason.HOME, 10, false);
        assertEquals(10L, service.remainingSeconds(PLAYER, TeleportReason.HOME, false));
        assertEquals(0L, service.remainingSeconds(PLAYER, TeleportReason.BACK, false));
        assertEquals(0L, service.remainingSeconds(PLAYER, TeleportReason.WARP, false));
    }
}
