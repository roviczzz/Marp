package me.roviczzz.marp.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MenuManager;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;
import me.roviczzz.marp.warp.WarpService.ResolveOutcome;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /warp [name]} command handler (DESIGN.md §3.3). */
public final class WarpCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final WarpService warpService;
    private final WarpCache warpCache;
    private final TeleportService teleportService;
    private final MenuManager menuManager;
    private final Scheduler scheduler;

    public WarpCommand(
            CommandSupport support,
            WarpService warpService,
            WarpCache warpCache,
            TeleportService teleportService,
            MenuManager menuManager,
            Scheduler scheduler) {
        this.support = support;
        this.warpService = warpService;
        this.warpCache = warpCache;
        this.teleportService = teleportService;
        this.menuManager = menuManager;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().warpsEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        if (args.length > 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/warp [name]"));
            return true;
        }
        if (args.length == 0) {
            if (!support.requirePermission(player, "marp.warps")) {
                return true;
            }
            menuManager.openWarpList(player, 0);
            return true;
        }
        if (!support.requirePermission(player, "marp.warp")) {
            return true;
        }

        UUID uuid = player.getUniqueId();
        String nameArg = args[0];

        warpService.resolveForTeleport(nameArg, player::hasPermission)
                .whenComplete((outcome, error) -> scheduler.runOnMain(() -> {
                    Player online = support.onlinePlayer(uuid);
                    if (online == null) {
                        return;
                    }
                    if (error != null || outcome == null) {
                        support.messages().send(online, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (outcome instanceof ResolveOutcome.Allowed allowed) {
                        Warp warp = allowed.warp();
                        teleportService.requestTeleport(
                                uuid, TeleportReason.WARP, warp.nameKey(), warp.location(), null);
                    } else if (outcome instanceof ResolveOutcome.NotFound notFound) {
                        support.messages().send(
                                online, Messages.WARP_NOT_FOUND, Map.of("name", notFound.nameKey()));
                    } else if (outcome instanceof ResolveOutcome.Denied denied) {
                        support.messages().send(
                                online, Messages.WARP_PERMISSION_DENIED, Map.of("name", denied.nameKey()));
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("marp.warp")) {
            return List.of();
        }
        return CompletionSupport.completeVisibleWarps(
                args[0],
                warpCache,
                warpService,
                sender::hasPermission,
                support.config().warpsEnabled());
    }
}
