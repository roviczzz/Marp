package me.roviczzz.marp.gui;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.home.HomeLimitResolver;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.input.TextInputService;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpService;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Coordinates opening, refreshing, and closing Marp menus on the main thread.
 */
public final class MenuManager {

    private final HomeService homeService;
    private final WarpService warpService;
    private final TeleportService teleportService;
    private final TextInputService textInput;
    private final GuiItemFactory itemFactory;
    private final Scheduler scheduler;
    private final Supplier<MarpConfig> config;
    private final Supplier<MessageService> messages;

    public MenuManager(
            HomeService homeService,
            WarpService warpService,
            TeleportService teleportService,
            TextInputService textInput,
            GuiItemFactory itemFactory,
            Scheduler scheduler,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages) {
        this.homeService = homeService;
        this.warpService = warpService;
        this.teleportService = teleportService;
        this.textInput = textInput;
        this.itemFactory = itemFactory;
        this.scheduler = scheduler;
        this.config = config;
        this.messages = messages;
    }

    public HomeService homeService() {
        return homeService;
    }

    public WarpService warpService() {
        return warpService;
    }

    public TeleportService teleportService() {
        return teleportService;
    }

    public TextInputService textInput() {
        return textInput;
    }

    public GuiItemFactory itemFactory() {
        return itemFactory;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public MarpConfig config() {
        return config.get();
    }

    public MessageService messages() {
        return messages.get();
    }

    public void openHomeList(Player player, int page) {
        UUID uuid = player.getUniqueId();
        homeService.list(uuid).whenComplete((homes, error) -> scheduler.runOnMain(() -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            if (error != null || homes == null) {
                messages().send(online, Messages.STORAGE_ERROR);
                return;
            }
            openHomeListWithSnapshot(online, homes, page);
        }));
    }

    public void openHomeListWithSnapshot(Player player, List<Home> homes, int page) {
        int limit = resolveHomeLimit(player);
        new HomeListMenu(this, player.getUniqueId(), homes, page, limit).open(player);
    }

    public void openWarpList(Player player, int page) {
        scheduler.runOnMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            boolean isAdmin = player.hasPermission("marp.admin.setwarp");
            List<Warp> visible = warpService.listVisible(player::hasPermission, isAdmin);
            new WarpListMenu(this, player.getUniqueId(), visible, page, isAdmin).open(player);
        });
    }

    public void openHomeEdit(Player player, Home home, int returnPage) {
        scheduler.runOnMain(() -> {
            if (player.isOnline()) {
                EntryEditMenu.forHome(this, player.getUniqueId(), home, returnPage).open(player);
            }
        });
    }

    public void openWarpEdit(Player player, Warp warp, int returnPage) {
        scheduler.runOnMain(() -> {
            if (player.isOnline()) {
                EntryEditMenu.forWarp(this, player.getUniqueId(), warp, returnPage).open(player);
            }
        });
    }

    public void openHomeDeleteConfirm(Player player, Home home, int returnPage) {
        scheduler.runOnMain(() -> {
            if (player.isOnline()) {
                DeleteConfirmMenu.forHome(this, player.getUniqueId(), home, returnPage).open(player);
            }
        });
    }

    public void openWarpDeleteConfirm(Player player, Warp warp, int returnPage) {
        scheduler.runOnMain(() -> {
            if (player.isOnline()) {
                DeleteConfirmMenu.forWarp(this, player.getUniqueId(), warp, returnPage).open(player);
            }
        });
    }

    public void closeMarpMenuIfOpen(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null
                && player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder() instanceof MarpMenuHolder) {
            player.closeInventory();
        }
    }

    public void closeAllMarpMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MarpMenuHolder) {
                player.closeInventory();
            }
        }
    }

    public int resolveHomeLimit(Player player) {
        MarpConfig cfg = config();
        HomeLimitResolver resolver = new HomeLimitResolver(cfg.defaultHomeLimit(), cfg.maxLimitNodeScan());
        return resolver.resolve(
                n -> player.hasPermission("marp.homelimit." + n),
                player.hasPermission("marp.homelimit.unlimited"));
    }

    public void playClickSound(Player player) {
        if (config().guiSounds()) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        }
    }
}
