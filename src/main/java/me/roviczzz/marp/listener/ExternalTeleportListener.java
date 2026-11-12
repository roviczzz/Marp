package me.roviczzz.marp.listener;

import java.util.UUID;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.teleport.TeleportSessionRegistry;
import me.roviczzz.marp.util.LocationCodec;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Optional {@code /back} origin recorder for non-Marp plugin/command teleports.
 *
 * <p>Disabled by default in {@code config.yml} and ignores a session's own
 * arrival so Marp teleports are never recorded twice.
 */
public final class ExternalTeleportListener implements Listener {

    private final TeleportSessionRegistry sessions;
    private final BackService backService;

    public ExternalTeleportListener(TeleportSessionRegistry sessions, BackService backService) {
        this.sessions = sessions;
        this.backService = backService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (sessions.isArriving(uuid)) {
            return;
        }
        TeleportCause cause = event.getCause();
        if (cause != TeleportCause.PLUGIN && cause != TeleportCause.COMMAND) {
            return;
        }
        if (event.getFrom().getWorld() == null) {
            return;
        }
        backService.recordExternalTeleportOrigin(uuid, LocationCodec.fromLocation(event.getFrom()));
    }
}
