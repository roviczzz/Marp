package me.roviczzz.marp.help;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.model.Page;
import me.roviczzz.marp.model.Pagination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HelpPresenterTest {

    @Test
    void rendersPaginatedHelpWithClickableRowsAndCategoryHeaders() throws Exception {
        MessageService messages;
        try (InputStreamReader reader = new InputStreamReader(
                HelpPresenterTest.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8)) {
            messages = new MessageService(YamlConfiguration.loadConfiguration(reader), true, ignored -> {
            });
        }
        HelpPresenter presenter = new HelpPresenter(() -> messages);
        MarpConfig config = new MarpConfig(new YamlConfiguration());

        List<HelpEntry> visible = HelpCatalog.visibleEntries(p -> true, config);
        assertTrue(visible.size() > 7);

        Page page1 = Page.of(0, visible.size(), 7);
        List<HelpEntry> slice1 = page1.slice(visible);
        Component page1Comp = presenter.renderPage(visible, slice1, page1, true);

        List<ClickEvent> clicks = new ArrayList<>();
        List<HoverEvent<?>> hovers = new ArrayList<>();
        collectEvents(page1Comp, clicks, hovers);

        assertFalse(clicks.isEmpty());
        // Footer has next page click.
        assertTrue(clicks.stream().anyMatch(c -> c.value().equals("/marp help 2")), "clicks was: " + clicks);
        // Entry clicks contain suggestCommand or runCommand.
        assertTrue(clicks.stream().anyMatch(c -> c.action() == ClickEvent.Action.RUN_COMMAND));
        assertTrue(clicks.stream().anyMatch(c -> c.action() == ClickEvent.Action.SUGGEST_COMMAND));

        String plain = messages.plain(page1Comp);
        assertTrue(plain.contains("Homes"));
        assertTrue(plain.contains("Page 1 of"));
    }

    private static void collectEvents(
            Component component, List<ClickEvent> clicks, List<HoverEvent<?>> hovers) {
        if (component.clickEvent() != null) {
            clicks.add(component.clickEvent());
        }
        if (component.hoverEvent() != null) {
            hovers.add(component.hoverEvent());
        }
        for (Component child : component.children()) {
            collectEvents(child, clicks, hovers);
        }
    }
}
