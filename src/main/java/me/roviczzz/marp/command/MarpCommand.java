package me.roviczzz.marp.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.teleport.WarmupSampler;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpCache;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /marp <reload|cancel|info>} command handler (DESIGN.md §3.3).
 */
public final class MarpCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final String pluginVersion;
    private final SqlStorage storage;
    private final HomeCache homeCache;
    private final WarpCache warpCache;
    private final WarmupSampler warmupSampler;
    private final Runnable reloadAction;
    private final Scheduler scheduler;

    public MarpCommand(
            CommandSupport support,
            String pluginVersion,
            SqlStorage storage,
            HomeCache homeCache,
            WarpCache warpCache,
            WarmupSampler warmupSampler,
            Runnable reloadAction,
            Scheduler scheduler) {
        this.support = support;
        this.pluginVersion = pluginVersion;
        this.storage = storage;
        this.homeCache = homeCache;
        this.warpCache = warpCache;
        this.warmupSampler = warmupSampler;
        this.reloadAction = reloadAction;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            support.messages().send(
                    sender, Messages.INVALID_USAGE, Map.of("usage", "/marp <reload|cancel|info>"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "cancel" -> handleCancel(sender);
            case "info" -> handleInfo(sender);
            default -> support.messages().send(
                    sender, Messages.INVALID_USAGE, Map.of("usage", "/marp <reload|cancel|info>"));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!support.requirePermission(sender, "marp.admin.reload")) {
            return;
        }
        reloadAction.run();
        support.messages().send(sender, Messages.RELOADED);
    }

    private void handleCancel(CommandSender sender) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!support.requirePermission(player, "marp.cancel")) {
            return;
        }
        boolean cancelledActive = warmupSampler.cancelExplicit(player.getUniqueId());
        if (!cancelledActive) {
            support.messages().send(player, Messages.CANCELLED);
        }
    }

    private void handleInfo(CommandSender sender) {
        if (!support.requirePermission(sender, "marp.admin.info")) {
            return;
        }
        String storageSummary = "sqlite (" + storage.databaseFile().getFileName()
                + ", cache=" + (warpCache.isLoaded() ? warpCache.all().size() : "cold")
                + "w/" + homeCache.cachedPlayers() + "p)";
        if (!support.isStorageReady()) {
            support.messages().send(
                    sender,
                    Messages.ADMIN_INFO,
                    Map.of(
                            "version", pluginVersion,
                            "storage", storageSummary + " [starting]",
                            "homes", "?",
                            "warps", "?"));
            return;
        }

        CompletableFuture<Integer> homesCount = storage.homes().countAll();
        CompletableFuture<Integer> warpsCount = storage.warps().count();
        homesCount.thenCombine(warpsCount, Map::entry)
                .whenComplete((counts, error) -> scheduler.runOnMain(() -> {
                    if (error != null || counts == null) {
                        support.messages().send(sender, Messages.STORAGE_ERROR);
                        return;
                    }
                    support.messages().send(
                            sender,
                            Messages.ADMIN_INFO,
                            Map.of(
                                    "version", pluginVersion,
                                    "storage", storageSummary,
                                    "homes", Integer.toString(counts.getKey()),
                                    "warps", Integer.toString(counts.getValue())));
                }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return CompletionSupport.completeMarpSubcommands(args[0], sender::hasPermission);
    }
}
