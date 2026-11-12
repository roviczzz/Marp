package me.roviczzz.marp.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.favorite.FavoriteLimitResolver;
import me.roviczzz.marp.favorite.FavoriteService;
import me.roviczzz.marp.gui.FavoritesMenu.Filter;
import me.roviczzz.marp.gui.FavoritesMenu.Item;
import me.roviczzz.marp.home.HomeLimitResolver;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.input.TextInputService;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.settings.PlayerSettingsService;
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
    private final PlayerSettingsService settingsService;
    private final FavoriteService favoriteService;

    public MenuManager(
            HomeService homeService,
            WarpService warpService,
            TeleportService teleportService,
            TextInputService textInput,
            GuiItemFactory itemFactory,
            Scheduler scheduler,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages) {
        this(homeService, warpService, teleportService, textInput, itemFactory, scheduler, config, messages, null, null);
    }

    public MenuManager(
            HomeService homeService,
            WarpService warpService,
            TeleportService teleportService,
            TextInputService textInput,
            GuiItemFactory itemFactory,
            Scheduler scheduler,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages,
            PlayerSettingsService settingsService) {
        this(homeService, warpService, teleportService, textInput, itemFactory, scheduler, config, messages, settingsService, null);
    }

    public MenuManager(
            HomeService homeService,
            WarpService warpService,
            TeleportService teleportService,
            TextInputService textInput,
            GuiItemFactory itemFactory,
            Scheduler scheduler,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages,
            PlayerSettingsService settingsService,
            FavoriteService favoriteService) {
        this.homeService = homeService;
        this.warpService = warpService;
        this.teleportService = teleportService;
        this.textInput = textInput;
        this.itemFactory = itemFactory;
        this.scheduler = scheduler;
        this.config = config;
        this.messages = messages;
        this.settingsService = settingsService;
        this.favoriteService = favoriteService;
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

    public PlayerSettingsService settingsService() {
        return settingsService;
    }

    public FavoriteService favoriteService() {
        return favoriteService;
    }

    public boolean isBedHomeDisabled(UUID playerUuid) {
        return settingsService != null && settingsService.isBedHomeDisabled(playerUuid);
    }

    public void openHomeList(Player player, int page) {
        openHomeList(player, false, page);
    }

    public void openHomeList(Player player, boolean starMode, int page) {
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
            List<Home> sorted = favoriteService != null
                    ? favoriteService.sortHomesWithFavorites(uuid, homes)
                    : homes;
            openHomeListWithSnapshot(online, sorted, starMode, page);
        }));
    }

    public void openHomeListWithSnapshot(Player player, List<Home> homes, int page) {
        openHomeListWithSnapshot(player, homes, false, page);
    }

    public void openHomeListWithSnapshot(Player player, List<Home> homes, boolean starMode, int page) {
        int limit = resolveHomeLimit(player);
        new HomeListMenu(this, player.getUniqueId(), homes, starMode, page, limit).open(player);
    }

    public void openWarpList(Player player, int page) {
        openWarpList(player, false, page);
    }

    public void openWarpList(Player player, boolean starMode, int page) {
        scheduler.runOnMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            boolean isAdmin = player.hasPermission("marp.admin.setwarp");
            List<Warp> visible = warpService.listVisible(player::hasPermission, isAdmin);
            List<Warp> sorted = favoriteService != null
                    ? favoriteService.sortWarpsWithFavorites(player.getUniqueId(), visible)
                    : visible;
            new WarpListMenu(this, player.getUniqueId(), sorted, starMode, page, isAdmin).open(player);
        });
    }

    public void openFavorites(Player player, Filter filter, boolean starMode, int page) {
        UUID uuid = player.getUniqueId();
        if (favoriteService == null) {
            return;
        }
        favoriteService.list(uuid).thenCompose(favs -> homeService.list(uuid).thenApply(homes -> {
            boolean isAdmin = player.hasPermission("marp.admin.setwarp");
            List<Warp> visibleWarps = warpService.listVisible(player::hasPermission, isAdmin);
            return buildFavoriteDisplayItems(favs, homes, visibleWarps, filter, isAdmin);
        })).whenComplete((items, error) -> scheduler.runOnMain(() -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            if (error != null || items == null) {
                messages().send(online, Messages.STORAGE_ERROR);
                return;
            }
            int limit = resolveFavoriteLimit(online);
            new FavoritesMenu(this, uuid, items, filter, starMode, page, limit).open(online);
        }));
    }

    private List<Item> buildFavoriteDisplayItems(
            List<me.roviczzz.marp.model.FavoriteEntry> favs,
            List<Home> homes,
            List<Warp> visibleWarps,
            Filter filter,
            boolean isAdmin) {
        Map<Long, Home> homeMap = homes.stream()
                .collect(java.util.stream.Collectors.toMap(Home::id, h -> h, (a, b) -> a));
        Map<Long, Warp> warpMap = visibleWarps.stream()
                .collect(java.util.stream.Collectors.toMap(Warp::id, w -> w, (a, b) -> a));

        List<Item> items = new ArrayList<>();
        for (me.roviczzz.marp.model.FavoriteEntry fav : favs) {
            if (fav.kind() == me.roviczzz.marp.model.FavoriteKind.HOME) {
                if (filter == Filter.ALL || filter == Filter.HOMES) {
                    Home home = homeMap.get(fav.targetId());
                    if (home != null) {
                        items.add(new Item.HomeItem(home));
                    }
                }
            } else if (fav.kind() == me.roviczzz.marp.model.FavoriteKind.WARP) {
                if (filter == Filter.ALL || filter == Filter.WARPS) {
                    Warp warp = warpMap.get(fav.targetId());
                    if (warp != null) {
                        boolean locked = !warpService.canAccess(warp, p -> isAdmin);
                        items.add(new Item.WarpItem(warp, locked));
                    }
                }
            }
        }
        return items;
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

    public int resolveFavoriteLimit(Player player) {
        MarpConfig cfg = config();
        FavoriteLimitResolver resolver = new FavoriteLimitResolver(
                cfg.favoritesDefaultLimit(), cfg.favoritesMaxLimitNodeScan(), cfg.favoritesAbsoluteMax());
        return resolver.resolve(
                n -> player.hasPermission("marp.favoritelimit." + n),
                player.hasPermission("marp.favoritelimit.unlimited"));
    }

    public void playClickSound(Player player) {
        if (config().guiSounds()) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        }
    }
}
