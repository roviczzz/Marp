package me.roviczzz.marp.storage.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.Home;

/**
 * Persistence contract for player homes.
 *
 * <p>Methods never accept Bukkit types and always complete off the main thread.
 */
public interface HomeRepository {

    CompletableFuture<List<Home>> findByPlayer(UUID player);

    CompletableFuture<Optional<Home>> findByPlayerAndName(UUID player, String nameKey);

    CompletableFuture<Optional<Home>> findById(long id);

    CompletableFuture<Integer> countByPlayer(UUID player);

    CompletableFuture<Integer> countAll();

    /**
     * Inserts a new home or updates the coordinates of an existing
     * {@code (player_uuid, name_key)} row while preserving its icon, display
     * name, and lore.
     */
    CompletableFuture<Home> insert(Home home);

    /**
     * Optimistic-locked update for GUI metadata edits. Returns {@code false} if
     * the row was deleted or its revision changed.
     */
    CompletableFuture<Boolean> update(Home home, long expectedRev);

    CompletableFuture<Boolean> delete(long id, UUID owner);
}
