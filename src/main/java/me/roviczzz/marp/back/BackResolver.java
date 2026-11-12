package me.roviczzz.marp.back;

import java.util.Optional;
import me.roviczzz.marp.config.MarpConfig.BackPreference;
import me.roviczzz.marp.model.BackEntry;

/**
 * Chooses between a player's death point and teleport origin.
 *
 * <p>Pure and side-effect free: recording, persistence, and fallback handling
 * live in {@link BackService}.
 */
public final class BackResolver {

    private BackResolver() {
    }

    public static Optional<BackEntry> resolve(BackEntry death, BackEntry teleport, BackPreference preference) {
        if (preference == BackPreference.DEATH) {
            return death != null ? Optional.of(death) : Optional.ofNullable(teleport);
        }
        if (preference == BackPreference.TELEPORT) {
            return teleport != null ? Optional.of(teleport) : Optional.ofNullable(death);
        }
        if (death == null) {
            return Optional.ofNullable(teleport);
        }
        if (teleport == null) {
            return Optional.of(death);
        }
        // Newest wins; a tie is treated as the death point.
        return death.createdAt() >= teleport.createdAt() ? Optional.of(death) : Optional.of(teleport);
    }
}
