package me.roviczzz.marp.teleport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MarpConfig.SafetyMode;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * Bukkit adapter around {@link SafetyEvaluator} and {@link SafeSpotFinder}.
 *
 * <p>Preloads the destination chunk (and the 3x3 chunk neighbourhood when
 * spiral search is enabled) asynchronously with a 5-second timeout before
 * reading any block state on the main thread.
 */
public final class SafetyService {

    public static final long CHUNK_LOAD_TIMEOUT_SECONDS = 5L;

    private final Scheduler scheduler;

    public SafetyService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public CompletableFuture<SafetyResult> evaluate(LocationSnapshot target, MarpConfig config) {
        CompletableFuture<SafetyResult> result = new CompletableFuture<>();
        scheduler.runOnMain(() -> {
            World world = Bukkit.getWorld(target.world());
            if (world == null) {
                result.completeExceptionally(new IllegalStateException("World not loaded: " + target.world()));
                return;
            }

            int centerCx = ((int) Math.floor(target.x())) >> 4;
            int centerCz = ((int) Math.floor(target.z())) >> 4;
            boolean preloadNeighbourhood =
                    config.safetyMode() == SafetyMode.SEARCH && config.searchRadius() > 0;

            List<CompletableFuture<Chunk>> chunkFutures = new ArrayList<>(preloadNeighbourhood ? 9 : 1);
            int span = preloadNeighbourhood ? 1 : 0;
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    chunkFutures.add(world.getChunkAtAsync(centerCx + dx, centerCz + dz));
                }
            }

            CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture[]::new))
                    .orTimeout(CHUNK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((unused, error) -> scheduler.runOnMain(() -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                            return;
                        }
                        World loadedWorld = Bukkit.getWorld(target.world());
                        if (loadedWorld == null) {
                            result.completeExceptionally(
                                    new IllegalStateException("World unloaded during check: " + target.world()));
                            return;
                        }
                        try {
                            BlockView view = createWorldView(loadedWorld);
                            SafetyEvaluator evaluator = new SafetyEvaluator(
                                    config.maxFallDistance(),
                                    config.allowWater(),
                                    config.checkWorldBorder(),
                                    config.centerOnBlock(),
                                    config.hazardMaterials());
                            SafetyResult initial = evaluator.evaluate(view, target);
                            if (initial instanceof SafetyResult.Unsafe unsafe
                                    && config.safetyMode() == SafetyMode.SEARCH
                                    && config.searchRadius() > 0) {
                                SafeSpotFinder finder = new SafeSpotFinder(
                                        evaluator, config.searchRadius(), config.searchVertical());
                                result.complete(finder.find(view, target, unsafe.reason()));
                            } else {
                                result.complete(initial);
                            }
                        } catch (RuntimeException exception) {
                            result.completeExceptionally(exception);
                        }
                    }));
        });
        return result;
    }

    public static BlockView createWorldView(World world) {
        return new BlockView() {
            @Override
            public List<BoundingBox> collisionBoxes(int x, int y, int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return List.of();
                }
                Block block = world.getBlockAt(x, y, z);
                return List.copyOf(block.getCollisionShape().getBoundingBoxes());
            }

            @Override
            public String materialAt(int x, int y, int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return "AIR";
                }
                return world.getBlockAt(x, y, z).getType().name();
            }

            @Override
            public int minHeight() {
                return world.getMinHeight();
            }

            @Override
            public int maxHeight() {
                return world.getMaxHeight();
            }

            @Override
            public boolean isInsideBorder(double x, double z) {
                WorldBorder border = world.getWorldBorder();
                Location center = border.getCenter();
                double half = border.getSize() / 2.0;
                return Math.abs(x - center.getX()) <= half && Math.abs(z - center.getZ()) <= half;
            }
        };
    }
}
