package me.roviczzz.marp.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.help.HelpCatalog;
import me.roviczzz.marp.help.HelpEntry;
import me.roviczzz.marp.help.HelpPresenter;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.model.Page;
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
    private final HelpPresenter helpPresenter;
    private final BedToggleCommand bedToggleCommand;

    public MarpCommand(
            CommandSupport support,
            String pluginVersion,
            SqlStorage storage,
            HomeCache homeCache,
            WarpCache warpCache,
            WarmupSampler warmupSampler,
            Runnable reloadAction,
            Scheduler scheduler) {
        this(
                support,
                pluginVersion,
                storage,
                homeCache,
                warpCache,
                warmupSampler,
                reloadAction,
                scheduler,
                new HelpPresenter(support::messages),
                null);
    }

    public MarpCommand(
            CommandSupport support,
            String pluginVersion,
            SqlStorage storage,
            HomeCache homeCache,
            WarpCache warpCache,
            WarmupSampler warmupSampler,
            Runnable reloadAction,
            Scheduler scheduler,
            HelpPresenter helpPresenter,
            BedToggleCommand bedToggleCommand) {
        this.support = support;
        this.pluginVersion = pluginVersion;
        this.storage = storage;
        this.homeCache = homeCache;
        this.warpCache = warpCache;
        this.warmupSampler = warmupSampler;
        this.reloadAction = reloadAction;
        this.scheduler = scheduler;
        this.helpPresenter = helpPresenter;
        this.bedToggleCommand = bedToggleCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            handleHelp(sender, 1);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> {
                int page = 1;
                if (args.length > 1) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                        page = 1;
                    }
                }
                handleHelp(sender, page);
            }
            case "bed" -> {
                if (bedToggleCommand != null) {
                    String[] subArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, subArgs, 0, subArgs.length);
                    bedToggleCommand.execute(sender, subArgs, "/marp bed [on|off]");
                } else {
                    support.messages().send(
                            sender,
                            Messages.INVALID_USAGE,
                            Map.of("usage", "/marp [help|cancel|bed|reload|info]"));
                }
            }
            case "reload" -> handleReload(sender);
            case "cancel" -> handleCancel(sender);
            case "info" -> handleInfo(sender);
            default -> support.messages().send(
                    sender,
                    Messages.INVALID_USAGE,
                    Map.of("usage", "/marp [help|cancel|bed|reload|info]"));
        }
        return true;
    }

    private void handleHelp(CommandSender sender, int pageNumber) {
        if (!support.requirePermission(sender, "marp.help")) {
            return;
        }
        List<HelpEntry> visible = HelpCatalog.visibleEntries(sender::hasPermission, support.config());
        int pageSize = support.config().helpEntriesPerPage();
        Page page = Page.of(Math.max(0, pageNumber - 1), visible.size(), pageSize);
        List<HelpEntry> slice = page.slice(visible);
        boolean canViewAdminPerms = sender.hasPermission("marp.admin.info");
        sender.sendMessage(helpPresenter.renderPage(visible, slice, page, canViewAdminPerms));
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
        if (args.length == 1) {
            return CompletionSupport.completeMarpSubcommands(args[0], sender::hasPermission);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (("help".equals(sub) || "?".equals(sub)) && sender.hasPermission("marp.help")) {
                List<HelpEntry> visible = HelpCatalog.visibleEntries(sender::hasPermission, support.config());
                int pageSize = support.config().helpEntriesPerPage();
                int totalPages = Page.of(0, visible.size(), pageSize).pages();
                List<String> pages = new java.util.ArrayList<>(totalPages);
                for (int i = 1; i <= totalPages; i++) {
                    pages.add(Integer.toString(i));
                }
                return CompletionSupport.completeNames(pages, args[1], 10);
            }
            if ("bed".equals(sub) && sender.hasPermission("marp.bedhome.toggle")) {
                return CompletionSupport.completeNames(List.of("on", "off"), args[1], 2);
            }
        }
        return List.of();
    }
}
