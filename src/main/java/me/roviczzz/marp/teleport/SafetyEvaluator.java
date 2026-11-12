package me.roviczzz.marp.teleport;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.roviczzz.marp.model.LocationSnapshot;
import org.bukkit.util.BoundingBox;

/**
 * Pure collision-box safety evaluator from DESIGN.md §9.5.
 *
 * <p>Operates solely on {@link BlockView} and {@link BoundingBox}, so every
 * rule can be unit-tested without a server instance.
 */
public final class SafetyEvaluator {

    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;
    private static final double EPSILON = 1.0e-4;
    private static final Set<String> ADJACENT_HAZARDS =
            Set.of("LAVA", "FIRE", "SOUL_FIRE", "CACTUS");

    private final double maxFallDistance;
    private final boolean allowWater;
    private final boolean checkWorldBorder;
    private final boolean centerOnBlock;
    private final Set<String> hazards;

    public SafetyEvaluator(
            double maxFallDistance,
            boolean allowWater,
            boolean checkWorldBorder,
            boolean centerOnBlock,
            Set<String> hazards) {
        this.maxFallDistance = Math.max(0.0, maxFallDistance);
        this.allowWater = allowWater;
        this.checkWorldBorder = checkWorldBorder;
        this.centerOnBlock = centerOnBlock;
        this.hazards = Set.copyOf(hazards);
    }

    public SafetyResult evaluate(BlockView view, LocationSnapshot target) {
        double cx = centerOnBlock ? Math.floor(target.x()) + 0.5 : target.x();
        double cz = centerOnBlock ? Math.floor(target.z()) + 0.5 : target.z();
        double y = target.y();

        // 1. World border
        if (checkWorldBorder && !view.isInsideBorder(cx, cz)) {
            return new SafetyResult.Unsafe("border");
        }

        int blockX = (int) Math.floor(cx);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(cz);

        // 2. Void / world height bounds
        if (y < view.minHeight() || blockY < view.minHeight()) {
            return new SafetyResult.Unsafe("void");
        }
        if (blockY >= view.maxHeight()) {
            return new SafetyResult.Unsafe("suffocation");
        }

        // 3. Suffocation at the requested Y
        if (intersectsCollision(view, cx, y, cz)) {
            return new SafetyResult.Unsafe("suffocation");
        }

        // 4. Hazard blocks at feet and head
        String feetHazard = checkCellHazard(view, blockX, blockY, blockZ);
        if (feetHazard != null) {
            return new SafetyResult.Unsafe(feetHazard);
        }
        String headHazard = checkCellHazard(view, blockX, blockY + 1, blockZ);
        if (headHazard != null) {
            return new SafetyResult.Unsafe(headHazard);
        }

        // 5. Floor scan within max-fall-distance
        int minScanY = (int) Math.floor(y - maxFallDistance) - 1;
        Double floorTop = null;
        boolean waterFloor = false;

        for (int scanY = blockY; scanY >= minScanY; scanY--) {
            if (scanY < view.minHeight()) {
                break;
            }
            String mat = normalizeMaterial(view.materialAt(blockX, scanY, blockZ));

            Double candidateTop = highestSupportingTop(view, blockX, scanY, blockZ, cx, cz, y);
            if (candidateTop != null) {
                if (y - candidateTop <= maxFallDistance + EPSILON) {
                    // Reject a floor made of a hazard (magma, campfire, etc.).
                    if (hazards.contains(mat)) {
                        return new SafetyResult.Unsafe(hazardReason(mat));
                    }
                    floorTop = candidateTop;
                }
                break;
            }

            // If we hit a non-floor hazard before finding any safe floor above it, stop.
            if (hazards.contains(mat)) {
                return new SafetyResult.Unsafe(hazardReason(mat));
            }

            if ("WATER".equals(mat)) {
                if (!allowWater) {
                    return new SafetyResult.Unsafe("water");
                }
                double surface = Math.min(y, scanY + 1.0);
                if (y - surface <= maxFallDistance + EPSILON) {
                    floorTop = surface;
                    waterFloor = true;
                    break;
                }
            }
        }

        if (floorTop == null) {
            String reason = (y - maxFallDistance < view.minHeight()) ? "void" : "fall";
            return new SafetyResult.Unsafe(reason);
        }

        double landingY = floorTop;
        int landingFeetY = (int) Math.floor(landingY);

        // If landingY dropped below y, re-check suffocation and cell hazards at landingY.
        if (Math.abs(landingY - y) > EPSILON) {
            if (intersectsCollision(view, cx, landingY, cz)) {
                return new SafetyResult.Unsafe("suffocation");
            }
            String landingFeetHazard = checkCellHazard(view, blockX, landingFeetY, blockZ);
            if (landingFeetHazard != null) {
                return new SafetyResult.Unsafe(landingFeetHazard);
            }
            String landingHeadHazard = checkCellHazard(view, blockX, landingFeetY + 1, blockZ);
            if (landingHeadHazard != null) {
                return new SafetyResult.Unsafe(landingHeadHazard);
            }
        }

        // 6. Water surfacing rule
        String landingFeetMat = normalizeMaterial(view.materialAt(blockX, landingFeetY, blockZ));
        String landingHeadMat = normalizeMaterial(view.materialAt(blockX, landingFeetY + 1, blockZ));
        if (waterFloor || "WATER".equals(landingFeetMat) || "WATER".equals(landingHeadMat)) {
            if (!allowWater) {
                return new SafetyResult.Unsafe("water");
            }
            String aboveHeadMat = normalizeMaterial(view.materialAt(blockX, landingFeetY + 2, blockZ));
            if ("WATER".equals(landingHeadMat) && "WATER".equals(aboveHeadMat)) {
                return new SafetyResult.Unsafe("water");
            }
        }

        // 7. Adjacent 4-neighbour hazards at feet level
        String adjacent = checkAdjacentHazards(view, blockX, landingFeetY, blockZ);
        if (adjacent != null) {
            return new SafetyResult.Unsafe(adjacent);
        }

        LocationSnapshot resolved = new LocationSnapshot(
                target.world(), cx, landingY, cz, target.yaw(), target.pitch());
        if (Math.abs(landingY - y) >= 0.01) {
            return new SafetyResult.Adjusted(resolved, "floor");
        }
        return new SafetyResult.Safe(resolved);
    }

