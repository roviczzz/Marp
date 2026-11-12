package me.roviczzz.marp.teleport;

import java.util.UUID;

/**
 * Guard seam for pre-teleport restrictions such as PvP combat tagging.
 *
 * <p>External plugins (e.g. CombatLogX) can implement this without modifying
 * {@link me.roviczzz.marp.tpa.TpaService}.
 */
@FunctionalInterface
public interface TeleportGuard {

    TeleportGuard NONE = player -> 0L;

    /**
     * Returns remaining combat-tag seconds for {@code player}, or {@code 0} if
     * the player is free to teleport.
     */
    long combatRemainingSeconds(UUID player);
}
