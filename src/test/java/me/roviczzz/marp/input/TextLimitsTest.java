package me.roviczzz.marp.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MenuListener;
import me.roviczzz.marp.input.TextLimits.IconResult;
import me.roviczzz.marp.input.TextLimits.LoreResult;
import me.roviczzz.marp.input.TextLimits.NameResult;
import me.roviczzz.marp.util.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class TextLimitsTest {

    private MarpConfig config() {
        return new MarpConfig(new YamlConfiguration());
    }

    private MessageService messages() {
        return new MessageService(new YamlConfiguration(), true, ignored -> {
        });
    }

    @Test
    void displayNameEnforcesVisibleAndRawLengthBoundaries() {
        MessageService msg = messages();
        MarpConfig cfg = config();

        // A gradient-formatted name whose raw length > 32 but visible length <= 32 is accepted.
        String coloured = "<gradient:#00c6ff:#0072ff>My Cozy Base</gradient>";
        assertTrue(coloured.length() > 32);
        NameResult ok = TextLimits.validateDisplayName(coloured, msg, cfg);
        assertInstanceOf(NameResult.Valid.class, ok);

        // Exactly 32 visible chars is accepted; 33 visible chars is rejected (never truncated).
        assertInstanceOf(
                NameResult.Valid.class,
                TextLimits.validateDisplayName("a".repeat(32), msg, cfg));
        assertInstanceOf(
                NameResult.Invalid.class,
                TextLimits.validateDisplayName("a".repeat(33), msg, cfg));

        // Exceeding 128 raw chars is rejected even when visible text is short.
        String hugeRaw = "<red>" + "<bold>".repeat(25) + "hi";
        assertTrue(hugeRaw.length() > 128);
        assertInstanceOf(
                NameResult.Invalid.class,
                TextLimits.validateDisplayName(hugeRaw, msg, cfg));

        // Empty or tag-only (zero visible chars) is rejected.
        assertInstanceOf(NameResult.Invalid.class, TextLimits.validateDisplayName("   ", msg, cfg));
        assertInstanceOf(NameResult.Invalid.class, TextLimits.validateDisplayName("<red></red>", msg, cfg));
    }

    @Test
    void loreEnforcesLineCountVisibleLengthAndRawLength() {
        MessageService msg = messages();
        MarpConfig cfg = config();

        // Empty input clears the lore.
        LoreResult cleared = TextLimits.validateLore("", msg, cfg);
        assertEquals(List.of(), assertInstanceOf(LoreResult.Valid.class, cleared).lines());

        // 4 lines within limits are accepted; 5 lines are rejected.
        LoreResult four = TextLimits.validateLore("one | two | three | four", msg, cfg);
        assertEquals(List.of("one", "two", "three", "four"), assertInstanceOf(LoreResult.Valid.class, four).lines());
        assertInstanceOf(LoreResult.Invalid.class, TextLimits.validateLore("1|2|3|4|5", msg, cfg));

        // 64 visible chars on a line passes; 65 is rejected.
        assertInstanceOf(LoreResult.Valid.class, TextLimits.validateLore("x".repeat(64), msg, cfg));
        assertInstanceOf(LoreResult.Invalid.class, TextLimits.validateLore("x".repeat(65), msg, cfg));

        // > 256 raw chars on a single line is rejected.
        String longRawLine = "<green>" + "<italic>".repeat(35) + "ok";
        assertTrue(longRawLine.length() > 256);
        assertInstanceOf(LoreResult.Invalid.class, TextLimits.validateLore(longRawLine, msg, cfg));
    }

    @Test
    void iconEnforcesMaterialAnd16KiBCap() {
        MarpConfig cfg = config();

        assertInstanceOf(
                IconResult.Valid.class,
                TextLimits.validateIcon("DIAMOND_SWORD", true, new byte[16_384], cfg));
        assertInstanceOf(
                IconResult.TooLarge.class,
                TextLimits.validateIcon("DIAMOND_SWORD", true, new byte[16_385], cfg));
        assertInstanceOf(
                IconResult.Invalid.class,
                TextLimits.validateIcon("AIR", true, new byte[] {1}, cfg));
        assertInstanceOf(
                IconResult.Invalid.class,
                TextLimits.validateIcon("WATER", false, new byte[] {1}, cfg));
    }

    @Test
    void storedDisplayValueThatNoLongerParsesFallsBackToIdentifier() {
        MessageService msg = messages();
        MarpConfig cfg = config();

        Component valid = TextLimits.renderEntryName("base", "<gold>Home Base", msg, cfg);
        assertEquals("Home Base", msg.plain(valid));

        Component broken = TextLimits.renderEntryName("base", "<red>unclosed <gradient", msg, cfg);
        assertEquals("base", msg.plain(broken));

        Component emptyVisible = TextLimits.renderEntryName("base", "<red></red>", msg, cfg);
        assertEquals("base", msg.plain(emptyVisible));
    }

    @Test
    void chatTextInputCapturesCancelAndAppliesInput() {
        UUID player = UUID.randomUUID();
        List<String> notices = new ArrayList<>();
        ChatTextInput input = new ChatTextInput(
                Scheduler.direct(),
                this::config,
                (u, key) -> notices.add(key));

        AtomicReference<String> received = new AtomicReference<>();
        boolean[] cancelled = {false};

        input.beginInput(player, received::set, () -> cancelled[0] = true);
        assertTrue(input.hasPending(player));
        assertTrue(input.handleChatFromAsync(player, "cancel"));
        assertFalse(input.hasPending(player));
        assertTrue(cancelled[0]);
        assertTrue(notices.contains(Messages.GUI_INPUT_CANCELLED));

        input.beginInput(player, received::set, () -> {
        });
        assertTrue(input.handleChatFromAsync(player, "<aqua>My Home"));
        assertEquals("<aqua>My Home", received.get());
    }

    @Test
    void menuListenerDebounceAndSupportedClickTypes() {
        long[] now = {1_000L};
        MenuListener listener = new MenuListener(() -> now[0]);
        UUID player = UUID.randomUUID();

        assertTrue(listener.allowAction(player));
        now[0] += 50L;
        assertFalse(listener.allowAction(player));
        now[0] += 200L;
        assertTrue(listener.allowAction(player));

        assertTrue(MenuListener.isSupportedClickType(ClickType.LEFT));
        assertTrue(MenuListener.isSupportedClickType(ClickType.RIGHT));
        assertTrue(MenuListener.isSupportedClickType(ClickType.SHIFT_LEFT));
        assertFalse(MenuListener.isSupportedClickType(ClickType.NUMBER_KEY));
        assertFalse(MenuListener.isSupportedClickType(ClickType.DOUBLE_CLICK));
        assertFalse(MenuListener.isSupportedClickType(ClickType.SWAP_OFFHAND));
    }
}
