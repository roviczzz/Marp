package me.roviczzz.marp.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import me.roviczzz.marp.config.MarpConfig.BackPreference;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import org.junit.jupiter.api.Test;

class BackResolverTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final LocationSnapshot DEATH_LOC = new LocationSnapshot("world", 1, 64, 1, 0, 0);
    private static final LocationSnapshot TELEPORT_LOC = new LocationSnapshot("world", 9, 64, 9, 0, 0);

    private static BackEntry death(long at) {
        return new BackEntry(PLAYER, BackKind.DEATH, DEATH_LOC, "LAVA", at);
    }

    private static BackEntry teleport(long at) {
        return new BackEntry(PLAYER, BackKind.TELEPORT, TELEPORT_LOC, "HOME", at);
    }

    @Test
    void newestWinsByDefault() {
        assertEquals(Optional.of(teleport(200)), BackResolver.resolve(death(100), teleport(200), BackPreference.NEWEST));
        assertEquals(Optional.of(death(300)), BackResolver.resolve(death(300), teleport(200), BackPreference.NEWEST));
    }

    @Test
    void tieGoesToDeath() {
        assertEquals(Optional.of(death(100)), BackResolver.resolve(death(100), teleport(100), BackPreference.NEWEST));
    }

    @Test
    void deathPreferenceAlwaysPrefersDeath() {
        assertEquals(Optional.of(death(10)), BackResolver.resolve(death(10), teleport(999), BackPreference.DEATH));
        assertEquals(Optional.of(teleport(999)), BackResolver.resolve(null, teleport(999), BackPreference.DEATH));
    }

    @Test
    void teleportPreferenceAlwaysPrefersTeleport() {
        assertEquals(Optional.of(teleport(10)), BackResolver.resolve(death(999), teleport(10), BackPreference.TELEPORT));
    }

    @Test
    void singleTargetOrNone() {
        assertEquals(Optional.of(death(1)), BackResolver.resolve(death(1), null, BackPreference.NEWEST));
        assertEquals(Optional.of(teleport(1)), BackResolver.resolve(null, teleport(1), BackPreference.NEWEST));
        assertTrue(BackResolver.resolve(null, null, BackPreference.NEWEST).isEmpty());
    }
}
