package me.roviczzz.marp.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.roviczzz.marp.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TpaPresenterTest {

    @Test
    void requestReceivedBuildsClickableAcceptAndDenyButtonsWithHoverDetails() throws Exception {
        MessageService messages;
        try (InputStreamReader reader = new InputStreamReader(
                TpaPresenterTest.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8)) {
            messages = new MessageService(YamlConfiguration.loadConfiguration(reader), true, ignored -> {
            });
        }
        TpaPresenter presenter = new TpaPresenter(() -> messages);
        TpaRequest req = new TpaRequest(
                7L,
                TpaDirection.TPA,
                UUID.randomUUID(),
                "Alice",
                UUID.randomUUID(),
                "Bob",
                1_000L,
                46_000L);

        Component prompt = presenter.requestReceived(req, 45, "world_nether");
        List<ClickEvent> clicks = new ArrayList<>();
        List<HoverEvent<?>> hovers = new ArrayList<>();
        collectEvents(prompt, clicks, hovers);

        assertEquals(2, clicks.size());
        assertEquals(ClickEvent.Action.RUN_COMMAND, clicks.get(0).action());
        assertEquals("/tpaccept Alice", clicks.get(0).value());
        assertEquals("/tpdeny Alice", clicks.get(1).value());
        assertEquals(2, hovers.size());
        Component acceptHover = (Component) hovers.get(0).value();
        assertNotNull(acceptHover);
        String hoverPlain = messages.plain(acceptHover);
        assertTrue(hoverPlain.contains("world_nether"));
        assertTrue(hoverPlain.contains("45s"));

        Component sent = presenter.requestSent(req, 45);
        List<ClickEvent> sentClicks = new ArrayList<>();
        collectEvents(sent, sentClicks, new ArrayList<>());
        assertEquals(1, sentClicks.size());
        assertEquals("/tpcancel", sentClicks.get(0).value());
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
