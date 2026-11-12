package me.roviczzz.marp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessageServiceTest {

    private MessageService service() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prefix", "<gray>[Marp]</gray> ");
        yaml.set("general.no-permission", "{prefix}<red>No permission.");
        yaml.set("home.set", "{prefix}<green>Home <white>{name}</white> set.");
        yaml.set("gui.input-invalid", "{prefix}<red>Invalid: <white>{reason}</white>.");
        return new MessageService(yaml, true, message -> {
        });
    }

    @Test
    void placeholdersAreSubstituted() {
        Component component = service().component("home.set", Map.of("name", "base"));
        assertEquals("[Marp] Home base set.", service().plain(component));
    }

    @Test
    void formattingIsApplied() {
        Component component = service().render("<red>No permission.", Map.of(), true);
        assertEquals(NamedTextColor.RED, component.color());
    }

    @Test
    void userValuesCannotInjectTags() {
        Component component = service().component("home.set", Map.of("name", "<selector:@a>"));
        String plain = service().plain(component);
        assertTrue(plain.contains("<selector:@a>"), plain);
        assertFalse(plain.contains("players"), plain);
    }

    @Test
    void unknownKeyReturnsAVisibleFallback() {
        MessageService service = service();
        assertFalse(service.has("nope.missing"));
        assertEquals("[nope.missing]", service.plain(service.component("nope.missing")));
    }

    @Test
    void brokenTemplateFallsBackToPlainText() {
        MessageService service = service();
        Component component = service.render("<red>unclosed <gradient:>", Map.of(), true);
        assertNotNull(component);
        assertFalse(service.plain(component).isBlank());
    }

    @Test
    void oversizedTemplateIsBounded() {
        MessageService service = service();
        String huge = "<red>" + "x".repeat(20_000);
        Component component = service.render(huge, Map.of(), true);
        assertTrue(service.plain(component).length() <= 512);
    }

    @Test
    void displayNameRenderingFallsBackWhenMalformed() {
        MessageService service = service();
        assertEquals("Base Home", service.plain(service.renderUserText("Base Home", true)));
        assertNotNull(service.renderUserText("<red>broken", true));
    }
}
