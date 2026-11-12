package me.roviczzz.marp.home;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.storage.repo.HomeRepository;

/**
 * Owns home creation, overwriting, deletion, listing, and metadata patching.
 *
 * <p>Commands and GUI both call this service, so identifier rules, limits, and
 * optimistic locking cannot diverge.
 */
public final class HomeService {

    public static final String DEFAULT_ICON_MATERIAL = "GRASS_BLOCK";

    public sealed interface SetOutcome {
        record Created(Home home) implements SetOutcome {}

        record Updated(Home home) implements SetOutcome {}

        record InvalidName(NameRules.Reason reason, String attempted, String pattern, int maxLength)
                implements SetOutcome {}

        record LimitZero() implements SetOutcome {}

        record LimitReached(int limit) implements SetOutcome {}
    }

    public sealed interface ResolveOutcome {
        record Found(Home home) implements ResolveOutcome {}

        record NotFound(String nameKey) implements ResolveOutcome {}

        record DefaultMissingOpenMenu(List<Home> homes) implements ResolveOutcome {}
    }

    public sealed interface EditOutcome<T> {
        record Saved<T>(T entry) implements EditOutcome<T> {}

        record Conflict<T>() implements EditOutcome<T> {}

        record NotFound<T>() implements EditOutcome<T> {}
    }

    private final HomeRepository repository;
    private final HomeCache cache;
    private final Supplier<MarpConfig> config;
    private final Supplier<NameRules> nameRules;
    private final LongSupplier clock;
    private final Predicate<UUID> bedOptedOut;
    private final Function<UUID, Optional<Long>> topFavoriteHomeResolver;

    public HomeService(
            HomeRepository repository,
            HomeCache cache,
            Supplier<MarpConfig> config,
            Supplier<NameRules> nameRules,
            LongSupplier clock) {
        this(repository, cache, config, nameRules, clock, uuid -> false, uuid -> Optional.empty());
    }

    public HomeService(
            HomeRepository repository,
            HomeCache cache,
            Supplier<MarpConfig> config,
            Supplier<NameRules> nameRules,
            LongSupplier clock,
            Predicate<UUID> bedOptedOut) {
        this(repository, cache, config, nameRules, clock, bedOptedOut, uuid -> Optional.empty());
    }

    public HomeService(
            HomeRepository repository,
            HomeCache cache,
            Supplier<MarpConfig> config,
            Supplier<NameRules> nameRules,
            LongSupplier clock,
            Predicate<UUID> bedOptedOut,
            Function<UUID, Optional<Long>> topFavoriteHomeResolver) {
        this.repository = repository;
        this.cache = cache;
        this.config = config;
        this.nameRules = nameRules;
        this.clock = clock;
        this.bedOptedOut = bedOptedOut;
        this.topFavoriteHomeResolver = topFavoriteHomeResolver;
    }

    public CompletableFuture<List<Home>> loadCache(UUID player) {
        return repository.findByPlayer(player).thenApply(homes -> {
            cache.putAll(player, homes.stream().map(Home::nameKey).toList());
            return homes;
        });
    }

    public CompletableFuture<List<Home>> list(UUID player) {
        return loadCache(player);
    }

    public CompletableFuture<Optional<Home>> findById(long id, UUID owner) {
        return repository.findById(id)
                .thenApply(opt -> opt.filter(home -> home.owner().equals(owner)));
    }

