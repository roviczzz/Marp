package me.roviczzz.marp.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.BackKind;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.util.LocationCodec;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /back} command handler (DESIGN.md §3.3). */
public final class BackCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final BackService backService;
    private final TeleportService teleportService;
    private final Scheduler scheduler;

    public BackCommand(
            CommandSupport support,
            BackService backService,
            TeleportService teleportService,
            Scheduler scheduler) {
        this.support = support;
        this.backService = backService;
        this.teleportService = teleportService;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requirePermission(player, "marp.back")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().backEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        if (args.length > 0) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/back"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        backService.resolve(uuid).whenComplete((entryOpt, error) -> scheduler.runOnMain(() -> {
            Player online = support.onlinePlayer(uuid);
            if (online == null || online.getWorld() == null) {
                return;
            }
            if (error != null || entryOpt == null) {
                support.messages().send(online, Messages.STORAGE_ERROR);
                return;
            }
            if (entryOpt.isEmpty()) {
                support.messages().send(online, Messages.BACK_NO_LOCATION);
                return;
            }
            BackEntry entry = entryOpt.get();
            LocationSnapshot current = LocationCodec.fromLocation(online.getLocation());
            if (current.world().equals(entry.location().world())
                    && current.squaredDistance(entry.location()) <= 1.0) {
                support.messages().send(online, Messages.BACK_ALREADY_THERE);
                return;
            }
            String targetKey = entry.kind() == BackKind.DEATH
                    ? Messages.BACK_TARGET_DEATH
                    : Messages.BACK_TARGET_TELEPORT;
            support.messages().send(
                    online, targetKey, Map.of("world", entry.location().world()));
            teleportService.requestTeleport(
                    uuid, TeleportReason.BACK, "back", entry.location(), entry.kind());
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
