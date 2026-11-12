package me.roviczzz.marp.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.Pagination;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Timed 27-slot deletion confirmation menu (DESIGN.md §8.5).
 *
 * <p>Expires after {@code gui.confirmation-seconds}; closing the menu or letting
 * it expire cancels the deletion and leaves the record untouched.
 */
public final class DeleteConfirmMenu implements Menu {

    public static final int SLOT_CANCEL = 11;
    public static final int SLOT_PREVIEW = 13;
    public static final int SLOT_CONFIRM = 15;

    private final MenuManager manager;
    private final TargetKind targetKind;
    private final Home home;
    private final Warp warp;
    private final int returnPage;
    private final MarpMenuHolder holder;
    private final Inventory inventory;
    private Scheduler.Cancellable expiryTask;
    private boolean resolved;

    private DeleteConfirmMenu(
            MenuManager manager,
            UUID owner,
            TargetKind targetKind,
            Home home,
            Warp warp,
            int returnPage) {
        this.manager = manager;
        this.targetKind = targetKind;
        this.home = home;
        this.warp = warp;
        this.returnPage = Math.max(0, returnPage);
        long id = targetKind == TargetKind.HOME ? home.id() : warp.id();
        long rev = targetKind == TargetKind.HOME ? home.rev() : warp.rev();
        String key = targetKind == TargetKind.HOME ? home.nameKey() : warp.nameKey();
        this.holder = new MarpMenuHolder(
                MenuType.DELETE_CONFIRM, targetKind, owner, this.returnPage, id, rev);
        this.inventory = Bukkit.createInventory(
                holder,
                27,
                manager.messages().component(Messages.GUI_CONFIRM_TITLE, Map.of("name", key)));
        this.holder.bind(inventory, this);
    }

    public static DeleteConfirmMenu forHome(MenuManager manager, UUID owner, Home home, int returnPage) {
        return new DeleteConfirmMenu(manager, owner, TargetKind.HOME, home, null, returnPage);
    }

    public static DeleteConfirmMenu forWarp(MenuManager manager, UUID owner, Warp warp, int returnPage) {
        return new DeleteConfirmMenu(manager, owner, TargetKind.WARP, null, warp, returnPage);
    }

    public void open(Player player) {
        render();
        player.openInventory(inventory);
        UUID uuid = player.getUniqueId();
        long timeoutTicks = Math.max(1L, manager.config().confirmationSeconds()) * 20L;
        this.expiryTask = manager.scheduler().runLater(() -> {
            if (resolved) {
                return;
            }
            resolved = true;
            Player online = Bukkit.getPlayer(uuid);
            if (online != null
                    && online.isOnline()
                    && online.getOpenInventory().getTopInventory() == inventory) {
                online.closeInventory();
            }
        }, timeoutTicks);
    }

    private void render() {
        GuiItemFactory factory = manager.itemFactory();
        ItemStack filler = factory.filler();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(
                SLOT_CANCEL,
                factory.simpleButton(
                        Material.RED_CONCRETE,
                        manager.messages().component(Messages.GUI_CONFIRM_NO),
                        List.of()));

        ItemStack preview = targetKind == TargetKind.HOME
                ? factory.homeEntry(home)
                : factory.warpEntry(warp, false);
        inventory.setItem(SLOT_PREVIEW, preview);

        inventory.setItem(
                SLOT_CONFIRM,
                factory.simpleButton(
                        Material.LIME_CONCRETE,
                        manager.messages().component(Messages.GUI_CONFIRM_YES),
                        List.of(manager.messages().component(Messages.GUI_CONFIRM_LORE))));
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
        if (resolved || click.isShiftClick()) {
            return;
        }
        if (slot == SLOT_CANCEL) {
            resolved = true;
            cancelExpiry();
            manager.playClickSound(player);
            reopenList(player, returnPage);
        } else if (slot == SLOT_CONFIRM) {
            resolved = true;
            cancelExpiry();
            confirmDelete(player);
        }
    }

    @Override
    public void onClose(Player player) {
        resolved = true;
        cancelExpiry();
    }