    public CompletableFuture<ResolveOutcome> resolveForTeleport(UUID player, String rawNameOrNull) {
        if (rawNameOrNull != null && !rawNameOrNull.isBlank()) {
            String key = rawNameOrNull.toLowerCase(Locale.ROOT);
            return repository.findByPlayerAndName(player, key)
                    .thenApply(opt -> opt.<ResolveOutcome>map(ResolveOutcome.Found::new)
                            .orElseGet(() -> new ResolveOutcome.NotFound(key)));
        }

        String defaultKey = config.get().defaultHomeName();
        MarpConfig cfg = config.get();
        return loadCache(player).thenApply(homes -> {
            Home defaultHome = null;
            for (Home home : homes) {
                if (home.nameKey().equalsIgnoreCase(defaultKey)) {
                    defaultHome = home;
                    break;
                }
            }

            Home topFavHome = null;
            Optional<Long> topFavId = topFavoriteHomeResolver.apply(player);
            if (topFavId.isPresent()) {
                long favId = topFavId.get();
                for (Home home : homes) {
                    if (home.id() == favId) {
                        topFavHome = home;
                        break;
                    }
                }
            }

            if (cfg.homeNoArgPriority() == MarpConfig.HomeNoArgPriority.FAVORITE_FIRST) {
                if (topFavHome != null) {
                    return new ResolveOutcome.Found(topFavHome);
                }
                if (defaultHome != null) {
                    return new ResolveOutcome.Found(defaultHome);
                }
            } else {
                if (defaultHome != null) {
                    return new ResolveOutcome.Found(defaultHome);
                }
                if (topFavHome != null) {
                    return new ResolveOutcome.Found(topFavHome);
                }
            }

            if (homes.size() == 1) {
                return new ResolveOutcome.Found(homes.get(0));
            }
            return new ResolveOutcome.DefaultMissingOpenMenu(homes);
        });
    }

    public CompletableFuture<SetOutcome> setHome(
            UUID owner, String rawNameOrNull, LocationSnapshot location, int limit) {
        String candidate = (rawNameOrNull == null || rawNameOrNull.isBlank())
                ? config.get().defaultHomeName()
                : rawNameOrNull;
        NameRules rules = nameRules.get();
        NameRules.Result validated = rules.validate(candidate);
        if (!validated.valid()) {
            return CompletableFuture.completedFuture(new SetOutcome.InvalidName(
                    validated.reason(), candidate, rules.patternText(), rules.maxLength()));
        }

        String key = validated.key();
        MarpConfig cfg = config.get();
        if (cfg.bedHomeEnabled()
                && cfg.bedHomeReserveName()
                && !bedOptedOut.test(owner)
                && key.equals(resolvedBedKey(cfg, rules))) {
            return CompletableFuture.completedFuture(new SetOutcome.InvalidName(
                    NameRules.Reason.RESERVED, candidate, rules.patternText(), rules.maxLength()));
        }
        long now = clock.getAsLong();

        return repository.findByPlayerAndName(owner, key).thenCompose(existingOpt -> {
            if (existingOpt.isPresent()) {
                Home existing = existingOpt.get();
                Home moved = existing.withLocation(location, now);
                return repository.insert(moved).thenApply(saved -> {
                    cache.add(owner, saved.nameKey());
                    return new SetOutcome.Updated(saved);
                });
            }

            if (limit <= 0) {
                return CompletableFuture.completedFuture(new SetOutcome.LimitZero());
            }

            CompletableFuture<Integer> countFuture = cfg.bedHomeCountsTowardLimit()
                    ? repository.countByPlayer(owner)
                    : repository.countManualByPlayer(owner);
            return countFuture.thenCompose(count -> {
                if (count >= limit) {
                    return CompletableFuture.completedFuture(new SetOutcome.LimitReached(limit));
                }
                Home fresh = new Home(
                        0L,
                        owner,
                        key,
                        key,
                        location,
                        IconSpec.material(DEFAULT_ICON_MATERIAL),
                        null,
                        List.of(),
                        false,
                        0L,
                        now,
                        now);
                return repository.insert(fresh).thenApply(saved -> {
                    cache.add(owner, saved.nameKey());
                    return saved.rev() == 0L
                            ? new SetOutcome.Created(saved)
                            : new SetOutcome.Updated(saved);
                });
            });
        });
    }

