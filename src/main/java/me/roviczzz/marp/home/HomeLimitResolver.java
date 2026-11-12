package me.roviczzz.marp.home;

import java.util.function.IntPredicate;

/**
 * Resolves a player's home limit from concrete {@code marp.homelimit.<n>} nodes.
 *
 * <p>Scanning concrete nodes high-to-low is deliberate: permission plugins
 * expand wildcards at check time, so a granted {@code marp.homelimit.*} is seen
 * by {@code hasPermission("marp.homelimit.5")}. Enumerating effective
 * permissions would miss unregistered numeric nodes.
 */
public final class HomeLimitResolver {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final int defaultLimit;
    private final int maxScan;

    public HomeLimitResolver(int defaultLimit, int maxScan) {
        this.defaultLimit = Math.max(0, defaultLimit);
        this.maxScan = Math.max(1, maxScan);
    }

    /**
     * @param hasNode tests an exact permission node
     * @param unlimited whether the player holds {@code marp.homelimit.unlimited}
     */
    public int resolve(IntPredicate hasNode, boolean unlimited) {
        if (unlimited) {
            return UNLIMITED;
        }
        for (int limit = maxScan; limit >= 1; limit--) {
            if (hasNode.test(limit)) {
                return limit;
            }
        }
        return defaultLimit;
    }

    public boolean isUnlimited(int limit) {
        return limit == UNLIMITED;
    }

    /** Display form: the number, or {@code ∞} for unlimited. */
    public String display(int limit) {
        return isUnlimited(limit) ? "∞" : Integer.toString(limit);
    }
}
