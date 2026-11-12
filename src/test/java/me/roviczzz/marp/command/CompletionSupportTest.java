package me.roviczzz.marp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CompletionSupportTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final LocationSnapshot LOC = new LocationSnapshot("world", 0, 64, 0, 0, 0);

    @Test
    void homeCompletionRespectsPrefixColdCacheAndFeatureSwitch() {
        HomeCache cache = new HomeCache();
        assertEquals(List.of(), CompletionSupport.completeHomes(PLAYER, "b", cache, true));

        cache.putAll(PLAYER, List.of("base", "barn", "outpost"));
        assertEquals(List.of("barn", "base"), CompletionSupport.completeHomes(PLAYER, "B", cache, true));
        assertEquals(List.of(), CompletionSupport.completeHomes(PLAYER, "b", cache, false));
    }

    @Test
    void warpCompletionFiltersHiddenWarpsAndHonoursColdCache() {
        WarpCache cache = new WarpCache();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("warps.per-warp-permissions", true);
        WarpService service = new WarpService(
                null, cache, () -> new MarpConfig(yaml), NameRules::defaults, () -> 0L);

        // Cold cache -> empty without touching repository.
        assertEquals(
                List.of(),
                CompletionSupport.completeVisibleWarps("", cache, service, ignored -> true, true));

        Warp spawn = new Warp(
                1L, "spawn", "spawn", LOC, IconSpec.material("ENDER_PEARL"), null, List.of(), null, null, 0L, 1L, 1L);
        Warp secret = new Warp(
                2L, "secret", "secret", LOC, IconSpec.material("ENDER_PEARL"), null, List.of(), null, null, 0L, 1L, 1L);
        cache.replaceAll(List.of(spawn, secret));

        Set<String> granted = Set.of("marp.warp.access.spawn");
        assertEquals(
                List.of("spawn"),
                CompletionSupport.completeVisibleWarps("s", cache, service, granted::contains, true));
        assertEquals(
                List.of("secret", "spawn"),
                CompletionSupport.completeAllWarps("s", cache, true, true));
        assertEquals(
                List.of(),
                CompletionSupport.completeAllWarps("s", cache, true, false));
    }

    @Test
    void marpSubcommandCompletionFiltersByPermission() {
        Set<String> playerPerms = Set.of("marp.cancel", "marp.help");
        assertEquals(
                List.of("help", "cancel"),
                CompletionSupport.completeMarpSubcommands("", playerPerms::contains));

        Set<String> adminPerms = Set.of(
                "marp.help", "marp.cancel", "marp.bedhome.toggle", "marp.admin.info", "marp.admin.reload");
        assertEquals(
                List.of("help", "cancel", "bed", "info", "reload"),
                CompletionSupport.completeMarpSubcommands("", adminPerms::contains));

        assertEquals(
                List.of("Alice", "Alvin"),
                CompletionSupport.completeNames(List.of("Bob", "Alvin", "Alice"), "al", 50));
    }
}
