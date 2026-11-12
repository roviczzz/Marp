package me.roviczzz.marp.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.home.HomeService.SetOutcome;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.Page;
import me.roviczzz.marp.model.Pagination;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.util.LocationCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Paginated player homes menu (DESIGN.md §8.2).
 */
public final class HomeListMenu implements Menu {

    private final MenuManager manager;
    private final List<Home> allHomes;
    private final Page page;
    private final List<Home> pageSlice;
    private final int limit;
    private final int rows;
    private final int navBase;
    private final MarpMenuHolder holder;
    private final Inventory inventory;

    public HomeListMenu(
            MenuManager manager,
            UUID owner,
            List<Home> allHomes,
            int requestedPage,
            int limit) {
        this.manager = manager;
        this.allHomes = List.copyOf(allHomes);
        this.rows = manager.config().guiRows();
        this.page = Pagination.page(requestedPage, this.allHomes.size(), rows);
        this.pageSlice = this.page.slice(this.allHomes);
        this.limit = limit;
        this.navBase = (rows - 1) * 9;
        this.holder = new MarpMenuHolder(
                MenuType.HOME_LIST, TargetKind.HOME, owner, this.page.index(), 0L, 0L);
        this.inventory = Bukkit.createInventory(
                holder,
                rows * 9,
                manager.messages().component(Messages.GUI_TITLE_HOMES));
        this.holder.bind(inventory, this);
    }

    public void open(Player player) {
        render(player);
        player.openInventory(inventory);
    }

    private void render(Player player) {
        GuiItemFactory factory = manager.itemFactory();
        for (int i = 0; i < pageSlice.size(); i++) {
            inventory.setItem(i, factory.homeEntry(pageSlice.get(i)));
        }
        if (allHomes.isEmpty()) {
            int emptySlot = Math.min(navBase - 5, 22);
            inventory.setItem(emptySlot, factory.emptyHomesItem());
        }

        boolean canCreate = player.hasPermission("marp.sethome") && allHomes.size() < limit;
        inventory.setItem(navBase, factory.previousButton(page));
        inventory.setItem(navBase + 1, factory.filler());
        inventory.setItem(navBase + 2, factory.filler());
        inventory.setItem(navBase + 3, factory.setHomeButton(canCreate));
        inventory.setItem(navBase + 4, factory.homePageInfo(page, allHomes.size(), limit));
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
        Home clicked = pageSlice.get(slot);
        revalidateAndAct(player, clicked, click);
    }

    private void handleNavigation(Player player, int navOffset) {
        switch (navOffset) {
            case 0 -> {
                if (page.hasPrevious()) {
                    manager.playClickSound(player);
                    manager.openHomeList(player, page.index() - 1);
                }
            }
            case 3 -> handleSetHomeHere(player);
            case 5 -> {
                manager.playClickSound(player);
                player.closeInventory();
            }
            case 8 -> {
                if (page.hasNext()) {
                    manager.playClickSound(player);
                    manager.openHomeList(player, page.index() + 1);
                }
            }
            default -> {
            }
        }
    }

