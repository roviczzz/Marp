package me.roviczzz.marp.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.favorite.FavoriteService.ToggleOutcome;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.storage.repo.FavoriteRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FavoriteServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final LocationSnapshot LOC = new LocationSnapshot("world", 0, 64, 0, 0, 0);

    @Test
    void togglesFavoritesEnforcesLimitsAndSortsPinnedEntriesFirst() {
        InMemoryFavoriteRepo repo = new InMemoryFavoriteRepo();
        FavoriteCache cache = new FavoriteCache();
        AtomicLong clock = new AtomicLong(100L);
        FavoriteService service = new FavoriteService(
                repo, cache, () -> new MarpConfig(new YamlConfiguration()), clock::get);

        // Star home 1 (sort_order 0) and warp 10 (sort_order 1).
        ToggleOutcome o1 = service.toggle(PLAYER, FavoriteKind.HOME, 1L, 2).join();
        FavoriteEntry f1 = assertInstanceOf(ToggleOutcome.Added.class, o1).entry();
        assertEquals(0, f1.sortOrder());
        assertTrue(service.isFavorite(PLAYER, FavoriteKind.HOME, 1L));

        clock.set(200L);
        ToggleOutcome o2 = service.toggle(PLAYER, FavoriteKind.WARP, 10L, 2).join();
        FavoriteEntry f2 = assertInstanceOf(ToggleOutcome.Added.class, o2).entry();
        assertEquals(1, f2.sortOrder());

        // Limit reached at 2.
        ToggleOutcome o3 = service.toggle(PLAYER, FavoriteKind.HOME, 2L, 2).join();
        assertEquals(new ToggleOutcome.LimitReached(2), o3);

        // Unstar home 1 -> frees slot.
        ToggleOutcome o4 = service.toggle(PLAYER, FavoriteKind.HOME, 1L, 2).join();
        assertEquals(new ToggleOutcome.Removed(FavoriteKind.HOME, 1L), o4);
        assertFalse(service.isFavorite(PLAYER, FavoriteKind.HOME, 1L));

        // Star home 2 -> takes sort_order 2.
        clock.set(300L);
        ToggleOutcome o5 = service.toggle(PLAYER, FavoriteKind.HOME, 2L, 2).join();
        assertInstanceOf(ToggleOutcome.Added.class, o5);

        // Pinned sorting: home 2 was starred, home 1 unstarred -> home 2 appears first!
        Home h1 = new Home(1L, PLAYER, "alpha", "alpha", LOC, IconSpec.material("STONE"), null, List.of(), 0, 10, 10);
        Home h2 = new Home(2L, PLAYER, "beta", "beta", LOC, IconSpec.material("STONE"), null, List.of(), 0, 10, 10);
        List<Home> sortedHomes = service.sortHomesWithFavorites(PLAYER, List.of(h1, h2));
        assertEquals(List.of(h2, h1), sortedHomes);

        // Warp pinned sorting: warp 10 was starred, warp 9 unstarred -> warp 10 appears first!
        Warp w9 = new Warp(9L, "a_spawn", "a_spawn", LOC, IconSpec.material("STONE"), null, List.of(), null, null, 0, 10, 10);
        Warp w10 = new Warp(10L, "z_arena", "z_arena", LOC, IconSpec.material("STONE"), null, List.of(), null, null, 0, 10, 10);
        List<Warp> sortedWarps = service.sortWarpsWithFavorites(PLAYER, List.of(w9, w10));
        assertEquals(List.of(w10, w9), sortedWarps);
    }

    private static final class InMemoryFavoriteRepo implements FavoriteRepository {
        private final Map<UUID, List<FavoriteEntry>> store = new HashMap<>();

        @Override
        public CompletableFuture<List<FavoriteEntry>> findByPlayer(UUID player) {
            List<FavoriteEntry> list = store.get(player);
            if (list == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<FavoriteEntry> copy = new ArrayList<>(list);
            copy.sort(Comparator.comparingInt(FavoriteEntry::sortOrder).thenComparingLong(FavoriteEntry::createdAt));
            return CompletableFuture.completedFuture(copy);
        }

        @Override
        public CompletableFuture<FavoriteEntry> add(UUID player, FavoriteKind kind, long targetId, long createdAt) {
            List<FavoriteEntry> list = store.computeIfAbsent(player, ignored -> new ArrayList<>());
            for (FavoriteEntry existing : list) {
                if (existing.kind() == kind && existing.targetId() == targetId) {
                    return CompletableFuture.completedFuture(existing);
                }
            }
            int nextOrder = list.stream().mapToInt(FavoriteEntry::sortOrder).max().orElse(-1) + 1;
            FavoriteEntry entry = new FavoriteEntry(player, kind, targetId, nextOrder, createdAt);
            list.add(entry);
            return CompletableFuture.completedFuture(entry);
        }

        @Override
        public CompletableFuture<Boolean> remove(UUID player, FavoriteKind kind, long targetId) {
            List<FavoriteEntry> list = store.get(player);
            if (list == null) {
                return CompletableFuture.completedFuture(false);
            }
            boolean removed = list.removeIf(e -> e.kind() == kind && e.targetId() == targetId);
            return CompletableFuture.completedFuture(removed);
        }

        @Override
        public CompletableFuture<Integer> countByPlayer(UUID player) {
            List<FavoriteEntry> list = store.get(player);
            return CompletableFuture.completedFuture(list == null ? 0 : list.size());
        }

        @Override
        public CompletableFuture<Integer> deleteByTarget(FavoriteKind kind, long targetId) {
            int count = 0;
            for (List<FavoriteEntry> list : store.values()) {
                if (list.removeIf(e -> e.kind() == kind && e.targetId() == targetId)) {
                    count++;
                }
            }
            return CompletableFuture.completedFuture(count);
        }
    }
}
