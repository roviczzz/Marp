package me.roviczzz.marp.teleport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.roviczzz.marp.model.LocationSnapshot;

/**
 * Deterministic spiral search for a nearby safe spot (DESIGN.md §9.6).
 *
 * <p>Evaluates every candidate through the same {@link SafetyEvaluator} used
 * for the initial check, and stops after {@code maxCandidates} evaluations so a
 * pathological area cannot stall the tick.
 */
public final class SafeSpotFinder {

    public static final int DEFAULT_MAX_CANDIDATES = 200;

    private final SafetyEvaluator evaluator;
    private final int radius;
    private final int vertical;
    private final int maxCandidates;

    public SafeSpotFinder(SafetyEvaluator evaluator, int radius, int vertical) {
        this(evaluator, radius, vertical, DEFAULT_MAX_CANDIDATES);
    }

    public SafeSpotFinder(SafetyEvaluator evaluator, int radius, int vertical, int maxCandidates) {
        this.evaluator = evaluator;
        this.radius = Math.max(0, radius);
        this.vertical = Math.max(0, vertical);
        this.maxCandidates = Math.max(1, maxCandidates);
    }

    public SafetyResult find(BlockView view, LocationSnapshot origin, String initialFailureReason) {
        double baseX = Math.floor(origin.x()) + 0.5;
        double baseY = Math.floor(origin.y());
        double baseZ = Math.floor(origin.z()) + 0.5;

        List<int[]> offsets = generateOffsets(radius, vertical, maxCandidates);
        for (int[] offset : offsets) {
            LocationSnapshot candidate = new LocationSnapshot(
                    origin.world(),
                    baseX + offset[0],
                    baseY + offset[1],
                    baseZ + offset[2],
                    origin.yaw(),
                    origin.pitch());
            SafetyResult eval = evaluator.evaluate(view, candidate);
            if (eval instanceof SafetyResult.Safe safe) {
                return new SafetyResult.Adjusted(safe.location(), "search");
            }
            if (eval instanceof SafetyResult.Adjusted adjusted) {
                return new SafetyResult.Adjusted(adjusted.location(), "search");
            }
        }
        return new SafetyResult.Unsafe(initialFailureReason == null ? "unsafe" : initialFailureReason);
    }

    /**
     * Generates {@code (dx, dy, dz)} offsets in the documented order:
     * <ol>
     *   <li>{@code dy = 0}, ring {@code 1..radius} (N, E, S, W, then diagonals);
     *   <li>{@code dy = +1, -1, +2, -2, ...} up to {@code vertical}, each
     *       starting with {@code (0, dy, 0)} followed by ring {@code 1..radius}.
     * </ol>
     */
    static List<int[]> generateOffsets(int radius, int vertical, int maxCandidates) {
        List<int[]> offsets = new ArrayList<>();
        appendHorizontalRings(offsets, 0, radius, maxCandidates);
        for (int v = 1; v <= vertical && offsets.size() < maxCandidates; v++) {
            for (int dy : new int[] {v, -v}) {
                if (offsets.size() >= maxCandidates) {
                    break;
                }
                offsets.add(new int[] {0, dy, 0});
                appendHorizontalRings(offsets, dy, radius, maxCandidates);
            }
        }
        return offsets;
    }

    private static void appendHorizontalRings(
            List<int[]> target, int dy, int radius, int maxCandidates) {
        for (int r = 1; r <= radius && target.size() < maxCandidates; r++) {
            for (int[] pt : ringPoints(r)) {
                if (target.size() >= maxCandidates) {
                    return;
                }
                target.add(new int[] {pt[0], dy, pt[1]});
            }
        }
    }

    private static List<int[]> ringPoints(int r) {
        List<int[]> points = new ArrayList<>();
        // Cardinals first: North (0, -r), East (+r, 0), South (0, +r), West (-r, 0).
        points.add(new int[] {0, -r});
        points.add(new int[] {r, 0});
        points.add(new int[] {0, r});
        points.add(new int[] {-r, 0});

        // Remaining points on the Chebyshev ring max(|dx|, |dz|) == r, ordered by
        // Euclidean distance and then clockwise starting from North-East.
        List<int[]> rest = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                    continue;
                }
                if (dx == 0 || dz == 0) {
                    continue;
                }
                rest.add(new int[] {dx, dz});
            }
        }
        rest.sort(Comparator
                .comparingInt((int[] p) -> p[0] * p[0] + p[1] * p[1])
                .thenComparingDouble(p -> clockwiseAngleFromNorth(p[0], p[1])));
        points.addAll(rest);
        return points;
    }

    private static double clockwiseAngleFromNorth(int dx, int dz) {
        double angle = Math.atan2(dx, -dz);
        return angle < 0 ? angle + 2.0 * Math.PI : angle;
    }
}
