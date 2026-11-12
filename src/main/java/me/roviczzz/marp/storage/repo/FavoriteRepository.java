package me.roviczzz.marp.storage.repo;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;

/** Persistence contract for starred homes and warps. */
public interface FavoriteRepository {

    CompletableFuture<List<FavoriteEntry>> findByPlayer(UUID player);

    CompletableFuture<FavoriteEntry> add(UUID player, FavoriteKind kind, long targetId, long createdAt);

    CompletableFuture<Boolean> remove(UUID player, FavoriteKind kind, long targetId);

    CompletableFuture<Integer> countByPlayer(UUID player);

    CompletableFuture<Integer> deleteByTarget(FavoriteKind kind, long targetId);
}
