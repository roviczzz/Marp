package me.roviczzz.marp.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.model.IconSpec;
import net.kyori.adventure.text.Component;

/**
 * Enforces the visible-length, raw-length, line-count, and icon byte caps from
 * DESIGN.md §8.10.
 *
 * <p>Oversized input is rejected with a clear reason rather than silently
 * truncated. If a stored display value no longer parses or has no visible
 * characters, rendering falls back to the plain identifier.
 */
public final class TextLimits {

    public sealed interface NameResult {
        record Valid(String value) implements NameResult {}

        record Invalid(String reason) implements NameResult {}
    }

    public sealed interface LoreResult {
        record Valid(List<String> lines) implements LoreResult {}

        record Invalid(String reason) implements LoreResult {}
    }

    public sealed interface IconResult {
        record Valid(IconSpec icon) implements IconResult {}

        record Invalid() implements IconResult {}

        record TooLarge(int bytes, int maxBytes) implements IconResult {}
    }

    private TextLimits() {
    }

    public static NameResult validateDisplayName(
            String rawInput, MessageService messages, MarpConfig config) {
        if (rawInput == null || rawInput.isBlank()) {
            return new NameResult.Invalid("display name cannot be empty");
        }
        int maxRaw = config.maxDisplayNameRaw();
        if (rawInput.length() > maxRaw) {
            return new NameResult.Invalid("max " + maxRaw + " raw characters");
        }
        if (config.allowMiniMessage() && hasMalformedAngleBrackets(rawInput)) {
            return new NameResult.Invalid("unclosed formatting tag");
        }
        String visible = config.allowMiniMessage() ? messages.visibleText(rawInput) : rawInput;
        if (visible.isBlank()) {
            return new NameResult.Invalid("display name must contain visible text");
        }
        int maxVisible = config.maxDisplayNameLength();
        if (visible.length() > maxVisible) {
            return new NameResult.Invalid("max " + maxVisible + " visible characters");
        }
        return new NameResult.Valid(rawInput);
    }

    public static LoreResult validateLore(
            String rawInput, MessageService messages, MarpConfig config) {
        if (rawInput == null || rawInput.isBlank()) {
            return new LoreResult.Valid(List.of());
        }
        String[] parts = rawInput.split("\\|", -1);
        int maxLines = config.maxLoreLines();
        if (parts.length > maxLines) {
            return new LoreResult.Invalid("max " + maxLines + " lines");
        }
        int maxLineRaw = config.maxLoreLineRaw();
        int maxLineVisible = config.maxLoreLineLength();
        List<String> lines = new ArrayList<>(parts.length);

        for (String part : parts) {
            String line = part.trim().replace("\r\n", "").replace("\n", "").replace("\r", "");
            if (line.length() > maxLineRaw) {
                return new LoreResult.Invalid("max " + maxLineRaw + " raw characters per line");
            }
            if (config.allowMiniMessage() && hasMalformedAngleBrackets(line)) {
                return new LoreResult.Invalid("unclosed formatting tag");
            }
            String visible = config.allowMiniMessage() ? messages.visibleText(line) : line;
            if (visible.length() > maxLineVisible) {
                return new LoreResult.Invalid("max " + maxLineVisible + " visible characters per line");
            }
            lines.add(line);
        }
        return new LoreResult.Valid(List.copyOf(lines));
    }

    public static IconResult validateIcon(
            String materialName, boolean isItem, byte[] serializedBytes, MarpConfig config) {
        if (materialName == null || materialName.isBlank() || !isItem) {
            return new IconResult.Invalid();
        }
        String upper = materialName.toUpperCase(Locale.ROOT);
        if (upper.equals("AIR") || upper.equals("CAVE_AIR") || upper.equals("VOID_AIR")) {
            return new IconResult.Invalid();
        }
        if (serializedBytes == null || serializedBytes.length == 0) {
            return new IconResult.Invalid();
        }
        int maxBytes = config.maxIconBytes();
        if (serializedBytes.length > maxBytes) {
            return new IconResult.TooLarge(serializedBytes.length, maxBytes);
        }
        return new IconResult.Valid(new IconSpec(upper, serializedBytes));
    }

    /**
     * Renders an entry's display name, falling back to the plain identifier if
     * the stored value is absent, exceeds raw limits, or no longer parses cleanly.
     */
    public static Component renderEntryName(
            String identifier, String storedDisplayName, MessageService messages, MarpConfig config) {
        if (storedDisplayName == null || storedDisplayName.isBlank()) {
            return Component.text(identifier);
        }
        if (storedDisplayName.length() > config.maxDisplayNameRaw()) {
            return Component.text(identifier);
        }
        if (!config.allowMiniMessage()) {
            return Component.text(storedDisplayName);
        }
        if (hasMalformedAngleBrackets(storedDisplayName)) {
            return Component.text(identifier);
        }
        String visible = messages.visibleText(storedDisplayName);
        if (visible.isBlank()) {
            return Component.text(identifier);
        }
        return messages.renderUserText(storedDisplayName, true);
    }

    static boolean hasMalformedAngleBrackets(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (ch == '<') {
                if (depth > 0) {
                    return true;
                }
                depth = 1;
            } else if (ch == '>') {
                if (depth == 0) {
                    return true;
                }
                depth = 0;
            }
        }
        return depth != 0;
    }
}
