package me.roviczzz.marp.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.roviczzz.marp.model.LocationSnapshot;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class SafetyEvaluatorTest {

    private static final BoundingBox FULL_BLOCK = new BoundingBox(0, 0, 0, 1, 1, 1);
    private static final BoundingBox BOTTOM_SLAB = new BoundingBox(0, 0, 0, 1, 0.5, 1);
    private static final BoundingBox SNOW_LAYER = new BoundingBox(0, 0, 0, 1, 0.125, 1);
    private static final BoundingBox STAIR_BASE = new BoundingBox(0, 0, 0, 1, 0.5, 1);
    private static final BoundingBox STAIR_STEP = new BoundingBox(0, 0.5, 0, 1, 1.0, 0.5);

    private static final Set<String> HAZARDS = Set.of(
            "LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE",
            "POWDER_SNOW", "CACTUS", "SWEET_BERRY_BUSH", "WITHER_ROSE");

    private SafetyEvaluator evaluator() {
        return new SafetyEvaluator(3.0, true, true, true, HAZARDS);
    }

    @Test
    void slabStairAndSnowFloorsAreAcceptedOrAdjustedOntoSurface() {
        SyntheticBlockView view = new SyntheticBlockView();
        // Bottom slab at (0, 64, 0) -> top at 64.5
        view.set(0, 64, 0, "OAK_SLAB", List.of(BOTTOM_SLAB));

        // Standing directly on the slab (y = 64.5) is SAFE.
        SafetyResult onSlab = evaluator().evaluate(view, new LocationSnapshot("world", 0.5, 64.5, 0.5, 0, 0));
        assertInstanceOf(SafetyResult.Safe.class, onSlab);

        // Saved slightly above the slab (y = 65.0) is ADJUSTED down onto 64.5.
        SafetyResult aboveSlab = evaluator().evaluate(view, new LocationSnapshot("world", 0.5, 65.0, 0.5, 0, 0));
        SafetyResult.Adjusted adjusted = assertInstanceOf(SafetyResult.Adjusted.class, aboveSlab);
        assertEquals(64.5, adjusted.location().y(), 1e-4);

        // Snow layer at (2, 64, 0) -> top at 64.125
        view.set(2, 64, 0, "SNOW", List.of(SNOW_LAYER));
        SafetyResult onSnow = evaluator().evaluate(view, new LocationSnapshot("world", 2.5, 64.125, 0.5, 0, 0));
        assertInstanceOf(SafetyResult.Safe.class, onSnow);

        // Stair at (4, 64, 0) -> highest top is 65.0; standing at 65.0 is SAFE.
        view.set(4, 64, 0, "OAK_STAIRS", List.of(STAIR_BASE, STAIR_STEP));
        SafetyResult onStair = evaluator().evaluate(view, new LocationSnapshot("world", 4.5, 65.0, 0.5, 0, 0));
        assertInstanceOf(SafetyResult.Safe.class, onStair);
    }

    @Test
    void suffocationLavaNoFloorOutsideBorderAndHazardFloorAreRejected() {
        SyntheticBlockView view = new SyntheticBlockView();
        view.set(0, 63, 0, "STONE", List.of(FULL_BLOCK));
        view.set(0, 64, 0, "STONE", List.of(FULL_BLOCK));

        // Inside a full block -> suffocation.
        SafetyResult suffocating = evaluator().evaluate(view, new LocationSnapshot("world", 0.5, 64.0, 0.5, 0, 0));
        assertEquals(new SafetyResult.Unsafe("suffocation"), suffocating);

        // Lava at feet -> lava.
        view.set(5, 63, 5, "STONE", List.of(FULL_BLOCK));
        view.set(5, 64, 5, "LAVA", List.of());
        assertEquals(
                new SafetyResult.Unsafe("lava"),
                evaluator().evaluate(view, new LocationSnapshot("world", 5.5, 64.0, 5.5, 0, 0)));

        // No floor within maxFallDistance -> fall.
        assertEquals(
                new SafetyResult.Unsafe("fall"),
                evaluator().evaluate(view, new LocationSnapshot("world", 20.5, 80.0, 20.5, 0, 0)));

        // Below world minHeight -> void.
        assertEquals(
                new SafetyResult.Unsafe("void"),
                evaluator().evaluate(view, new LocationSnapshot("world", 0.5, -70.0, 0.5, 0, 0)));

        // Outside world border -> border.
        view.borderHalfSize = 100.0;
        assertEquals(
                new SafetyResult.Unsafe("border"),
                evaluator().evaluate(view, new LocationSnapshot("world", 250.5, 64.0, 0.5, 0, 0)));

        // Floor made of magma block -> rejected as hazard.
        view.set(10, 63, 10, "MAGMA_BLOCK", List.of(FULL_BLOCK));
        assertEquals(
                new SafetyResult.Unsafe("magma_block"),
                evaluator().evaluate(view, new LocationSnapshot("world", 10.5, 64.0, 10.5, 0, 0)));
    }

    @Test
    void safeSpotFinderUsesDeterministicOrderPrefersSameYAndHonoursCandidateCap() {
        SyntheticBlockView view = new SyntheticBlockView();
        // Origin (0, 64, 0) is unsafe (lava), but both East (1, 64, 0) and Up (0, 65, 0) have safe floors.
        view.set(0, 64, 0, "LAVA", List.of());
        view.set(2, 63, 0, "STONE", List.of(FULL_BLOCK)); // safe at (2.5, 64.0, 0.5) (distance 2, same Y)
        view.set(0, 64, 2, "STONE", List.of(FULL_BLOCK)); // safe at (0.5, 65.0, 2.5) (Y+1)

        SafeSpotFinder finder = new SafeSpotFinder(evaluator(), 3, 2);
        SafetyResult found = finder.find(view, new LocationSnapshot("world", 0.5, 64.0, 0.5, 0, 0), "lava");
        SafetyResult.Adjusted adjusted = assertInstanceOf(SafetyResult.Adjusted.class, found);
        // Same-Y spot at (2.5, 64.0, 0.5) must be chosen before any Y+1 candidate.
        assertEquals(2.5, adjusted.location().x(), 1e-4);
        assertEquals(64.0, adjusted.location().y(), 1e-4);
        assertEquals(0.5, adjusted.location().z(), 1e-4);

        // Found spot itself passes the evaluator.
        assertInstanceOf(SafetyResult.Safe.class, evaluator().evaluate(view, adjusted.location()));

        // North (0, -1) comes before East (1, 0) at radius 1.
        SyntheticBlockView cardinals = new SyntheticBlockView();
        cardinals.set(0, 63, -2, "STONE", List.of(FULL_BLOCK)); // North r=2
        cardinals.set(2, 63, 0, "STONE", List.of(FULL_BLOCK));  // East r=2
        SafetyResult cardinalFound = finder.find(
                cardinals, new LocationSnapshot("world", 0.5, 64.0, 0.5, 0, 0), "fall");
        LocationSnapshot loc = assertInstanceOf(SafetyResult.Adjusted.class, cardinalFound).location();
        assertEquals(0.5, loc.x(), 1e-4);
        assertEquals(-1.5, loc.z(), 1e-4);

        // Candidate cap stops the search before reaching a far candidate and returns Unsafe.
        SafeSpotFinder capped = new SafeSpotFinder(evaluator(), 3, 3, 3);
        assertEquals(
                new SafetyResult.Unsafe("lava"),
                capped.find(view, new LocationSnapshot("world", 0.5, 64.0, 0.5, 0, 0), "lava"));
        assertTrue(SafeSpotFinder.generateOffsets(3, 3, 5).size() == 5);
    }

    static final class SyntheticBlockView implements BlockView {
        private record Cell(String material, List<BoundingBox> boxes) {}

        private final Map<String, Cell> cells = new HashMap<>();
        double borderHalfSize = 1_000_000.0;

        void set(int x, int y, int z, String material, List<BoundingBox> boxes) {
            cells.put(key(x, y, z), new Cell(material, List.copyOf(boxes)));
        }

        @Override
        public List<BoundingBox> collisionBoxes(int x, int y, int z) {
            Cell cell = cells.get(key(x, y, z));
            return cell == null ? List.of() : cell.boxes();
        }

        @Override
        public String materialAt(int x, int y, int z) {
            Cell cell = cells.get(key(x, y, z));
            return cell == null ? "AIR" : cell.material();
        }

        @Override
        public int minHeight() {
            return -64;
        }

        @Override
        public int maxHeight() {
            return 320;
        }

        @Override
        public boolean isInsideBorder(double x, double z) {
            return Math.abs(x) <= borderHalfSize && Math.abs(z) <= borderHalfSize;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
