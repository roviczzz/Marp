package me.roviczzz.marp.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util. UUID;
import java.util.function.Predicate;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;

/**
 * Pure, cache-only tab completion helpers (DESIGN.md §3.4).
 *
 * <p>Never touches the database; if a cache is cold, returns an empty list
 * immediately.
 */
public final class CompletionSupport {

    public static final int MAX_SUGGESTIONS = 50;

    private CompletionSupport() {
    }

    public static List<String> completeHomes(
            UUID player, String rawArg, HomeCache cache, boolean homesEnabled) {
        if (!homesEnabled) {
            return List.of();
        }
        return cache.suggest(player, rawArg, MAX_SUGGESTIONS);
    }

    public static List<String> completeVisibleWarps(
            String rawArg,
            WarpCache cache,
            WarpService warpService,
            Predicate<String> hasPermission,
            boolean warpsEnabled) {
        if (!warpsEnabled) {
            return List.of();
        }
        return cache.suggestVisible(
                rawArg, warp -> warpService.canAccess(warp, hasPermission), MAX_SUGGESTIONS);
    }

    public static List<String> completeAllWarps(
            String rawArg, WarpCache cache, boolean warpsEnabled, boolean canAdminDelete) {
        if (!warpsEnabled || !canAdminDelete) {
            return List.of();
        }
        return cache.suggestAll(rawArg, MAX_SUGGESTIONS);
    }

    public static List<String> completeMarpSubcommands(
            String rawArg, Predicate<String> hasPermission) {
        String prefix = rawArg == null ? "" : rawArg.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(3);
        if ("reload".startsWith(prefix) && hasPermission.test("marp.admin.reload")) {
            out.add("reload");
        }
        if ("cancel".startsWith(prefix) && hasPermission.test("marp.cancel")) {
            out.add("cancel");
        }
        if ("info".startsWith(prefix) && hasPermission.test("marp.admin.info")) {
            out.add("info");
        }
        return out;
    }
}
