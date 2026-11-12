package me.roviczzz.marp.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HomeLimitResolverTest {

    private final HomeLimitResolver resolver = new HomeLimitResolver(3, 100);

    @Test
    void highestGrantedNodeWins() {
        int limit = resolver.resolve(node -> node == 2 || node == 5, false);
        assertEquals(5, limit);
    }

    @Test
    void fallsBackToDefaultWhenNoNodeMatches() {
        assertEquals(3, resolver.resolve(node -> false, false));
    }

    @Test
    void unlimitedPermissionOverridesEverything() {
        int limit = resolver.resolve(node -> node == 2, true);
        assertEquals(HomeLimitResolver.UNLIMITED, limit);
        assertTrue(resolver.isUnlimited(limit));
        assertEquals("∞", resolver.display(limit));
    }

    @Test
    void zeroDefaultIsHonoured() {
        HomeLimitResolver zero = new HomeLimitResolver(0, 100);
        assertEquals(0, zero.resolve(node -> false, false));
    }

    @Test
    void nodesAboveTheScanCeilingAreIgnored() {
        assertEquals(3, resolver.resolve(node -> node == 500, false));
    }
}
