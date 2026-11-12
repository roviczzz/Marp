package me.roviczzz.marp.favorite;

import java.util.function.IntPredicate;

/**
 * Resolves a player's favorite limit from concrete {@code marp.favoritelimit.<n>} nodes
 * or {@code marp.favoritelimit.unlimited} bounded by {@code favorites.absolute-max}.
 */
public final class FavoriteLimitResolver {

    private final int defaultLimit;
    private final int maxScan;
    private final int absoluteMax;

    public FavoriteLimitResolver(int defaultLimit, int maxScan, int absoluteMax) {
        this.defaultLimit = Math.max(0, defaultLimit);
        this.maxScan = Math.max(1, maxScan);
        this.absoluteMax = Math.max(1, absoluteMax);
    }

    public int resolve(IntPredicate hasNode, boolean unlimited) {
        if (unlimited) {
            return absoluteMax;
        }
        for (int limit = maxScan; limit >= 1; limit--) {
            if (hasNode.test(limit)) {
                return limit;
            }
        }
        return defaultLimit;
    }
}
