package me.roviczzz.marp.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.home.HomeService.EditOutcome;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.storage.repo.WarpRepository;
import me.roviczzz.marp.warp.WarpService.ResolveOutcome;
import me.roviczzz.marp.warp.WarpService.SetOutcome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WarpServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final LocationSnapshot FIRST = new LocationSnapshot("world", 0, 64, 0, 0, 0);
    private static final LocationSnapshot SECOND = new LocationSnapshot("world", 100, 70, -25, 180, 0);

    @Test
    void coldWarpCacheReturnsEmptySuggestionsAndPopulatedCacheFiltersByAccess() {
        WarpCache cache = new WarpCache();
        assertFalse(cache.isLoaded());
        assertEquals(List.of(), cache.all());
        assertEquals(List.of(), cache.suggestVisible("", ignored -> true, 10));

        FakeWarpRepository repo = new FakeWarpRepository();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("warps.per-warp-permissions", true);
        MarpConfig config = new MarpConfig(yaml);
        WarpService service = new WarpService(repo, cache, () -> config, NameRules::defaults, () -> 100L);

        service.setWarp("spawn", FIRST, ADMIN).join();
        service.setWarp("vip", FIRST, ADMIN).join();

        assertTrue(cache.isLoaded());
        Set<String> playerPerms = Set.of("marp.warp.access.spawn");
        assertEquals(
                List.of("spawn"),
                cache.suggestVisible("", w -> service.canAccess(w, playerPerms::contains), 10));
        assertEquals(List.of("spawn", "vip"), cache.suggestAll("", 10));
    }

    @Test
    void publicAccessGrantedAccessDeniedHiddenDeniedVisibleAndMovePreservesPresentation() {
        FakeWarpRepository repo = new FakeWarpRepository();
        WarpCache cache = new WarpCache();
        YamlConfiguration yaml = new YamlConfiguration();
        WarpService service = new WarpService(
                repo, cache, () -> new MarpConfig(yaml), NameRules::defaults, () -> 1_000L);

        SetOutcome createdOut = service.setWarp("Spawn", FIRST, ADMIN).join();
        Warp created = assertInstanceOf(SetOutcome.Created.class, createdOut).warp();
        assertEquals("spawn", created.nameKey());

        // Style the warp.
        EditOutcome<Warp> styledOut = service.patchMetadata(
                created.id(),
                created.rev(),
                new IconSpec("BEACON", new byte[] {4, 5}),
                Optional.of("<aqua>Server Spawn"),
                Optional.of(List.of("Welcome"))).join();
        Warp styled = ((EditOutcome.Saved<Warp>) assertInstanceOf(EditOutcome.Saved.class, styledOut)).entry();

        // Moving the warp keeps its icon, display name, and lore.
        SetOutcome movedOut = service.setWarp("SPAWN", SECOND, ADMIN).join();
        Warp moved = assertInstanceOf(SetOutcome.Moved.class, movedOut).warp();
        assertEquals(SECOND, moved.location());
        assertEquals("<aqua>Server Spawn", moved.displayName());
        assertEquals(List.of("Welcome"), moved.lore());
        assertEquals("BEACON", moved.icon().material());

        // Public access (per-warp-permissions: false).
        assertInstanceOf(
                ResolveOutcome.Allowed.class,
                service.resolveForTeleport("spawn", ignored -> false).join());

        // Enable per-warp permissions with hide-unauthorized: true (default).
        yaml.set("warps.per-warp-permissions", true);
        yaml.set("warps.hide-unauthorized", true);
        assertInstanceOf(
                ResolveOutcome.NotFound.class,
                service.resolveForTeleport("spawn", ignored -> false).join());
        assertInstanceOf(
                ResolveOutcome.Allowed.class,
                service.resolveForTeleport("spawn", node -> node.equals("marp.warp.access.spawn")).join());

        // Visible denial when hide-unauthorized is false.
        yaml.set("warps.hide-unauthorized", false);
        assertInstanceOf(
                ResolveOutcome.Denied.class,
                service.resolveForTeleport("spawn", ignored -> false).join());

        // Admin sees all warps in listVisible; normal player without permission sees none.
        assertEquals(1, service.listVisible(ignored -> false, true).size());
        assertEquals(0, service.listVisible(ignored -> false, false).size());
    }

    private static final class FakeWarpRepository implements WarpRepository {
        private long seq = 1L;
        private final Map<Long, Warp> byId = new LinkedHashMap<>();

        @Override
        public CompletableFuture<List<Warp>> findAll() {
            return CompletableFuture.completedFuture(byId.values().stream()
                    .sorted(Comparator.comparing(Warp::nameKey).thenComparingLong(Warp::id))
                    .toList());
        }

        @Override
        public CompletableFuture<Optional<Warp>> findByKey(String nameKey) {
            String key = nameKey.toLowerCase(Locale.ROOT);
            return CompletableFuture.completedFuture(byId.values().stream()
                    .filter(w -> w.nameKey().equals(key))
                    .findFirst());
        }

        @Override
        public CompletableFuture<Optional<Warp>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.ofNullable(byId.get(id)));
        }

        @Override
        public CompletableFuture<List<Warp>> findPage(int offset, int limit) {
            List<Warp> all = findAll().join();
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return CompletableFuture.completedFuture(all.subList(from, to));
        }

        @Override
        public CompletableFuture<Integer> count() {
            return CompletableFuture.completedFuture(byId.size());
        }

        @Override
        public CompletableFuture<Warp> insert(Warp warp) {
            Optional<Warp> existingOpt = findByKey(warp.nameKey()).join();
            if (existingOpt.isPresent()) {
                Warp existing = existingOpt.get();
                Warp updated = new Warp(
                        existing.id(),
                        existing.name(),
                        existing.nameKey(),
                        warp.location(),
                        existing.icon(),
                        existing.displayName(),
                        existing.lore(),
                        existing.permission(),
                        existing.createdBy(),
                        existing.rev() + 1,
                        existing.createdAt(),
                        warp.updatedAt());
                byId.put(existing.id(), updated);
                return CompletableFuture.completedFuture(updated);
            }
            long id = seq++;
            Warp saved = new Warp(
                    id,
                    warp.name(),
                    warp.nameKey().toLowerCase(Locale.ROOT),
                    warp.location(),
                    warp.icon(),
                    warp.displayName(),
                    warp.lore(),
                    warp.permission(),
                    warp.createdBy(),
                    0L,
                    warp.createdAt(),
                    warp.updatedAt());
            byId.put(id, saved);
            return CompletableFuture.completedFuture(saved);
        }

        @Override
        public CompletableFuture<Boolean> update(Warp warp, long expectedRev) {
            Warp current = byId.get(warp.id());
            if (current == null || current.rev() != expectedRev) {
                return CompletableFuture.completedFuture(false);
            }
            Warp next = new Warp(
                    current.id(),
                    current.name(),
                    current.nameKey(),
                    warp.location(),
                    warp.icon(),
                    warp.displayName(),
                    warp.lore(),
                    warp.permission(),
                    current.createdBy(),
                    current.rev() + 1,
                    current.createdAt(),
                    warp.updatedAt());
            byId.put(current.id(), next);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> delete(long id) {
            return CompletableFuture.completedFuture(byId.remove(id) != null);
        }
    }
}