    private void handleSetHomeHere(Player player) {
        if (!player.hasPermission("marp.sethome")) {
            manager.messages().send(player, Messages.NO_PERMISSION);
            return;
        }
        int currentLimit = manager.resolveHomeLimit(player);
        if (currentLimit <= 0) {
            manager.messages().send(player, Messages.HOME_LIMIT_ZERO);
            return;
        }
        if (allHomes.size() >= currentLimit) {
            manager.messages().send(
                    player,
                    Messages.HOME_LIMIT_REACHED,
                    Map.of("limit", Integer.toString(currentLimit)));
            return;
        }
        if (player.getWorld() == null) {
            return;
        }
        LocationSnapshot snapshot = LocationCodec.fromLocation(player.getLocation());
        UUID uuid = player.getUniqueId();
        int returnPage = page.index();

        manager.playClickSound(player);
        player.closeInventory();
        manager.messages().send(player, Messages.GUI_INPUT_NAME_PROMPT);
        manager.textInput().beginInput(
                uuid,
                typedName -> manager.homeService()
                        .setHome(uuid, typedName, snapshot, manager.resolveHomeLimit(player))
                        .whenComplete((outcome, error) -> manager.scheduler().runOnMain(() -> {
                            Player online = Bukkit.getPlayer(uuid);
                            if (online == null || !online.isOnline()) {
                                return;
                            }
                            if (error != null || outcome == null) {
                                manager.messages().send(online, Messages.STORAGE_ERROR);
                                return;
                            }
                            if (outcome instanceof SetOutcome.Created created) {
                                manager.messages().send(
                                        online, Messages.HOME_SET, Map.of("name", created.home().nameKey()));
                            } else if (outcome instanceof SetOutcome.Updated updated) {
                                manager.messages().send(
                                        online, Messages.HOME_UPDATED, Map.of("name", updated.home().nameKey()));
                            } else if (outcome instanceof SetOutcome.LimitZero) {
                                manager.messages().send(online, Messages.HOME_LIMIT_ZERO);
                            } else if (outcome instanceof SetOutcome.LimitReached reached) {
                                manager.messages().send(
                                        online,
                                        Messages.HOME_LIMIT_REACHED,
                                        Map.of("limit", Integer.toString(reached.limit())));
                            } else if (outcome instanceof SetOutcome.InvalidName invalid) {
                                switch (invalid.reason()) {
                                    case TOO_LONG -> manager.messages().send(
                                            online,
                                            Messages.HOME_NAME_TOO_LONG,
                                            Map.of("max", Integer.toString(invalid.maxLength())));
                                    case RESERVED -> manager.messages().send(
                                            online,
                                            Messages.HOME_NAME_RESERVED,
                                            Map.of("name", invalid.attempted()));
                                    default -> manager.messages().send(
                                            online,
                                            Messages.HOME_NAME_INVALID,
                                            Map.of("pattern", invalid.pattern()));
                                }
                            }
                            manager.openHomeList(online, returnPage);
                        })),
                () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        manager.openHomeList(online, returnPage);
                    }
                });
    }

    private void revalidateAndAct(Player player, Home clicked, ClickType click) {
        UUID uuid = player.getUniqueId();
        int currentPage = page.index();
        manager.homeService().findById(clicked.id(), uuid)
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
                                online, Messages.HOME_NOT_FOUND, Map.of("name", clicked.nameKey()));
                        manager.openHomeList(online, currentPage);
                        return;
                    }
                    Home fresh = freshOpt.get();
                    if (fresh.rev() != clicked.rev() || !fresh.nameKey().equals(clicked.nameKey())) {
                        manager.messages().send(online, Messages.CONFLICT);
                        manager.openHomeList(online, currentPage);
                        return;
                    }

                    // Shift checks run before left/right because SHIFT_LEFT.isLeftClick() is true.
                    if (click.isShiftClick()) {
                        if (!online.hasPermission("marp.delhome")) {
                            manager.messages().send(online, Messages.NO_PERMISSION);
                            return;
                        }
                        manager.playClickSound(online);
                        manager.openHomeDeleteConfirm(online, fresh, currentPage);
                    } else if (click.isLeftClick()) {
                        if (!online.hasPermission("marp.home")) {
                            manager.messages().send(online, Messages.NO_PERMISSION);
                            return;
                        }
                        if (Bukkit.getWorld(fresh.location().world()) == null) {
                            manager.messages().send(online, Messages.TELEPORT_FAILED);
                            return;
                        }
                        manager.playClickSound(online);
                        online.closeInventory();
                        manager.teleportService().requestTeleport(
                                uuid, TeleportReason.HOME, fresh.nameKey(), fresh.location(), null);
                    } else if (click.isRightClick()) {
                        if (!online.hasPermission("marp.homes")) {
                            manager.messages().send(online, Messages.NO_PERMISSION);
                            return;
                        }
                        manager.playClickSound(online);
                        manager.openHomeEdit(online, fresh, currentPage);
                    }
                }));
    }
}
