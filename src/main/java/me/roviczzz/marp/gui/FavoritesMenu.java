package me.roviczzz.marp.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.favorite.FavoriteService.ToggleOutcome;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.model.FavoriteEntry;
import me.roviczzz.marp.model.FavoriteKind;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.Page;
import me.roviczzz.marp.model.Pagination;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.teleport.TeleportReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Paginated chest menu displaying starred homes and warps (DESIGN.md addendum).
 */
public final class FavoritesMenu implements Menu {

    public enum Filter {
        ALL("All"),
        HOMES("Homes"),
        WARPS("Warps");

        private final String display;

        Filter(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public Filter next() {
            return switch (this) {
                case ALL -> HOMES;
                case HOMES -> WARPS;
                case WARPS -> ALL;
            };
        }
    }

    public sealed interface Item {
        record HomeItem(Home home) implements Item {}

        record WarpItem(Warp warp, boolean locked) implements Item {}
    }

    private final MenuManager manager;
    private final UUID owner;
    private final List<Item> allItems;
    private final Filter filter;
    private final boolean starMode;
    private final int limit;
    private final int rows;
    private final int navBase;
    private final Page page;
    private final List<Item> pageSlice;
    private final MarpMenuHolder holder;
    private final Inventory inventory;

    public FavoritesMenu(
            MenuManager manager,
            UUID owner,
            List<Item> allItems,
            Filter filter,
            boolean starMode,
            int requestedPage,
            int limit) {
        this.manager = manager;
        this.owner = owner;
        this.filter = filter == null ? Filter.ALL : filter;
        this.allItems = List.copyOf(allItems);
        this.starMode = starMode;
        this.limit = limit;
        this.rows = manager.config().guiRows();
        this.navBase = (rows - 1) * 9;
        this.page = Pagination.page(requestedPage, this.allItems.size(), rows);
        this.pageSlice = this.page.slice(this.allItems);
        this.holder = new MarpMenuHolder(
                MenuType.FAVORITES, TargetKind.MIXED, owner, this.page.index(), 0L, 0L);
        this.inventory = Bukkit.createInventory(
                holder,
                rows * 9,
                manager.messages().component(
                        starMode ? Messages.GUI_TITLE_STAR_MODE : Messages.GUI_TITLE_FAVORITES));
        this.holder.bind(inventory, this);
    }

    public void open(Player player) {
        render(player);
        player.openInventory(inventory);
    }

    private void render(Player player) {
        GuiItemFactory factory = manager.itemFactory();
        for (int i = 0; i < pageSlice.size(); i++) {
            Item item = pageSlice.get(i);
            if (item instanceof Item.HomeItem hi) {
                inventory.setItem(i, factory.homeEntry(hi.home(), true));
            } else if (item instanceof Item.WarpItem wi) {
                inventory.setItem(i, factory.warpEntry(wi.warp(), wi.locked(), true));
            }
        }
        if (allItems.isEmpty()) {
            int emptySlot = Math.min(navBase - 5, 22);
            inventory.setItem(emptySlot, factory.emptyFavoritesItem());
        }

        inventory.setItem(navBase, factory.previousButton(page));
        inventory.setItem(navBase + 1, factory.homesNavButton());
        inventory.setItem(navBase + 2, factory.warpsNavButton());
        inventory.setItem(navBase + 3, factory.filterButton(filter.display()));
        inventory.setItem(navBase + 4, factory.homePageInfo(page, allItems.size(), limit));
        inventory.setItem(navBase + 5, factory.closeButton());
        inventory.setItem(navBase + 6, factory.starModeButton(starMode));
        inventory.setItem(navBase + 7, factory.filler());
        inventory.setItem(navBase + 8, factory.nextButton(page));
    }

    @Override
    public MarpMenuHolder holder() {
        return holder;
    }

