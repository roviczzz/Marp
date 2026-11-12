package me.roviczzz.marp.command;

import java.util.List;
import me.roviczzz.marp.gui.MenuManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /homes} command handler (DESIGN.md §3.3). */
public final class HomesCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final MenuManager menuManager;

    public HomesCommand(CommandSupport support, MenuManager menuManager) {
        this.support = support;
        this.menuManager = menuManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requirePermission(player, "marp.homes")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().homesEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        menuManager.openHomeList(player, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
