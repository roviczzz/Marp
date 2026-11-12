package me.roviczzz.marp.listener;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.teleport.WarmupSampler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Warms a player's home cache on join and cleans up all session-scoped state on
 * quit.
 */
public final class PlayerConnectionListener implements Listener {

    private final BooleanSupplier storageReady;
    private final HomeService homeService;
    private final HomeCache homeCache;
    private final BackService backService;
    private final WarmupSampler sampler;
    private final Consumer<UUID> quitCleanupHook;

    public PlayerConnectionListener(
            BooleanSupplier storageReady,
            HomeService homeService,
            HomeCache homeCache,
            BackService backService,
            WarmupSampler sampler,
            Consumer<UUID> quitCleanupHook) {
        this.storageReady = storageReady;
        this.homeService = homeService;
        this.homeCache = homeCache;
        this.backService = backService;
        this.sampler = sampler;
        this.quitCleanupHook = quitCleanupHook;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (storageReady.getAsBoolean()) {
            homeService.loadCache(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sampler.onQuit(uuid);
        homeCache.clear(uuid);
        backService.clearPlayerTransient(uuid);
        quitCleanupHook.accept(uuid);
    }
}
