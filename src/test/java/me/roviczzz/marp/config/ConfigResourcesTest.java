package me.roviczzz.marp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigResourcesTest {

    private static YamlConfiguration resource(String name) {
        try (InputStream in = ConfigResourcesTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is missing from src/main/resources");
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            throw new AssertionError("Could not read " + name, exception);
        }
    }

    @Test
    void configHasEveryDocumentedSection() {
        YamlConfiguration config = resource("config.yml");
        assertEquals(1, config.getInt("config-version"));
        assertNotNull(config.getConfigurationSection("storage"));
        assertNotNull(config.getConfigurationSection("homes"));
        assertNotNull(config.getConfigurationSection("warps"));
        assertNotNull(config.getConfigurationSection("back"));
        assertNotNull(config.getConfigurationSection("teleport"));
        assertNotNull(config.getConfigurationSection("gui"));
        assertNotNull(config.getConfigurationSection("tpa"));
        assertNotNull(config.getConfigurationSection("bed-home"));
        assertNotNull(config.getConfigurationSection("favorites"));
        assertNotNull(config.getConfigurationSection("help"));
        assertTrue(config.getBoolean("homes.enabled"));
        assertTrue(config.getBoolean("warps.enabled"));
        assertTrue(config.getBoolean("back.enabled"));
        assertTrue(config.getBoolean("tpa.enabled"));
        assertTrue(config.getBoolean("bed-home.enabled"));
        assertTrue(config.getBoolean("favorites.enabled"));
        assertEquals("marp.db", config.getString("storage.sqlite.file"));
        assertEquals("SEARCH", config.getString("teleport.safety.mode"));
    }

    @Test
    void messagesDefineEveryKeyReferencedByCode() {
        YamlConfiguration messages = resource("messages.yml");
        MessageService service = new MessageService(messages, true, message -> {
        });
        List<String> missing = new ArrayList<>();
        for (Field field : Messages.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            field.setAccessible(true);
            try {
                String key = (String) field.get(null);
                if (!service.has(key)) {
                    missing.add(key);
                }
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        }
        assertTrue(missing.isEmpty(), "messages.yml is missing: " + missing);
    }
}
