package me.roviczzz.marp.storage.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;

/** Persistence contract for the two-row per-player back state. */
public interface BackRepository {

    CompletableFuture<Optional<BackEntry>> find(UUID player, BackKind kind);

    CompletableFuture<List<BackEntry>> findAll(UUID player);

    CompletableFuture<Void> upsert(BackEntry entry);

    CompletableFuture<Boolean> delete(UUID player, BackKind kind);
}
