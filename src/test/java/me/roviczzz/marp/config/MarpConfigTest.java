package me.roviczzz.marp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MarpConfigTest {

    private MarpConfig config(List<String> warnings) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("gui.rows", 2);
        yaml.set("teleport.safety.mode", "NOPE");
        yaml.set("back.prefer", "weird");
        yaml.set("back.unreachable-fallback", "???");
        yaml.set("homes.max-limit-node-scan", 0);
        yaml.set("favorites.home-no-arg-priority", "RANDOM");
        yaml.set("help.entries-per-page", 0);
        return new MarpConfig(yaml, warnings::add);
    }

    @Test
    void invalidValuesFallBackToDocumentedDefaults() {
        List<String> warnings = new ArrayList<>();
        MarpConfig config = config(warnings);

        assertEquals(MarpConfig.SafetyMode.SEARCH, config.safetyMode());
        assertEquals(MarpConfig.BackPreference.NEWEST, config.backPreference());
        assertEquals(MarpConfig.UnreachableFallback.LAST_GROUND, config.unreachableFallback());
        assertEquals(MarpConfig.HomeNoArgPriority.DEFAULT_FIRST, config.homeNoArgPriority());
        assertEquals(3, config.guiRows());
        assertEquals(1, config.maxLimitNodeScan());
        assertEquals(1, config.helpEntriesPerPage());
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("teleport.safety.mode")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("favorites.home-no-arg-priority")));
    }

    @Test
    void rowsAreClampedIntoTheSupportedRange() {
        YamlConfiguration low = new YamlConfiguration();
        low.set("gui.rows", 2);
        assertEquals(3, new MarpConfig(low).guiRows());

        YamlConfiguration high = new YamlConfiguration();
        high.set("gui.rows", 9);
        assertEquals(6, new MarpConfig(high).guiRows());
    }

    @Test
    void everyAccessorHasAUsableDefault() {
        MarpConfig config = new MarpConfig(new YamlConfiguration());
        assertEquals("sqlite", config.storageType());
        assertEquals("home", config.defaultHomeName());
        assertEquals(3, config.defaultHomeLimit());
        assertEquals(4, config.sampleIntervalTicks());
        assertEquals(3, config.warmupHomeSeconds());
        assertEquals("SEARCH", config.safetyMode().name());
        assertEquals("NEWEST", config.backPreference().name());
        assertTrue(config.hazardMaterials().contains("LAVA"));
        assertTrue(config.ignoredDamageCauses().contains("POISON"));
        assertTrue(config.tpaEnabled());
        assertEquals(60, config.tpaRequestTimeoutSeconds());
        assertEquals(3, config.warmupTpaSeconds());
        assertEquals(15, config.cooldownTpaSeconds());
        assertTrue(config.bedHomeEnabled());
        assertEquals("bed", config.bedHomeName());
        assertFalse(config.bedHomeCountsTowardLimit());
        assertTrue(config.favoritesEnabled());
        assertEquals(9, config.favoritesDefaultLimit());
        assertEquals(7, config.helpEntriesPerPage());
    }
}
