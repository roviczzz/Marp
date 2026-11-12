package me.roviczzz.marp.listener;

import me.roviczzz.marp.teleport.CombatTracker;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Records PvP combat hits into {@link CombatTracker} for optional pre-teleport
 * combat-tag checks.
 */
public final class CombatListener implements Listener {

    private final CombatTracker tracker;

    public CombatListener(CombatTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            tracker.recordCombat(attacker.getUniqueId(), victim.getUniqueId());
        }
    }
}
