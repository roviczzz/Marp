package me.roviczzz.marp.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import me.roviczzz.marp.config.MarpConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HelpCatalogTest {

    @Test
    void filtersByPermissionAndDisabledFeatures() {
        YamlConfiguration yaml = new YamlConfiguration();
        MarpConfig config = new MarpConfig(yaml);

        // All entries exist in catalog.
        assertFalse(HelpCatalog.all().isEmpty());

        // Player with only marp.home sees only /home, /sethome, /delhome, /homes if they hold those.
        Set<String> perms = Set.of("marp.home", "marp.sethome");
        List<HelpEntry> visible = HelpCatalog.visibleEntries(perms::contains, config);
        assertTrue(visible.stream().anyMatch(e -> e.syntax().equals("/home [name]")));
        assertTrue(visible.stream().anyMatch(e -> e.syntax().equals("/sethome [name]")));
        assertFalse(visible.stream().anyMatch(e -> e.syntax().contains("warp")));
        assertFalse(visible.stream().anyMatch(e -> e.syntax().contains("reload")));

        // Disabled warps feature hides warps even when sender has permission.
        yaml.set("warps.enabled", false);
        MarpConfig noWarps = new MarpConfig(yaml);
        List<HelpEntry> opVisible = HelpCatalog.visibleEntries(p -> true, noWarps);
        assertFalse(opVisible.stream().anyMatch(e -> e.category() == HelpCategory.WARPS));
    }
}
