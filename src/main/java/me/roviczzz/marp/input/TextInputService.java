package me.roviczzz.marp.input;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Seam for capturing player text input from the GUI.
 *
 * <p>v0.1 ships {@link ChatTextInput}; keeping this interface means Dialog or
 * anvil implementations can be added later without changing menu code.
 */
public interface TextInputService {

    boolean hasPending(UUID player);

    void beginInput(UUID player, Consumer<String> onInput, Runnable onCancel);

    boolean handleChatFromAsync(UUID player, String plainText);

    void cancelWithNotice(UUID player);

    void clearSilent(UUID player);

    void cancelAllWithNotice();
}
