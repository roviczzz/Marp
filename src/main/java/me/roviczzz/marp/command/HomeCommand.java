package me.roviczzz.marp.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MenuManager;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.home.HomeService.ResolveOutcome;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /home [name]} command handler (DESIGN.md §3.3). */
public final class HomeCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final HomeService homeService;
    private final HomeCache homeCache;
    private final TeleportService teleportService;
    private final MenuManager menuManager;
    private final Scheduler scheduler;

    public HomeCommand(
            CommandSupport support,
            HomeService homeService,
            HomeCache homeCache,
            TeleportService teleportService,
            MenuManager menuManager,
            Scheduler scheduler) {
        this.support = support;
        this.homeService = homeService;
        this.homeCache = homeCache;
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
        if (!support.requirePermission(player, "marp.home")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().homesEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        if (args.length > 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/home [name]"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String nameArg = args.length == 0 ? null : args[0];

        homeService.resolveForTeleport(uuid, nameArg)
                .whenComplete((outcome, error) -> scheduler.runOnMain(() -> {
                    Player online = support.onlinePlayer(uuid);
                    if (online == null) {
                        return;
                    }
                    if (error != null || outcome == null) {
                        support.messages().send(online, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (outcome instanceof ResolveOutcome.Found found) {
                        Home home = found.home();
                        teleportService.requestTeleport(
                                uuid, TeleportReason.HOME, home.nameKey(), home.location(), null, home.isBed());
                    } else if (outcome instanceof ResolveOutcome.NotFound notFound) {
                        support.messages().send(
                                online, Messages.HOME_NOT_FOUND, Map.of("name", notFound.nameKey()));
                    } else if (outcome instanceof ResolveOutcome.DefaultMissingOpenMenu openMenu) {
                        support.messages().send(online, Messages.HOME_DEFAULT_MISSING);
                        menuManager.openHomeListWithSnapshot(online, openMenu.homes(), 0);
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)
                || args.length != 1
                || !player.hasPermission("marp.home")) {
            return List.of();
        }
        UUID uuid = player.getUniqueId();
        if (!homeCache.isLoaded(uuid) && support.isStorageReady()) {
            homeService.loadCache(uuid);
        }
        return CompletionSupport.completeHomes(
                uuid, args[0], homeCache, support.config().homesEnabled());
    }
}
