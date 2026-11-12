package me.roviczzz.marp.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FavoriteLimitResolverTest {

    @Test
    void resolvesHighestNodeUnlimitedAndDefaultFallback() {
        FavoriteLimitResolver resolver = new FavoriteLimitResolver(9, 50, 45);

        // No node -> default 9.
        assertEquals(9, resolver.resolve(n -> false, false));

        // Highest node wins (e.g. has 5 and 15 -> 15).
        assertEquals(15, resolver.resolve(n -> n == 5 || n == 15, false));

        // Node above scan cap (60) ignored -> falls back to 9.
        assertEquals(9, resolver.resolve(n -> n == 60, false));

        // Unlimited capped by absolute-max (45).
        assertEquals(45, resolver.resolve(n -> true, true));
    }
}
