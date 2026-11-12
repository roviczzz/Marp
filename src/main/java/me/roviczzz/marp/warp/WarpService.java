package me.roviczzz.marp.warp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.home.HomeService.EditOutcome;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.storage.repo.WarpRepository;

/**
 * Owns server-wide warp CRUD, access filtering, and cache maintenance.
 */
public final class WarpService {

    public static final String DEFAULT_ICON_MATERIAL = "ENDER_PEARL";
    public static final String ACCESS_PREFIX = "marp.warp.access.";
    public static final String ACCESS_WILDCARD = "marp.warp.access.*";

    public sealed interface SetOutcome {
        record Created(Warp warp) implements SetOutcome {}

        record Moved(Warp warp) implements SetOutcome {}

        record InvalidName(NameRules.Reason reason, String attempted, String pattern, int maxLength)
                implements SetOutcome {}
    }

    public sealed interface ResolveOutcome {
        record Allowed(Warp warp) implements ResolveOutcome {}

        record NotFound(String nameKey) implements ResolveOutcome {}

        record Denied(String nameKey) implements ResolveOutcome {}
    }

    private final WarpRepository repository;
    private final WarpCache cache;
    private final Supplier<MarpConfig> config;
    private final Supplier<NameRules> nameRules;
    private final LongSupplier clock;

    public WarpService(
            WarpRepository repository,
            WarpCache cache,
            Supplier<MarpConfig> config,
            Supplier<NameRules> nameRules,
            LongSupplier clock) {
        this.repository = repository;
        this.cache = cache;
        this.config = config;
        this.nameRules = nameRules;
        this.clock = clock;
    }

    public CompletableFuture<List<Warp>> refreshCache() {
        return repository.findAll().thenApply(warps -> {
            cache.replaceAll(warps);
            return warps;
        });
    }

    public boolean canAccess(Warp warp, Predicate<String> hasPermission) {
        if (!config.get().perWarpPermissions()) {
            return true;
        }
        String required = (warp.permission() != null && !warp.permission().isBlank())
                ? warp.permission()
                : ACCESS_PREFIX + warp.nameKey();
        return hasPermission.test(required) || hasPermission.test(ACCESS_WILDCARD);
    }

    public List<Warp> listVisible(Predicate<String> hasPermission, boolean isAdmin) {
        List<Warp> all = cache.all();
        if (isAdmin) {
            return all;
        }
        return all.stream().filter(warp -> canAccess(warp, hasPermission)).toList();
    }

    public CompletableFuture<Optional<Warp>> findById(long id) {
        return repository.findById(id);
    }

    public CompletableFuture<ResolveOutcome> resolveForTeleport(
            String rawName, Predicate<String> hasPermission) {
        String key = rawName.toLowerCase(Locale.ROOT);
        return repository.findByKey(key).thenApply(opt -> {
            if (opt.isEmpty()) {
                return new ResolveOutcome.NotFound(key);
            }
            Warp warp = opt.get();
            cache.put(warp);
            if (!canAccess(warp, hasPermission)) {
                return config.get().hideUnauthorizedWarps()
                        ? new ResolveOutcome.NotFound(key)
                        : new ResolveOutcome.Denied(key);
            }
            return new ResolveOutcome.Allowed(warp);
        });
    }

    public CompletableFuture<SetOutcome> setWarp(
            String rawName, LocationSnapshot location, UUID createdBy) {
        NameRules rules = nameRules.get();
        NameRules.Result validated = rules.validate(rawName);
        if (!validated.valid()) {
            return CompletableFuture.completedFuture(new SetOutcome.InvalidName(
                    validated.reason(), rawName, rules.patternText(), rules.maxLength()));
        }

        String key = validated.key();
        long now = clock.getAsLong();

        return repository.findByKey(key).thenCompose(existingOpt -> {
            if (existingOpt.isPresent() && !config.get().overwritePreservesMetadata()) {
                Warp existing = existingOpt.get();
                Warp reset = new Warp(
                        existing.id(),
                        key,
                        key,
                        location,
                        IconSpec.material(DEFAULT_ICON_MATERIAL),
                        null,
                        List.of(),
                        null,
                        createdBy,
                        existing.rev(),
                        existing.createdAt(),
                        now);
                return repository.update(reset, existing.rev())
                        .thenCompose(ignored -> repository.findById(existing.id()))
                        .thenApply(reloaded -> {
                            Warp saved = reloaded.orElse(reset);
                            cache.put(saved);
                            return new SetOutcome.Moved(saved);
                        });
            }

            Warp candidate = new Warp(
                    0L,
                    key,
                    key,
                    location,
                    IconSpec.material(DEFAULT_ICON_MATERIAL),
                    null,
                    List.of(),
                    null,
                    createdBy,
                    0L,
                    now,
                    now);
            return repository.insert(candidate).thenApply(saved -> {
                cache.put(saved);
                return (existingOpt.isPresent() || saved.rev() > 0L)
                        ? new SetOutcome.Moved(saved)
                        : new SetOutcome.Created(saved);
            });
        });
    }

    public CompletableFuture<Optional<Warp>> deleteByName(String rawName) {
        String key = rawName.toLowerCase(Locale.ROOT);
        return repository.findByKey(key).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            Warp warp = opt.get();
            return repository.delete(warp.id()).thenApply(deleted -> {
                if (deleted) {
                    cache.remove(warp.id());
                    return Optional.of(warp);
                }
                return Optional.empty();
            });
        });
    }

    public CompletableFuture<Boolean> deleteById(long id) {
        return repository.delete(id).thenApply(deleted -> {
            if (deleted) {
                cache.remove(id);
            }
            return deleted;
        });
    }

    public CompletableFuture<EditOutcome<Warp>> patchMetadata(
            long id,
            long expectedRev,
            IconSpec newIcon,
            Optional<String> newDisplayName,
            Optional<List<String>> newLore) {
        return repository.findById(id).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(new EditOutcome.NotFound<>());
            }
            Warp current = opt.get();
            if (current.rev() != expectedRev) {
                return CompletableFuture.completedFuture(new EditOutcome.Conflict<>());
            }
            IconSpec icon = newIcon != null ? newIcon : current.icon();
            String displayName = newDisplayName != null ? newDisplayName.orElse(null) : current.displayName();
            List<String> lore = newLore != null ? newLore.orElse(List.of()) : current.lore();
            long now = clock.getAsLong();
            Warp candidate = current.withMetadata(icon, displayName, lore, current.rev(), now);

            return repository.update(candidate, expectedRev).thenCompose(updated -> {
                if (!updated) {
                    return CompletableFuture.completedFuture(new EditOutcome.Conflict<>());
                }
                return repository.findById(id).thenApply(reloaded -> {
                    if (reloaded.isEmpty()) {
                        return new EditOutcome.Conflict<>();
                    }
                    Warp saved = reloaded.get();
                    cache.put(saved);
                    return new EditOutcome.Saved<>(saved);
                });
            });
        });
    }
}
