package me.roviczzz.marp.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.util.LocationCodec;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpService;
import me.roviczzz.marp.warp.WarpService.SetOutcome;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /setwarp <name>} command handler (DESIGN.md §3.3). */
public final class SetWarpCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final WarpService warpService;
    private final Scheduler scheduler;

    public SetWarpCommand(CommandSupport support, WarpService warpService, Scheduler scheduler) {
        this.support = support;
        this.warpService = warpService;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requirePermission(player, "marp.admin.setwarp")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().warpsEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        if (args.length != 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/setwarp <name>"));
            return true;
        }
        if (player.getWorld() == null) {
            return true;
        }

        UUID uuid = player.getUniqueId();
        String nameArg = args[0];
        LocationSnapshot location = LocationCodec.fromLocation(player.getLocation());

        warpService.setWarp(nameArg, location, uuid)
                .whenComplete((outcome, error) -> scheduler.runOnMain(() -> {
                    Player online = support.onlinePlayer(uuid);
                    if (online == null) {
                        return;
                    }
                    if (error != null || outcome == null) {
                        support.messages().send(online, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (outcome instanceof SetOutcome.Created created) {
                        support.messages().send(
                                online, Messages.WARP_SET, Map.of("name", created.warp().nameKey()));
                    } else if (outcome instanceof SetOutcome.Moved moved) {
                        support.messages().send(
                                online, Messages.WARP_UPDATED, Map.of("name", moved.warp().nameKey()));
                    } else if (outcome instanceof SetOutcome.InvalidName invalid) {
                        support.messages().send(
                                online, Messages.WARP_NAME_INVALID, Map.of("pattern", invalid.pattern()));
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
