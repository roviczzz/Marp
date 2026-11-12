package me.roviczzz.marp.storage.repo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.Warp;

/** Persistence contract for server warps. */
public interface WarpRepository {

    CompletableFuture<List<Warp>> findAll();

    CompletableFuture<Optional<Warp>> findByKey(String nameKey);

    CompletableFuture<Optional<Warp>> findById(long id);

    CompletableFuture<List<Warp>> findPage(int offset, int limit);

    CompletableFuture<Integer> count();

    /**
     * Inserts a new warp or updates the coordinates of an existing
     * {@code name_key} row while preserving its presentation and access fields.
     */
    CompletableFuture<Warp> insert(Warp warp);

    CompletableFuture<Boolean> update(Warp warp, long expectedRev);

    CompletableFuture<Boolean> delete(long id);
}
