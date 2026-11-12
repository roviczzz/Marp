package me.roviczzz.marp.listener;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.home.HomeService.SetOutcome;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.settings.PlayerSettingsService;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Automatically creates or updates the player's bed home when their respawn
 * point is set via a bed (or respawn anchor when configured).
 */
public final class BedHomeListener implements Listener {

    private final BooleanSupplier storageReady;
    private final HomeService homeService;
    private final PlayerSettingsService settingsService;
    private final ToIntFunction<Player> limitResolver;
    private final Supplier<MarpConfig> config;
    private final Supplier<MessageService> messages;
    private final Scheduler scheduler;

    public BedHomeListener(
            BooleanSupplier storageReady,
            HomeService homeService,
            PlayerSettingsService settingsService,
            ToIntFunction<Player> limitResolver,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages,
            Scheduler scheduler) {
        this.storageReady = storageReady;
        this.homeService = homeService;
        this.settingsService = settingsService;
        this.limitResolver = limitResolver;
        this.config = config;
        this.messages = messages;
        this.scheduler = scheduler;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        MarpConfig cfg = config.get();
        if (!cfg.homesEnabled() || !cfg.bedHomeEnabled()) {
            return;
        }
        Cause cause = event.getCause();
        boolean isBed = cause == Cause.BED;
        boolean isAnchor = cause == Cause.RESPAWN_ANCHOR && cfg.bedHomeIncludeRespawnAnchors();
        if (!isBed && !isAnchor) {
            return;
        }

        Location spawnLoc = event.getLocation();
        if (spawnLoc == null || spawnLoc.getWorld() == null) {
            return;
        }
        String worldName = spawnLoc.getWorld().getName();
        if (cfg.bedHomeDisabledWorlds().contains(worldName.toLowerCase(Locale.ROOT))) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission("marp.bedhome") || settingsService.isBedHomeDisabled(uuid)) {
            return;
        }
        if (!storageReady.getAsBoolean()) {
            return;
        }

        Block block = spawnLoc.getBlock();
        String iconMaterial;
        double yOffset;
        if (cause == Cause.RESPAWN_ANCHOR) {
            iconMaterial = "RESPAWN_ANCHOR";
            yOffset = 1.0;
        } else if (block.getBlockData() instanceof Bed) {
            iconMaterial = block.getType().name();
            yOffset = 0.5625;
        } else {
            iconMaterial = "RED_BED";
            yOffset = 0.5625;
        }

        Location playerLoc = player.getLocation();
        LocationSnapshot target = new LocationSnapshot(
                worldName,
                spawnLoc.getBlockX() + 0.5,
                spawnLoc.getBlockY() + yOffset,
                spawnLoc.getBlockZ() + 0.5,
                playerLoc.getYaw(),
                playerLoc.getPitch());
        int limit = limitResolver.applyAsInt(player);

        homeService.setBedHome(uuid, target, iconMaterial, limit)
                .whenComplete((outcome, error) -> scheduler.runOnMain(() -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null || !online.isOnline() || error != null || outcome == null) {
                        return;
                    }
                    if (outcome instanceof SetOutcome.Created created) {
                        messages.get().send(
                                online, Messages.BED_SET, Map.of("name", created.home().nameKey()));
                    } else if (outcome instanceof SetOutcome.Updated updated) {
                        messages.get().send(
                                online, Messages.BED_UPDATED, Map.of("name", updated.home().nameKey()));
                    }
                }));
    }
}
