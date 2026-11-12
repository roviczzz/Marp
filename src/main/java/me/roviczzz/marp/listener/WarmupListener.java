package me.roviczzz.marp.listener;

import java.util.UUID;
import java.util.function.Consumer;
import me.roviczzz.marp.teleport.WarmupSampler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Routes damage, command, and external-teleport events into {@link WarmupSampler}
 * and cancels any pending chat text input when a player runs a command.
 */
public final class WarmupListener implements Listener {

    private final WarmupSampler sampler;
    private final Consumer<UUID> cancelPendingInputHook;

    public WarmupListener(WarmupSampler sampler, Consumer<UUID> cancelPendingInputHook) {
        this.sampler = sampler;
        this.cancelPendingInputHook = cancelPendingInputHook;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            sampler.onDamage(player.getUniqueId(), event.getCause().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cancelPendingInputHook.accept(uuid);
        sampler.onCommand(uuid, event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        sampler.onExternalTeleport(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (event.getPlayer().getWorld() != null) {
            sampler.onAnchorWorldChange(
                    event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getName());
        }
    }
}
