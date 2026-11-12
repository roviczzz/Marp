package me.roviczzz.marp.listener;

import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.teleport.WarmupSampler;
import me.roviczzz.marp.util.LocationCodec;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Cancels any pending warmup on death and records the death location for
 * {@code /back} (separate from the respawn point).
 */
public final class DeathListener implements Listener {

    private final WarmupSampler sampler;
    private final BackService backService;

    public DeathListener(WarmupSampler sampler, BackService backService) {
        this.sampler = sampler;
        this.backService = backService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        sampler.onDeath(player.getUniqueId());
        if (player.getWorld() == null) {
            return;
        }
        EntityDamageEvent lastDamage = player.getLastDamageCause();
        String cause = lastDamage != null ? lastDamage.getCause().name() : "DEATH";
        backService.recordDeath(
                player.getUniqueId(), LocationCodec.fromLocation(player.getLocation()), cause);
    }
}
