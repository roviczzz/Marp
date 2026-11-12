package me.roviczzz.marp.storage.repo;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.model.PlayerSettings;

/** Persistence contract for per-player toggle preferences. */
public interface PlayerSettingsRepository {

    CompletableFuture<Optional<PlayerSettings>> find(UUID player);

    CompletableFuture<Void> upsert(PlayerSettings settings);
}
