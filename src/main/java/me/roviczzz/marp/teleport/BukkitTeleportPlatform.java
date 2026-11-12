package me.roviczzz.marp.teleport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.util.LocationCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Paper-backed implementation of {@link TeleportPlatform} and
 * {@link WarmupSampler.PlayerStateProvider}.
 */
public final class BukkitTeleportPlatform implements TeleportPlatform, WarmupSampler.PlayerStateProvider {

    public static final String PERM_BYPASS_COOLDOWN = "marp.bypass.cooldown";
    public static final String PERM_BYPASS_WARMUP = "marp.bypass.warmup";
    public static final String PERM_BYPASS_SAFETY = "marp.bypass.safety";

    private final SafetyService safetyService;
    private final Supplier<MessageService> messages;
    private final Consumer<UUID> closeMarpMenuHook;

    public BukkitTeleportPlatform(
            SafetyService safetyService,
            Supplier<MessageService> messages,
            Consumer<UUID> closeMarpMenuHook) {
        this.safetyService = safetyService;
        this.messages = messages;
        this.closeMarpMenuHook = closeMarpMenuHook;
    }

    @Override
    public Optional<PlayerContext> player(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return Optional.empty();
        }
        return Optional.of(new PlayerContext(
                player.getUniqueId(),
                LocationCodec.fromLocation(player.getLocation()),
                player.hasPermission(PERM_BYPASS_COOLDOWN),
                player.hasPermission(PERM_BYPASS_WARMUP),
                player.hasPermission(PERM_BYPASS_SAFETY)));
    }

    @Override
    public boolean isWorldLoaded(String worldName) {
        return Bukkit.getWorld(worldName) != null;
    }

    @Override
    public CompletableFuture<SafetyResult> evaluateSafety(LocationSnapshot target, MarpConfig config) {
        return safetyService.evaluate(target, config);
    }

    @Override
    public void preparePlayerForArrival(UUID uuid, MarpConfig config) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        if (config.dismountVehicle() && player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (config.closeInventory()) {
            player.closeInventory();
        }
        if (config.resetFallDistance()) {
            player.setFallDistance(0.0f);
        }
        player.sendActionBar(Component.empty());
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(UUID uuid, LocationSnapshot target) {
        Player player = Bukkit.getPlayer(uuid);
        Location location = LocationCodec.toLocation(target);
        if (player == null || !player.isOnline() || location == null) {
            return CompletableFuture.completedFuture(false);
        }
        return player.teleportAsync(location)
                .orTimeout(SafetyService.CHUNK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void sendMessage(UUID uuid, String messageKey, Map<String, String> placeholders) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            messages.get().send(player, messageKey, placeholders);
        }
    }

    @Override
    public void sendCountdown(UUID uuid, int seconds, MarpConfig config) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (config.countdownActionbar()) {
            player.sendActionBar(messages.get().component(
                    Messages.TELEPORT_WARMUP_ACTIONBAR, Map.of("seconds", Integer.toString(seconds))));
        }
        if (config.countdownSound()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);
        }
    }

    @Override
    public void clearActionBar(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendActionBar(Component.empty());
        }
    }

    @Override
    public void playArrivalEffects(UUID uuid, MarpConfig config) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (config.countdownSound()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        }
        if (config.titleOnStart()) {
            player.showTitle(Title.title(
                    messages.get().component(Messages.TELEPORT_WARMUP_TITLE), Component.empty()));
        }
    }

    @Override
    public void closeMarpMenuIfOpen(UUID uuid) {
        closeMarpMenuHook.accept(uuid);
    }

    @Override
    public Collection<PlayerSample> onlinePlayers() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<PlayerSample> samples = new ArrayList<>(online.size());
        for (Player player : online) {
            if (player.getWorld() != null) {
                samples.add(toSample(player));
            }
        }
        return samples;
    }

    @Override
    public Optional<PlayerSample> sample(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || player.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.of(toSample(player));
    }

    private static PlayerSample toSample(Player player) {
        Location loc = player.getLocation();
        String feetMat = loc.getBlock().getType().name();
        return new PlayerSample(
                player.getUniqueId(),
                LocationCodec.fromLocation(loc),
                player.isOnGround(),
                feetMat,
                player.isDead());
    }
}
