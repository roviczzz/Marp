package me.roviczzz.marp.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.UUID;
import me.roviczzz.marp.input.TextInputService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Captures the next chat message for a player with a pending GUI text edit.
 *
 * <p>{@link AsyncChatEvent} fires off the main thread; the message is converted
 * to plain text immediately and handed to {@link TextInputService}, which hops
 * back to the main thread for validation and application.
 */
public final class InputListener implements Listener {

    private final TextInputService inputService;

    public InputListener(TextInputService inputService) {
        this.inputService = inputService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!inputService.hasPending(uuid)) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (inputService.handleChatFromAsync(uuid, plain)) {
            event.setCancelled(true);
        }
    }
}