    public CompletableFuture<SetOutcome> setBedHome(
            UUID owner, LocationSnapshot location, String iconMaterial, int limit) {
        MarpConfig cfg = config.get();
        NameRules rules = nameRules.get();
        String key = resolvedBedKey(cfg, rules);
        String material = (iconMaterial == null || iconMaterial.isBlank()) ? "RED_BED" : iconMaterial;
        long now = clock.getAsLong();

        return repository.findByPlayerAndName(owner, key).thenCompose(existingOpt -> {
            if (existingOpt.isPresent()) {
                Home existing = existingOpt.get();
                Home moved = existing.withLocation(location, now);
                return repository.insert(moved).thenApply(saved -> {
                    cache.add(owner, saved.nameKey());
                    return new SetOutcome.Updated(saved);
                });
            }

            CompletableFuture<Optional<SetOutcome>> limitCheck;
            if (cfg.bedHomeCountsTowardLimit()) {
                if (limit <= 0) {
                    return CompletableFuture.completedFuture(new SetOutcome.LimitZero());
                }
                limitCheck = repository.countByPlayer(owner)
                        .thenApply(count -> count >= limit
                                ? Optional.of(new SetOutcome.LimitReached(limit))
                                : Optional.empty());
            } else {
                limitCheck = CompletableFuture.completedFuture(Optional.empty());
            }

            return limitCheck.thenCompose(blockedOpt -> {
                if (blockedOpt.isPresent()) {
                    return CompletableFuture.completedFuture(blockedOpt.get());
                }
                Home fresh = new Home(
                        0L,
                        owner,
                        key,
                        key,
                        location,
                        IconSpec.material(material),
                        null,
                        List.of(),
                        true,
                        0L,
                        now,
                        now);
                return repository.insert(fresh).thenApply(saved -> {
                    cache.add(owner, saved.nameKey());
                    return saved.rev() == 0L
                            ? new SetOutcome.Created(saved)
                            : new SetOutcome.Updated(saved);
                });
            });
        });
    }

    public static int manualCount(List<Home> homes, MarpConfig cfg) {
        if (cfg.bedHomeCountsTowardLimit()) {
            return homes.size();
        }
        int count = 0;
        for (Home home : homes) {
            if (!home.isBed()) {
                count++;
            }
        }
        return count;
    }

    private static String resolvedBedKey(MarpConfig cfg, NameRules rules) {
        NameRules.Result validated = rules.validate(cfg.bedHomeName());
        return validated.valid() ? validated.key() : "bed";
    }

    public CompletableFuture<Optional<Home>> deleteByName(UUID owner, String rawNameOrNull) {
        String key = (rawNameOrNull == null || rawNameOrNull.isBlank())
                ? config.get().defaultHomeName()
                : rawNameOrNull.toLowerCase(Locale.ROOT);
        return repository.findByPlayerAndName(owner, key).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            Home home = opt.get();
            return repository.delete(home.id(), owner).thenApply(deleted -> {
                if (deleted) {
                    cache.remove(owner, home.nameKey());
                    return Optional.of(home);
                }
                return Optional.empty();
            });
        });
    }

    public CompletableFuture<Boolean> deleteById(long id, UUID owner) {
        return findById(id, owner).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            Home home = opt.get();
            return repository.delete(home.id(), owner).thenApply(deleted -> {
                if (deleted) {
                    cache.remove(owner, home.nameKey());
                }
                return deleted;
            });
        });
    }

    /**
     * Patches any subset of a home's presentation fields under optimistic
     * locking. Passing {@code null} for an argument leaves that field untouched.
     */
    public CompletableFuture<EditOutcome<Home>> patchMetadata(
            long id,
            UUID owner,
            long expectedRev,
            IconSpec newIcon,
            Optional<String> newDisplayName,
            Optional<List<String>> newLore) {
        return findById(id, owner).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(new EditOutcome.NotFound<>());
            }
            Home current = opt.get();
            if (current.rev() != expectedRev) {
                return CompletableFuture.completedFuture(new EditOutcome.Conflict<>());
            }
            IconSpec icon = newIcon != null ? newIcon : current.icon();
            String displayName = newDisplayName != null ? newDisplayName.orElse(null) : current.displayName();
            List<String> lore = newLore != null ? newLore.orElse(List.of()) : current.lore();
            long now = clock.getAsLong();
            Home candidate = current.withMetadata(icon, displayName, lore, current.rev(), now);

            return repository.update(candidate, expectedRev).thenCompose(updated -> {
                if (!updated) {
                    return CompletableFuture.completedFuture(new EditOutcome.Conflict<>());
                }
                return findById(id, owner).thenApply(reloaded -> reloaded
                        .<EditOutcome<Home>>map(EditOutcome.Saved::new)
                        .orElseGet(EditOutcome.Conflict::new));
            });
        });
    }
}
