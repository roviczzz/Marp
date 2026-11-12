package me.roviczzz.marp.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.model.Page;
import me.roviczzz.marp.model.Pagination;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.teleport.TeleportReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Paginated server warps menu (DESIGN.md §8.3).
 */
public final class WarpListMenu implements Menu {

    private final MenuManager manager;
    private final List<Warp> visibleWarps;
    private final Page page;
    private final List<Warp> pageSlice;
    private final boolean isAdmin;
    private final int rows;
    private final int navBase;
    private final MarpMenuHolder holder;
    private final Inventory inventory;

    public WarpListMenu(
            MenuManager manager,
            UUID owner,
            List<Warp> visibleWarps,
            int requestedPage,
            boolean isAdmin) {
        this.manager = manager;
        this.visibleWarps = List.copyOf(visibleWarps);
        this.rows = manager.config().guiRows();
        this.page = Pagination.page(requestedPage, this.visibleWarps.size(), rows);
        this.pageSlice = this.page.slice(this.visibleWarps);
        this.isAdmin = isAdmin;
        this.navBase = (rows - 1) * 9;
        this.holder = new MarpMenuHolder(
                MenuType.WARP_LIST, TargetKind.WARP, owner, this.page.index(), 0L, 0L);
        this.inventory = Bukkit.createInventory(
                holder,
                rows * 9,
                manager.messages().component(Messages.GUI_TITLE_WARPS));
        this.holder.bind(inventory, this);
    }

    public void open(Player player) {
        render(player);
        player.openInventory(inventory);
    }

    private void render(Player player) {
        GuiItemFactory factory = manager.itemFactory();
        for (int i = 0; i < pageSlice.size(); i++) {
            Warp warp = pageSlice.get(i);
            boolean locked = !manager.warpService().canAccess(warp, player::hasPermission);
            inventory.setItem(i, factory.warpEntry(warp, locked));
        }
        if (visibleWarps.isEmpty()) {
            int emptySlot = Math.min(navBase - 5, 22);
            inventory.setItem(emptySlot, factory.emptyWarpsItem());
        }

        inventory.setItem(navBase, factory.previousButton(page));
        inventory.setItem(navBase + 1, factory.filler());
        inventory.setItem(navBase + 2, factory.filler());
        inventory.setItem(navBase + 3, factory.warpHelpButton());
        inventory.setItem(navBase + 4, factory.warpPageInfo(page));
        inventory.setItem(navBase + 5, factory.closeButton());
        inventory.setItem(navBase + 6, factory.filler());
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
        Warp clicked = pageSlice.get(slot);
        revalidateAndAct(player, clicked, click);
    }

    private void handleNavigation(Player player, int navOffset) {
        switch (navOffset) {
            case 0 -> {
                if (page.hasPrevious()) {
                    manager.playClickSound(player);
                    manager.openWarpList(player, page.index() - 1);
                }
            }
            case 5 -> {
                manager.playClickSound(player);
                player.closeInventory();
            }
            case 8 -> {
                if (page.hasNext()) {
                    manager.playClickSound(player);
                    manager.openWarpList(player, page.index() + 1);
                }
            }
            default -> {
            }
        }
    }

    private void revalidateAndAct(Player player, Warp clicked, ClickType click) {
        UUID uuid = player.getUniqueId();
        int currentPage = page.index();
        manager.warpService().findById(clicked.id())
                .whenComplete((freshOpt, error) -> manager.scheduler().runOnMain(() -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    if (error != null || freshOpt == null) {
                        manager.messages().send(online, Messages.STORAGE_ERROR);
                        return;
                    }
                    if (freshOpt.isEmpty()) {
                        manager.messages().send(
                                online, Messages.WARP_NOT_FOUND, Map.of("name", clicked.nameKey()));
                        manager.openWarpList(online, currentPage);
                        return;
                    }
                    Warp fresh = freshOpt.get();
                    if (fresh.rev() != clicked.rev() || !fresh.nameKey().equals(clicked.nameKey())) {
                        manager.messages().send(online, Messages.CONFLICT);
                        manager.openWarpList(online, currentPage);
                        return;
                    }

                    // Shift checks run before left/right because SHIFT_LEFT.isLeftClick() is true.
                    if (click.isShiftClick()) {
                        if (!online.hasPermission("marp.admin.delwarp")) {
                            manager.messages().send(online, Messages.NO_PERMISSION);
                            return;
                        }
                        manager.playClickSound(online);
                        manager.openWarpDeleteConfirm(online, fresh, currentPage);
                    } else if (click.isLeftClick()) {
                        if (!online.hasPermission("marp.warp")) {
                            manager.messages().send(online, Messages.NO_PERMISSION);
                            return;
                        }
                        if (!manager.warpService().canAccess(fresh, online::hasPermission)) {
                            manager.messages().send(online, Messages.WARP_PERMISSION_DENIED);
                            return;
                        }
                        if (Bukkit.getWorld(fresh.location().world()) == null) {
                            manager.messages().send(online, Messages.TELEPORT_FAILED);
                            return;
                        }
                        manager.playClickSound(online);
                        online.closeInventory();
                        manager.teleportService().requestTeleport(
                                uuid, TeleportReason.WARP, fresh.nameKey(), fresh.location(), null);
                    } else if (click.isRightClick()) {
                        if (!online.hasPermission("marp.admin.setwarp")
                                && !online.hasPermission("marp.admin.editwarp")) {
                            manager.messages().send(online, Messages.NO_PERMISSION);
                            return;
                        }
                        manager.playClickSound(online);
                        manager.openWarpEdit(online, fresh, currentPage);
                    }
                }));
    }
}
