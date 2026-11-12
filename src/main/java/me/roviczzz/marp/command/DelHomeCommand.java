package me.roviczzz.marp.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /delhome [name]} command handler (DESIGN.md §3.3). */
public final class DelHomeCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final HomeService homeService;
    private final HomeCache homeCache;
    private final Scheduler scheduler;

    public DelHomeCommand(
            CommandSupport support,
            HomeService homeService,
            HomeCache homeCache,
            Scheduler scheduler) {
        this.support = support;
        this.homeService = homeService;
        this.homeCache = homeCache;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requirePermission(player, "marp.delhome")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().homesEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        if (args.length > 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/delhome [name]"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String nameArg = args.length == 0 ? null : args[0];
        String expectedKey = nameArg == null
                ? support.config().defaultHomeName()
                : nameArg.toLowerCase(Locale.ROOT);

        homeService.deleteByName(uuid, nameArg)
                .whenComplete((deletedOpt, error) -> scheduler.runOnMain(() -> {
                    Player online = support.onlinePlayer(uuid);
                    if (online == null) {
                        return;
                    }
                    if (error != null || deletedOpt == null) {
                        support.messages().send(online, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (deletedOpt.isPresent()) {
                        support.messages().send(
                                online,
                                Messages.HOME_DELETED,
                                Map.of("name", deletedOpt.get().nameKey()));
                    } else {
                        support.messages().send(
                                online, Messages.HOME_NOT_FOUND, Map.of("name", expectedKey));
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)
                || args.length != 1
                || !player.hasPermission("marp.delhome")) {
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
