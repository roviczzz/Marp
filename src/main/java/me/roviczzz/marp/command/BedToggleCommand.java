package me.roviczzz.marp.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.PlayerSettings;
import me.roviczzz.marp.settings.PlayerSettingsService;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /bedtoggle [on|off]} command handler. */
public final class BedToggleCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final PlayerSettingsService settings;
    private final Scheduler scheduler;

    public BedToggleCommand(
            CommandSupport support,
            PlayerSettingsService settings,
            Scheduler scheduler) {
        this.support = support;
        this.settings = settings;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        execute(sender, args, "/bedtoggle [on|off]");
        return true;
    }

    public void execute(CommandSender sender, String[] args, String usage) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!support.requirePermission(player, "marp.bedhome.toggle")) {
            return;
        }
        if (!support.requireFeatureEnabled(player, support.config().bedHomeEnabled())) {
            return;
        }
        if (!support.requireStorageReady(player)) {
            return;
        }
        if (args.length > 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", usage));
            return;
        }

        UUID uuid = player.getUniqueId();
        var future = switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "" -> settings.toggleBedHome(uuid);
            case "on", "enable" -> settings.setBedHomeDisabled(uuid, false);
            case "off", "disable" -> settings.setBedHomeDisabled(uuid, true);
            default -> null;
        };
        if (future == null) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", usage));
            return;
        }

        future.whenComplete((PlayerSettings updated, Throwable error) -> scheduler.runOnMain(() -> {
            Player online = support.onlinePlayer(uuid);
            if (online == null) {
                return;
            }
            if (error != null || updated == null) {
                support.messages().send(online, Messages.STORAGE_ERROR);
                return;
            }
            support.messages().send(
                    online,
                    updated.bedHomeDisabled() ? Messages.BED_TOGGLE_OFF : Messages.BED_TOGGLE_ON);
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)
                || args.length != 1
                || !support.config().bedHomeEnabled()
                || !player.hasPermission("marp.bedhome.toggle")) {
            return List.of();
        }
        return CompletionSupport.completeNames(List.of("on", "off"), args[0], 2);
    }
}