    private void confirmDelete(Player player) {
        UUID uuid = player.getUniqueId();
        if (targetKind == TargetKind.HOME) {
            if (!player.hasPermission("marp.delhome")) {
                manager.messages().send(player, Messages.NO_PERMISSION);
                return;
            }
            manager.playClickSound(player);
            manager.homeService().findById(home.id(), uuid)
                    .whenComplete((freshOpt, error) -> manager.scheduler().runOnMain(() -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online == null || !online.isOnline()) {
                            return;
                        }
                        if (error != null || freshOpt == null) {
                            manager.messages().send(online, Messages.STORAGE_ERROR);
                            return;
                        }
                        if (freshOpt.isEmpty() || !freshOpt.get().nameKey().equals(home.nameKey())) {
                            manager.messages().send(
                                    online, Messages.HOME_NOT_FOUND, Map.of("name", home.nameKey()));
                            manager.openHomeList(online, returnPage);
                            return;
                        }
                        manager.homeService().deleteById(home.id(), uuid)
                                .thenCompose(deleted -> manager.homeService().list(uuid)
                                        .thenApply(remaining -> Map.entry(deleted, remaining)))
                                .whenComplete((pair, delError) -> manager.scheduler().runOnMain(() -> {
                                    Player p = Bukkit.getPlayer(uuid);
                                    if (p == null || !p.isOnline()) {
                                        return;
                                    }
                                    if (delError != null || pair == null) {
                                        manager.messages().send(p, Messages.STORAGE_ERROR);
                                        return;
                                    }
                                    if (Boolean.TRUE.equals(pair.getKey())) {
                                        manager.messages().send(
                                                p, Messages.HOME_DELETED, Map.of("name", home.nameKey()));
                                    }
                                    List<Home> remaining = pair.getValue();
                                    int nextPage = Pagination.pageAfterDelete(
                                            returnPage, remaining.size(), manager.config().guiRows());
                                    manager.openHomeListWithSnapshot(p, remaining, nextPage);
                                }));
                    }));
        } else {
            if (!player.hasPermission("marp.admin.delwarp")) {
                manager.messages().send(player, Messages.NO_PERMISSION);
                return;
            }
            manager.playClickSound(player);
            manager.warpService().findById(warp.id())
                    .whenComplete((freshOpt, error) -> manager.scheduler().runOnMain(() -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online == null || !online.isOnline()) {
                            return;
                        }
                        if (error != null || freshOpt == null) {
                            manager.messages().send(online, Messages.STORAGE_ERROR);
                            return;
                        }
                        if (freshOpt.isEmpty() || !freshOpt.get().nameKey().equals(warp.nameKey())) {
                            manager.messages().send(
                                    online, Messages.WARP_NOT_FOUND, Map.of("name", warp.nameKey()));
                            manager.openWarpList(online, returnPage);
                            return;
                        }
                        manager.warpService().deleteById(warp.id())
                                .whenComplete((deleted, delError) -> manager.scheduler().runOnMain(() -> {
                                    Player p = Bukkit.getPlayer(uuid);
                                    if (p == null || !p.isOnline()) {
                                        return;
                                    }
                                    if (delError != null) {
                                        manager.messages().send(p, Messages.STORAGE_ERROR);
                                        return;
                                    }
                                    if (Boolean.TRUE.equals(deleted)) {
                                        manager.messages().send(
                                                p, Messages.WARP_DELETED, Map.of("name", warp.nameKey()));
                                    }
                                    boolean isAdmin = p.hasPermission("marp.admin.setwarp");
                                    int remainingCount = manager.warpService()
                                            .listVisible(p::hasPermission, isAdmin)
                                            .size();
                                    int nextPage = Pagination.pageAfterDelete(
                                            returnPage, remainingCount, manager.config().guiRows());
                                    manager.openWarpList(p, nextPage);
                                }));
                    }));
        }
    }

    private void reopenList(Player player, int page) {
        if (targetKind == TargetKind.HOME) {
            manager.openHomeList(player, page);
        } else {
            manager.openWarpList(player, page);
        }
    }

    private void cancelExpiry() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
    }
}