    private boolean intersectsCollision(BlockView view, double cx, double y, double cz) {
        BoundingBox playerBox = new BoundingBox(
                cx - PLAYER_HALF_WIDTH + EPSILON,
                y + EPSILON,
                cz - PLAYER_HALF_WIDTH + EPSILON,
                cx + PLAYER_HALF_WIDTH - EPSILON,
                y + PLAYER_HEIGHT - EPSILON,
                cz + PLAYER_HALF_WIDTH - EPSILON);

        int minBx = (int) Math.floor(playerBox.getMinX());
        int maxBx = (int) Math.floor(playerBox.getMaxX());
        int minBy = (int) Math.floor(y);
        int maxBy = (int) Math.floor(y + PLAYER_HEIGHT);
        int minBz = (int) Math.floor(playerBox.getMinZ());
        int maxBz = (int) Math.floor(playerBox.getMaxZ());

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    for (BoundingBox local : view.collisionBoxes(bx, by, bz)) {
                        BoundingBox worldBox = toWorldBox(local, bx, by, bz);
                        if (worldBox.overlaps(playerBox)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private Double highestSupportingTop(
            BlockView view, int blockX, int scanY, int blockZ, double cx, double cz, double maxY) {
        double minPx = cx - PLAYER_HALF_WIDTH + EPSILON;
        double maxPx = cx + PLAYER_HALF_WIDTH - EPSILON;
        double minPz = cz - PLAYER_HALF_WIDTH + EPSILON;
        double maxPz = cz + PLAYER_HALF_WIDTH - EPSILON;

        Double best = null;
        for (BoundingBox local : view.collisionBoxes(blockX, scanY, blockZ)) {
            BoundingBox worldBox = toWorldBox(local, blockX, scanY, blockZ);
            if (worldBox.getMaxX() <= minPx || worldBox.getMinX() >= maxPx) {
                continue;
            }
            if (worldBox.getMaxZ() <= minPz || worldBox.getMinZ() >= maxPz) {
                continue;
            }
            double top = worldBox.getMaxY();
            if (top <= maxY + EPSILON && (best == null || top > best)) {
                best = top;
            }
        }
        return best;
    }

    private String checkCellHazard(BlockView view, int x, int y, int z) {
        String mat = normalizeMaterial(view.materialAt(x, y, z));
        if (hazards.contains(mat)) {
            return hazardReason(mat);
        }
        if (!allowWater && "WATER".equals(mat)) {
            return "water";
        }
        return null;
    }

    private String checkAdjacentHazards(BlockView view, int x, int y, int z) {
        int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int[] off : offsets) {
            String mat = normalizeMaterial(view.materialAt(x + off[0], y, z + off[1]));
            if (ADJACENT_HAZARDS.contains(mat) && hazards.contains(mat)) {
                return hazardReason(mat);
            }
        }
        return null;
    }

    private static BoundingBox toWorldBox(BoundingBox box, int bx, int by, int bz) {
        return new BoundingBox(
                box.getMinX() + bx,
                box.getMinY() + by,
                box.getMinZ() + bz,
                box.getMaxX() + bx,
                box.getMaxY() + by,
                box.getMaxZ() + bz);
    }

    private static String normalizeMaterial(String raw) {
        return raw == null ? "AIR" : raw.toUpperCase(Locale.ROOT);
    }

    static String hazardReason(String material) {
        return switch (material) {
            case "LAVA" -> "lava";
            case "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE" -> "fire";
            default -> material.toLowerCase(Locale.ROOT);
        };
    }
}
