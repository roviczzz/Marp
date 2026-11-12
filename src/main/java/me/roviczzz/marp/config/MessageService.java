package me.roviczzz.marp.config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads every player-facing string from {@code messages.yml}.
 *
 * <p>Placeholders use {@code {name}} syntax and are escaped before parsing, so
 * a world name or player name can never inject MiniMessage. The parser is built
 * with a resolver whitelist: selectors, scores, NBT, click, and hover tags are
 * not resolvable at all, which is stronger than stripping them afterwards.
 */
public final class MessageService {

    private static final int MAX_TEMPLATE_LENGTH = 8192;
    private static final String PREFIX = "prefix";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final YamlConfiguration messages;
    private final Consumer<String> warn;
    private final MiniMessage mini;
    private final Set<String> reported = new LinkedHashSet<>();

    public MessageService(YamlConfiguration messages, boolean allowMiniMessage, Consumer<String> warn) {
        this.messages = messages;
        this.warn = warn;
        TagResolver whitelist = TagResolver.builder()
                .resolver(StandardTags.color())
                .resolver(StandardTags.decorations())
                .resolver(StandardTags.gradient())
                .resolver(StandardTags.rainbow())
                .resolver(StandardTags.reset())
                .resolver(StandardTags.newline())
                .resolver(StandardTags.translatable())
                .resolver(StandardTags.keybind())
                .resolver(StandardTags.font())
                .build();
        this.mini = MiniMessage.builder()
                .tags(whitelist)
                .strict(false)
                .build();
    }

    public boolean has(String key) {
        return messages.getString(key) != null;
    }

    public String raw(String key) {
        return messages.getString(key);
    }

    public Component component(String key, Map<String, String> placeholders) {
        String template = raw(key);
        if (template == null) {
            reportOnce("missing-message:" + key, "Missing message key '" + key + "' in messages.yml");
            return Component.text("[" + key + "]");
        }
        return render(template, placeholders, true);
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component render(String template, Map<String, String> placeholders, boolean parseMiniMessage) {
        if (template == null) {
            return Component.empty();
        }
        if (template.length() > MAX_TEMPLATE_LENGTH) {
            reportOnce(
                    "oversized:" + Integer.toHexString(template.hashCode()),
                    "A message template is longer than " + MAX_TEMPLATE_LENGTH + " characters; using plain fallback");
            return Component.text(template.substring(0, Math.min(512, template.length())));
        }

        String substituted = substitute(template, placeholders);
        if (!parseMiniMessage) {
            return Component.text(substituted);
        }
        try {
            return mini.deserialize(substituted);
        } catch (RuntimeException exception) {
            reportOnce(
                    "parse:" + Integer.toHexString(template.hashCode()),
                    "Could not parse a message template (" + exception.getMessage() + "); using plain fallback");
            return Component.text(stripMarkup(substituted));
        }
    }

    public Component renderUserText(String text, boolean parseMiniMessage) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        if (text.length() > MAX_TEMPLATE_LENGTH) {
            return Component.text(text.substring(0, Math.min(256, text.length())));
        }
        if (!parseMiniMessage) {
            return Component.text(text);
        }
        try {
            return mini.deserialize(text);
        } catch (RuntimeException exception) {
            return Component.text(stripMarkup(text));
        }
    }

    public String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public String visibleText(String markup) {
        if (markup == null || markup.isEmpty()) {
            return "";
        }
        try {
            return plain(mini.deserialize(markup));
        } catch (RuntimeException exception) {
            return stripMarkup(markup);
        }
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component(key, placeholders));
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public String formatDate(long epochMillis) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    public MiniMessage miniMessage() {
        return mini;
    }

    private String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        String prefix = raw(PREFIX);
        if (prefix != null) {
            result = result.replace("{prefix}", prefix);
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                result = result.replace("{" + entry.getKey() + "}", mini.escapeTags(value));
            }
        }
        return result;
    }

    private static String stripMarkup(String text) {
        return text.replaceAll("<[^<>]{1,64}>", "");
    }

    private void reportOnce(String token, String message) {
        if (reported.add(token)) {
            warn.accept(message);
        }
    }

    /** Convenience factory for the pair-based API used by commands. */
    public static Map<String, String> placeholders(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be key/value pairs");
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            result.put(keyValuePairs[i].toLowerCase(Locale.ROOT), keyValuePairs[i + 1]);
        }
        return result;
    }
}
