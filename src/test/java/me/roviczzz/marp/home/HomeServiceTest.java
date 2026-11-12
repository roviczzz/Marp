package me.roviczzz.marp.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.home.HomeService.EditOutcome;
import me.roviczzz.marp.home.HomeService.ResolveOutcome;
import me.roviczzz.marp.home.HomeService.SetOutcome;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.storage.repo.HomeRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HomeServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final LocationSnapshot FIRST = new LocationSnapshot("world", 1, 64, 1, 0, 0);
    private static final LocationSnapshot SECOND = new LocationSnapshot("world", 20, 70, -5, 90, 0);

    @Test
    void homeCacheColdLookupAddRemoveAndClear() {
        HomeCache cache = new HomeCache();
        assertFalse(cache.isLoaded(PLAYER));
        assertEquals(List.of(), cache.names(PLAYER));
        assertEquals(List.of(), cache.suggest(PLAYER, "b", 10));

        cache.putAll(PLAYER, List.of("shop", "Base"));
        assertTrue(cache.isLoaded(PLAYER));
        assertEquals(List.of("base", "shop"), cache.names(PLAYER));
        assertEquals(List.of("base"), cache.suggest(PLAYER, "B", 10));

        cache.add(PLAYER, "arena");
        assertEquals(List.of("arena", "base", "shop"), cache.names(PLAYER));

        cache.remove(PLAYER, "BASE");
        assertEquals(List.of("arena", "shop"), cache.names(PLAYER));

        cache.clear(PLAYER);
        assertFalse(cache.isLoaded(PLAYER));
        assertEquals(List.of(), cache.names(PLAYER));
    }

    @Test
    void newHomeAtLimitIsRefusedWhileUpdatingExistingAtLimitIsAllowed() {
        FakeHomeRepository repo = new FakeHomeRepository();
        HomeCache cache = new HomeCache();
        AtomicLong clock = new AtomicLong(1_000L);
        MarpConfig config = new MarpConfig(new YamlConfiguration());
        HomeService service = new HomeService(repo, cache, () -> config, NameRules::defaults, clock::get);

        // Create up to limit = 1.
        SetOutcome first = service.setHome(PLAYER, "base", FIRST, 1).join();
        Home created = assertInstanceOf(SetOutcome.Created.class, first).home();
        assertEquals("base", created.nameKey());

        // Second distinct name at limit 1 -> refused.
        SetOutcome refused = service.setHome(PLAYER, "second", SECOND, 1).join();
        assertEquals(new SetOutcome.LimitReached(1), refused);

        // Limit 0 also refuses new homes.
        assertEquals(new SetOutcome.LimitZero(), service.setHome(PLAYER, "third", SECOND, 0).join());

        // Updating an existing identifier at the limit (even limit 0) is allowed and preserves metadata.
        EditOutcome<Home> patched = service.patchMetadata(
                created.id(),
                PLAYER,
                created.rev(),
                new IconSpec("DIAMOND", new byte[] {7}),
                Optional.of("<gold>My Base"),
                Optional.of(List.of("line 1"))).join();
        Home withMeta = ((EditOutcome.Saved<Home>) assertInstanceOf(EditOutcome.Saved.class, patched)).entry();
        assertEquals(1L, withMeta.rev());

        clock.set(2_000L);
        SetOutcome updatedOutcome = service.setHome(PLAYER, "BASE", SECOND, 0).join();
        Home relocated = assertInstanceOf(SetOutcome.Updated.class, updatedOutcome).home();
        assertEquals(SECOND, relocated.location());
        assertEquals("<gold>My Base", relocated.displayName());
        assertEquals(List.of("line 1"), relocated.lore());
        assertEquals("DIAMOND", relocated.icon().material());

        // Stale rev produces a conflict and does not overwrite.
        EditOutcome<Home> stale = service.patchMetadata(
                relocated.id(),
                PLAYER,
                0L,
                IconSpec.material("STONE"),
                Optional.of("Stale"),
                null).join();
        assertInstanceOf(EditOutcome.Conflict.class, stale);
        assertEquals("<gold>My Base", service.findById(relocated.id(), PLAYER).join().orElseThrow().displayName());
    }

    @Test
    void defaultResolutionUsesDefaultOrSingleHomeElseOpensMenu() {
        FakeHomeRepository repo = new FakeHomeRepository();
        HomeCache cache = new HomeCache();
        MarpConfig config = new MarpConfig(new YamlConfiguration());
        HomeService service = new HomeService(repo, cache, () -> config, NameRules::defaults, () -> 100L);

        // Zero homes -> open menu.
        assertInstanceOf(ResolveOutcome.DefaultMissingOpenMenu.class, service.resolveForTeleport(PLAYER, null).join());

        // Single non-default home -> used directly.
        service.setHome(PLAYER, "outpost", FIRST, 5).join();
        Home only = assertInstanceOf(ResolveOutcome.Found.class, service.resolveForTeleport(PLAYER, null).join()).home();
        assertEquals("outpost", only.nameKey());

        // Two non-default homes -> open menu.
        service.setHome(PLAYER, "mine", SECOND, 5).join();
        assertInstanceOf(ResolveOutcome.DefaultMissingOpenMenu.class, service.resolveForTeleport(PLAYER, null).join());

        // Default home present -> wins over others.
        service.setHome(PLAYER, null, FIRST, 5).join();
        Home def = assertInstanceOf(ResolveOutcome.Found.class, service.resolveForTeleport(PLAYER, null).join()).home();
        assertEquals("home", def.nameKey());
    }

    @Test
    void bedHomeExemptFromLimitPreservesMetadataAndRespectsReservedName() {
        FakeHomeRepository repo = new FakeHomeRepository();
        HomeCache cache = new HomeCache();
        YamlConfiguration yaml = new YamlConfiguration();
        AtomicLong clock = new AtomicLong(1_000L);
        boolean[] optedOut = {false};
        HomeService service = new HomeService(
                repo, cache, () -> new MarpConfig(yaml), NameRules::defaults, clock::get, u -> optedOut[0]);

        // Fill manual limit = 1.
        assertInstanceOf(SetOutcome.Created.class, service.setHome(PLAYER, "base", FIRST, 1).join());
        assertInstanceOf(SetOutcome.LimitReached.class, service.setHome(PLAYER, "extra", SECOND, 1).join());

        // Manual /sethome bed is rejected while bed-home.reserve-name is true and player hasn't opted out.
        SetOutcome reserved = service.setHome(PLAYER, "bed", FIRST, 5).join();
        SetOutcome.InvalidName invalid = assertInstanceOf(SetOutcome.InvalidName.class, reserved);
        assertEquals(NameRules.Reason.RESERVED, invalid.reason());

        // setBedHome succeeds even at manual home limit = 1.
        Home bed = assertInstanceOf(
                SetOutcome.Created.class, service.setBedHome(PLAYER, FIRST, "BLUE_BED", 1).join()).home();
        assertTrue(bed.isBed());
        assertEquals("BLUE_BED", bed.icon().material());
        assertEquals(1, HomeService.manualCount(service.list(PLAYER).join(), new MarpConfig(yaml)));

        // Custom metadata survives bed overwrite.
        service.patchMetadata(bed.id(), PLAYER, bed.rev(), IconSpec.material("EMERALD"), Optional.of("Cozy"), null).join();
        Home movedBed = assertInstanceOf(
                SetOutcome.Updated.class, service.setBedHome(PLAYER, SECOND, "WHITE_BED", 1).join()).home();
        assertEquals(SECOND, movedBed.location());
        assertEquals("EMERALD", movedBed.icon().material());
        assertEquals("Cozy", movedBed.displayName());

        // Opting out allows manual /sethome bed if deleted first.
        optedOut[0] = true;
        assertTrue(service.deleteByName(PLAYER, "bed").join().isPresent());
        assertInstanceOf(SetOutcome.Created.class, service.setHome(PLAYER, "bed", FIRST, 5).join());
    }

    @Test
    void defaultResolutionHonorsFavoritesPriority() {
        FakeHomeRepository repo = new FakeHomeRepository();
        HomeCache cache = new HomeCache();
        YamlConfiguration yaml = new YamlConfiguration();
        Long[] topFavHolder = {null};
        HomeService service = new HomeService(
                repo,
                cache,
                () -> new MarpConfig(yaml),
                NameRules::defaults,
                () -> 100L,
                u -> false,
                u -> Optional.ofNullable(topFavHolder[0]));

        Home defHome = assertInstanceOf(SetOutcome.Created.class, service.setHome(PLAYER, "home", FIRST, 5).join()).home();
        Home starHome = assertInstanceOf(SetOutcome.Created.class, service.setHome(PLAYER, "farm", SECOND, 5).join()).home();
        topFavHolder[0] = starHome.id();

        // DEFAULT_FIRST: "home" wins even though "farm" is top favorite.
        yaml.set("favorites.home-no-arg-priority", "DEFAULT_FIRST");
        assertEquals(defHome.id(), assertInstanceOf(ResolveOutcome.Found.class,
                service.resolveForTeleport(PLAYER, null).join()).home().id());

        // FAVORITE_FIRST: top favorite "farm" wins over "home".
        yaml.set("favorites.home-no-arg-priority", "FAVORITE_FIRST");
        assertEquals(starHome.id(), assertInstanceOf(ResolveOutcome.Found.class,
                service.resolveForTeleport(PLAYER, null).join()).home().id());
    }

    private static final class FakeHomeRepository implements HomeRepository {
        private long seq = 1L;
        private final Map<Long, Home> byId = new LinkedHashMap<>();

        @Override
        public CompletableFuture<List<Home>> findByPlayer(UUID player) {
            return CompletableFuture.completedFuture(byId.values().stream()
                    .filter(h -> h.owner().equals(player))
                    .sorted(Comparator.comparing(Home::nameKey).thenComparingLong(Home::id))
                    .toList());
        }

        @Override
        public CompletableFuture<Optional<Home>> findByPlayerAndName(UUID player, String nameKey) {
            String key = nameKey.toLowerCase(Locale.ROOT);
            return CompletableFuture.completedFuture(byId.values().stream()
                    .filter(h -> h.owner().equals(player) && h.nameKey().equals(key))
                    .findFirst());
        }

        @Override
        public CompletableFuture<Optional<Home>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.ofNullable(byId.get(id)));
        }

        @Override
        public CompletableFuture<Integer> countByPlayer(UUID player) {
            return findByPlayer(player).thenApply(List::size);
        }

        @Override
        public CompletableFuture<Integer> countManualByPlayer(UUID player) {
            return findByPlayer(player).thenApply(homes ->
                    (int) homes.stream().filter(home -> !home.isBed()).count());
        }

        @Override
        public CompletableFuture<Integer> countAll() {
            return CompletableFuture.completedFuture(byId.size());
        }

        @Override
        public CompletableFuture<Home> insert(Home home) {
            Optional<Home> existingOpt = findByPlayerAndName(home.owner(), home.nameKey()).join();
            if (existingOpt.isPresent()) {
                Home existing = existingOpt.get();
                Home updated = new Home(
                        existing.id(),
                        existing.owner(),
                        existing.name(),
                        existing.nameKey(),
                        home.location(),
                        existing.icon(),
                        existing.displayName(),
                        existing.lore(),
                        existing.isBed(),
                        existing.rev() + 1,
                        existing.createdAt(),
                        home.updatedAt());
                byId.put(existing.id(), updated);
                return CompletableFuture.completedFuture(updated);
            }
            long id = seq++;
            Home saved = new Home(
                    id,
                    home.owner(),
                    home.name(),
                    home.nameKey().toLowerCase(Locale.ROOT),
                    home.location(),
                    home.icon(),
                    home.displayName(),
                    home.lore(),
                    home.isBed(),
                    0L,
                    home.createdAt(),
                    home.updatedAt());
            byId.put(id, saved);
            return CompletableFuture.completedFuture(saved);
        }

        @Override
        public CompletableFuture<Boolean> update(Home home, long expectedRev) {
            Home current = byId.get(home.id());
            if (current == null || !current.owner().equals(home.owner()) || current.rev() != expectedRev) {
                return CompletableFuture.completedFuture(false);
            }
            Home next = new Home(
                    current.id(),
                    current.owner(),
                    current.name(),
                    current.nameKey(),
                    home.location(),
                    home.icon(),
                    home.displayName(),
                    home.lore(),
                    current.isBed(),
                    current.rev() + 1,
                    current.createdAt(),
                    home.updatedAt());
            byId.put(current.id(), next);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> delete(long id, UUID owner) {
            Home current = byId.get(id);
            if (current == null || !current.owner().equals(owner)) {
                return CompletableFuture.completedFuture(false);
            }
            byId.remove(id);
            return CompletableFuture.completedFuture(true);
        }
    }
}
