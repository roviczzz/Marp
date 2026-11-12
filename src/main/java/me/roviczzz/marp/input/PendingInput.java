package me.roviczzz.marp.input;

import java.util.UUID;
import java.util.function.Consumer;
import me.roviczzz.marp.util.Scheduler;

/** One active chat-input session for a player. */
public record PendingInput(
        long token,
        UUID player,
        Consumer<String> onInput,
        Runnable onCancel,
        Scheduler.Cancellable timeoutTask) {
}
