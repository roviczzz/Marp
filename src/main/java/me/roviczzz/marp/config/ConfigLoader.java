package me.roviczzz.marp.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads {@code config.yml} and {@code messages.yml} with two guarantees:
 *
 * <ul>
 *   <li>a missing file is copied from the jar, so a fresh install works;
 *   <li>keys added by a plugin update are merged from the bundled defaults
 *       without overwriting a user's edited value.
 * </ul>
 *
 * <p>Merged keys are kept in memory rather than written back: rewriting the
 * file would discard the operator's comments and ordering.
 */
public final class ConfigLoader {

    private static final String RESOURCE_ROOT = "/";

    private ConfigLoader() {
    }

    public static YamlConfiguration load(Path dataDirectory, String fileName, Consumer<String> warn) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            warn.accept("Could not create data directory " + dataDirectory + ": " + exception.getMessage());
        }

        Path file = dataDirectory.resolve(fileName);
        if (!Files.exists(file)) {
            copyBundled(fileName, file, warn);
        }

        YamlConfiguration defaults = readResource(fileName, warn);
        YamlConfiguration loaded;
        try {
            loaded = YamlConfiguration.loadConfiguration(file.toFile());
        } catch (RuntimeException exception) {
            warn.accept("Could not read " + fileName + " (" + exception.getMessage() + "); using bundled defaults");
            loaded = defaults;
        }

        if (loaded != defaults) {
            List<String> added = mergeMissing(loaded, defaults);
            if (!added.isEmpty()) {
                warn.accept("Added " + added.size() + " missing key(s) to " + fileName + " in memory");
            }
        }
        return loaded;
    }

    private static void copyBundled(String fileName, Path target, Consumer<String> warn) {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            if (in == null) {
                warn.accept("Bundled resource " + fileName + " is missing from the jar");
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            warn.accept("Could not write " + fileName + ": " + exception.getMessage());
        }
    }

    private static YamlConfiguration readResource(String fileName, Consumer<String> warn) {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            if (in == null) {
                warn.accept("Bundled resource " + fileName + " is missing from the jar");
                return new YamlConfiguration();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException | RuntimeException exception) {
            warn.accept("Could not read bundled " + fileName + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    /**
     * Copies every leaf key from {@code defaults} that the user file does not
     * set. Returns the paths that were added.
     */
    static List<String> mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || target.isSet(key)) {
                continue;
            }
            Object value = defaults.get(key);
            target.set(key, value);
            added.add(key);
        }
        return added;
    }
}
