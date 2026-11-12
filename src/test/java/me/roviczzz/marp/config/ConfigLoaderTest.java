package me.roviczzz.marp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path dataDirectory;

    @Test
    void firstRunCopiesBundledDefaults() throws Exception {
        List<String> warnings = new ArrayList<>();
        YamlConfiguration config = ConfigLoader.load(dataDirectory, "config.yml", warnings::add);

        assertTrue(Files.exists(dataDirectory.resolve("config.yml")));
        assertEquals(1, config.getInt("config-version"));
        assertEquals("SEARCH", config.getString("teleport.safety.mode"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void userValuesArePreserved() throws Exception {
        Path file = dataDirectory.resolve("config.yml");
        Files.createDirectories(dataDirectory);
        Files.writeString(file, "config-version: 1\nhomes:\n  default-limit: 12\n", StandardCharsets.UTF_8);

        YamlConfiguration config = ConfigLoader.load(dataDirectory, "config.yml", message -> {
        });

        assertEquals(12, config.getInt("homes.default-limit"));
    }

    @Test
    void missingKeysAreMergedInMemory() throws Exception {
        Path file = dataDirectory.resolve("messages.yml");
        Files.createDirectories(dataDirectory);
        Files.writeString(file, "messages-version: 1\ngeneral:\n  no-permission: 'custom'\n", StandardCharsets.UTF_8);

        YamlConfiguration messages = ConfigLoader.load(dataDirectory, "messages.yml", message -> {
        });

        assertEquals("custom", messages.getString("general.no-permission"));
        assertNotNull(messages.getString("general.storage-not-ready"));
        assertNotNull(messages.getString("home.set"));
        // The merge is in memory only; comments and the edited value stay on disk.
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("custom"));
        assertFalse(onDisk.contains("storage-not-ready"));
    }

    @Test
    void unreadableFileDoesNotThrow() throws Exception {
        Path file = dataDirectory.resolve("config.yml");
        Files.createDirectories(dataDirectory);
        Files.writeString(file, "config-version: [unclosed\n  bad: : :", StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();
        YamlConfiguration config = ConfigLoader.load(dataDirectory, "config.yml", warnings::add);

        assertNotNull(config);
        // Whatever the parser did, the bundled keys are available afterwards.
        assertNotNull(config.getString("teleport.safety.mode"));
    }
}
