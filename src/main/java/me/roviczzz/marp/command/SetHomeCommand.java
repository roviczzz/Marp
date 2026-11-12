package me.roviczzz.marp.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MenuManager;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.home.HomeService.SetOutcome;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.util.LocationCodec;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /sethome [name]} command handler (DESIGN.md §3.3). */
public final class SetHomeCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final HomeService homeService;
    private final HomeCache homeCache;
    private final MenuManager menuManager;
    private final Scheduler scheduler;

    public SetHomeCommand(
            CommandSupport support,
            HomeService homeService,
            HomeCache homeCache,
            MenuManager menuManager,
            Scheduler scheduler) {
        this.support = support;
        this.homeService = homeService;
        this.homeCache = homeCache;
        this.menuManager = menuManager;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requirePermission(player, "marp.sethome")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().homesEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        if (args.length > 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/sethome [name]"));
            return true;
        }
        if (player.getWorld() == null) {
            return true;
        }

        UUID uuid = player.getUniqueId();
        String nameArg = args.length == 0 ? null : args[0];
        LocationSnapshot location = LocationCodec.fromLocation(player.getLocation());
        int limit = menuManager.resolveHomeLimit(player);

        homeService.setHome(uuid, nameArg, location, limit)
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
                                online, Messages.HOME_SET, Map.of("name", created.home().nameKey()));
                    } else if (outcome instanceof SetOutcome.Updated updated) {
                        support.messages().send(
                                online, Messages.HOME_UPDATED, Map.of("name", updated.home().nameKey()));
                    } else if (outcome instanceof SetOutcome.LimitZero) {
                        support.messages().send(online, Messages.HOME_LIMIT_ZERO);
                    } else if (outcome instanceof SetOutcome.LimitReached reached) {
                        support.messages().send(
                                online,
                                Messages.HOME_LIMIT_REACHED,
                                Map.of("limit", Integer.toString(reached.limit())));
                    } else if (outcome instanceof SetOutcome.InvalidName invalid) {
                        switch (invalid.reason()) {
                            case TOO_LONG -> support.messages().send(
                                    online,
                                    Messages.HOME_NAME_TOO_LONG,
                                    Map.of("max", Integer.toString(invalid.maxLength())));
                            case RESERVED -> support.messages().send(
                                    online,
                                    Messages.HOME_NAME_RESERVED,
                                    Map.of("name", invalid.attempted()));
                            default -> support.messages().send(
                                    online,
                                    Messages.HOME_NAME_INVALID,
                                    Map.of("pattern", invalid.pattern()));
                        }
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)
                || args.length != 1
                || !player.hasPermission("marp.sethome")) {
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