    @Override
    public Inventory inventory() {
        return inventory;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot >= navBase) {
            if (click.isShiftClick()) {
                return;
            }
            handleNavigation(player, slot - navBase);
            return;
        }
        if (slot < 0 || slot >= pageSlice.size()) {
            return;
        }
        Item item = pageSlice.get(slot);
        if (starMode || click.isShiftClick() || click.isRightClick()) {
            handleUnstar(player, item);
        } else if (click.isLeftClick()) {
            handleTeleport(player, item);
        }
    }

    private void handleNavigation(Player player, int navOffset) {
        switch (navOffset) {
            case 0 -> {
                if (page.hasPrevious()) {
                    manager.playClickSound(player);
                    manager.openFavorites(player, filter, starMode, page.index() - 1);
                }
            }
            case 1 -> {
                manager.playClickSound(player);
                manager.openHomeList(player, 0);
            }
            case 2 -> {
                manager.playClickSound(player);
                manager.openWarpList(player, 0);
            }
            case 3 -> {
                manager.playClickSound(player);
                manager.openFavorites(player, filter.next(), starMode, 0);
            }
            case 5 -> {
                manager.playClickSound(player);
                player.closeInventory();
            }
            case 6 -> {
                manager.playClickSound(player);
                manager.openFavorites(player, filter, !starMode, page.index());
            }
            case 8 -> {
                if (page.hasNext()) {
                    manager.playClickSound(player);
                    manager.openFavorites(player, filter, starMode, page.index() + 1);
                }
            }
            default -> {
            }
        }
    }

    private void handleUnstar(Player player, Item item) {
        FavoriteKind kind = item instanceof Item.HomeItem ? FavoriteKind.HOME : FavoriteKind.WARP;
        long targetId = item instanceof Item.HomeItem hi ? hi.home().id() : ((Item.WarpItem) item).warp().id();
        String name = item instanceof Item.HomeItem hi ? hi.home().nameKey() : ((Item.WarpItem) item).warp().nameKey();
        int returnPage = page.index();
        manager.playClickSound(player);
        manager.favoriteService().toggle(owner, kind, targetId, limit)
                .whenComplete((outcome, error) -> manager.scheduler().runOnMain(() -> {
                    Player online = Bukkit.getPlayer(owner);
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    if (error != null || outcome == null) {
                        manager.messages().send(online, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (outcome instanceof ToggleOutcome.Removed) {
                        manager.messages().send(online, Messages.FAVORITES_REMOVED, Map.of("name", name));
                    }
                    manager.openFavorites(online, filter, starMode, returnPage);
                }));
    }

    private void handleTeleport(Player player, Item item) {
        if (item instanceof Item.HomeItem hi) {
            Home home = hi.home();
            if (!player.hasPermission("marp.home")) {
                manager.messages().send(player, Messages.NO_PERMISSION);
                return;
            }
            if (Bukkit.getWorld(home.location().world()) == null) {
                manager.messages().send(player, Messages.TELEPORT_FAILED);
                return;
            }
            manager.playClickSound(player);
            player.closeInventory();
            manager.teleportService().requestTeleport(
                    owner, TeleportReason.HOME, home.nameKey(), home.location(), null, home.isBed());
        } else if (item instanceof Item.WarpItem wi) {
            Warp warp = wi.warp();
            if (!player.hasPermission("marp.warp")) {
                manager.messages().send(player, Messages.NO_PERMISSION);
                return;
            }
            if (!manager.warpService().canAccess(warp, player::hasPermission)) {
                manager.messages().send(player, Messages.WARP_PERMISSION_DENIED);
                return;
            }
            if (Bukkit.getWorld(warp.location().world()) == null) {
                manager.messages().send(player, Messages.TELEPORT_FAILED);
                return;
            }
            manager.playClickSound(player);
            player.closeInventory();
            manager.teleportService().requestTeleport(
                    owner, TeleportReason.WARP, warp.nameKey(), warp.location(), null);
        }
    }
}
