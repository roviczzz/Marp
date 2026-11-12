package me.roviczzz.marp.teleport;

import java.util.List;
import org.bukkit.util.BoundingBox;

/**
 * Testable seam over world block state used by {@link SafetyEvaluator} and
 * {@link SafeSpotFinder}.
 *
 * <p>Collision boxes are returned in block-local coordinates {@code [0, 1]}
 * (matching Paper's {@code VoxelShape#getBoundingBoxes()}); the evaluator shifts
 * them to world coordinates.
 */
public interface BlockView {

    List<BoundingBox> collisionBoxes(int x, int y, int z);

    String materialAt(int x, int y, int z);

    int minHeight();

    int maxHeight();

    boolean isInsideBorder(double x, double z);
}
