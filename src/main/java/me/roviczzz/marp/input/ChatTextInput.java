package me.roviczzz.marp.input;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Chat-backed implementation of {@link TextInputService}.
 *
 * <p>Consumes the player's next chat message, cancels it on the async chat
 * thread, and validates/applies the value on the main thread.
 */
public final class ChatTextInput implements TextInputService {

    private final Scheduler scheduler;
    private final Supplier<MarpConfig> config;
    private final BiConsumer<UUID, String> messageSender;
    private final AtomicLong nextToken = new AtomicLong(1L);
    private final Map<UUID, PendingInput> pending = new ConcurrentHashMap<>();

    public ChatTextInput(
            Scheduler scheduler,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages) {
        this(scheduler, config, (uuid, key) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                messages.get().send(player, key);
            }
        });
    }

    public ChatTextInput(
            Scheduler scheduler,
            Supplier<MarpConfig> config,
            BiConsumer<UUID, String> messageSender) {
        this.scheduler = scheduler;
        this.config = config;
        this.messageSender = messageSender;
    }

    @Override
    public boolean hasPending(UUID player) {
        return pending.containsKey(player);
    }

    @Override
    public void beginInput(UUID player, Consumer<String> onInput, Runnable onCancel) {
        long token = nextToken.getAndIncrement();
        long timeoutTicks = Math.max(1L, config.get().inputTimeoutSeconds()) * 20L;
        Scheduler.Cancellable timeoutTask = scheduler.runLater(() -> expireToken(player, token), timeoutTicks);
        PendingInput session = new PendingInput(token, player, onInput, onCancel, timeoutTask);
        PendingInput previous = pending.put(player, session);
        if (previous != null) {
            previous.timeoutTask().cancel();
        }
    }

    @Override
    public boolean handleChatFromAsync(UUID player, String plainText) {
        PendingInput current = pending.remove(player);
        if (current == null) {
            return false;
        }
        current.timeoutTask().cancel();
        String text = plainText == null ? "" : plainText.trim();
        scheduler.runOnMain(() -> {
            if ("cancel".equalsIgnoreCase(text)) {
                messageSender.accept(player, Messages.GUI_INPUT_CANCELLED);
                if (current.onCancel() != null) {
                    current.onCancel().run();
                }
            } else {
                current.onInput().accept(text);
            }
        });
        return true;
    }

    @Override
    public void cancelWithNotice(UUID player) {
        PendingInput removed = pending.remove(player);
        if (removed != null) {
            removed.timeoutTask().cancel();
            messageSender.accept(player, Messages.GUI_INPUT_CANCELLED);
        }
    }

    @Override
    public void clearSilent(UUID player) {
        PendingInput removed = pending.remove(player);
        if (removed != null) {
            removed.timeoutTask().cancel();
        }
    }

    @Override
    public void cancelAllWithNotice() {
        for (UUID uuid : List.copyOf(pending.keySet())) {
            cancelWithNotice(uuid);
        }
    }

    private void expireToken(UUID player, long expectedToken) {
        PendingInput[] expired = new PendingInput[1];
        pending.computeIfPresent(player, (ignored, current) -> {
            if (current.token() != expectedToken) {
                return current;
            }
            expired[0] = current;
            return null;
        });
        if (expired[0] != null) {
            messageSender.accept(player, Messages.GUI_INPUT_TIMEOUT);
        }
    }
}
