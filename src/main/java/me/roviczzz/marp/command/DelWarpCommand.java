package me.roviczzz.marp.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** {@code /delwarp <name>} command handler (DESIGN.md §3.3, console-allowed). */
public final class DelWarpCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final WarpService warpService;
    private final WarpCache warpCache;
    private final Scheduler scheduler;

    public DelWarpCommand(
            CommandSupport support,
            WarpService warpService,
            WarpCache warpCache,
            Scheduler scheduler) {
        this.support = support;
        this.warpService = warpService;
        this.warpCache = warpCache;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!support.requirePermission(sender, "marp.admin.delwarp")) {
            return true;
        }
        if (!support.requireFeatureEnabled(sender, support.config().warpsEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(sender)) {
            return true;
        }
        if (args.length != 1) {
            support.messages().send(sender, Messages.INVALID_USAGE, Map.of("usage", "/delwarp <name>"));
            return true;
        }

        String nameArg = args[0];
        String expectedKey = nameArg.toLowerCase(Locale.ROOT);

        warpService.deleteByName(nameArg)
                .whenComplete((deletedOpt, error) -> scheduler.runOnMain(() -> {
                    if (error != null || deletedOpt == null) {
                        support.messages().send(sender, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (deletedOpt.isPresent()) {
                        support.messages().send(
                                sender,
                                Messages.WARP_DELETED,
                                Map.of("name", deletedOpt.get().nameKey()));
                    } else {
                        support.messages().send(
                                sender, Messages.WARP_NOT_FOUND, Map.of("name", expectedKey));
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return CompletionSupport.completeAllWarps(
                args[0],
                warpCache,
                support.config().warpsEnabled(),
                sender.hasPermission("marp.admin.delwarp"));
    }
}
