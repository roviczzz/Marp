package me.roviczzz.marp.command;

import java.util.List;
import me.roviczzz.marp.gui.FavoritesMenu;
import me.roviczzz.marp.gui.MenuManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /favorites} command handler. */
public final class FavoritesCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final MenuManager menuManager;

    public FavoritesCommand(CommandSupport support, MenuManager menuManager) {
        this.support = support;
        this.menuManager = menuManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!support.requirePermission(player, "marp.favorites")) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().favoritesEnabled())) {
            return true;
        }
        if (!support.requireStorageReady(player)) {
            return true;
        }
        menuManager.openFavorites(player, FavoritesMenu.Filter.ALL, false, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
