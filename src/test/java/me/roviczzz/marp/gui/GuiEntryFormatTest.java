package me.roviczzz.marp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.model.LocationSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiEntryFormatTest {

    private MessageService messages() {
        try (InputStreamReader reader = new InputStreamReader(
                GuiEntryFormatTest.class.getResourceAsStream("/messages.yml"),
                StandardCharsets.UTF_8)) {
            return new MessageService(
                    YamlConfiguration.loadConfiguration(reader), true, ignored -> {
                    });
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void holderPreservesIdentityFieldsAndClampsPage() {
        UUID owner = UUID.randomUUID();
        MarpMenuHolder holder = new MarpMenuHolder(
                MenuType.ENTRY_EDIT, TargetKind.HOME, owner, -3, 42L, 7L);
        assertEquals(MenuType.ENTRY_EDIT, holder.menuType());
        assertEquals(TargetKind.HOME, holder.targetKind());
        assertEquals(owner, holder.owner());
        assertEquals(0, holder.page());
        assertEquals(42L, holder.entryId());
        assertEquals(7L, holder.expectedRev());
    }

    @Test
    void entryLoreRendersCoordsLockedMarkerAndMissingWorldFallback() {
        MessageService msg = messages();
        MarpConfig cfg = new MarpConfig(new YamlConfiguration());
        GuiItemFactory factory = new GuiItemFactory(() -> cfg, () -> msg, ignored -> {
        });

        LocationSnapshot loc = new LocationSnapshot("world", 12.34, 64.0, -8.9, 0, 0);
        List<Component> loadedLore = factory.buildEntryLore(
                List.of("Custom line"), loc, 1_700_000_000_000L, true, true);
        List<String> plainLoaded = loadedLore.stream().map(msg::plain).toList();
        assertTrue(plainLoaded.contains("Custom line"));
        assertTrue(plainLoaded.stream().anyMatch(s -> s.contains("12.3, 64.0, -8.9")));
        assertTrue(plainLoaded.stream().anyMatch(s -> s.contains("Locked")));

        List<Component> missingWorldLore = factory.buildEntryLore(
                List.of(), loc, 1_700_000_000_000L, false, false);
        List<String> plainMissing = missingWorldLore.stream().map(msg::plain).toList();
        assertTrue(plainMissing.stream().anyMatch(s -> s.contains("not loaded")));
    }

    @Test
    void normalizeMaterialNameFallsBackForBlankOrAir() {
        assertEquals("GRASS_BLOCK", GuiItemFactory.normalizeMaterialName(null, "GRASS_BLOCK"));
        assertEquals("GRASS_BLOCK", GuiItemFactory.normalizeMaterialName("AIR", "GRASS_BLOCK"));
        assertEquals("DIAMOND_SWORD", GuiItemFactory.normalizeMaterialName("minecraft:diamond_sword", "GRASS_BLOCK"));
    }
}
