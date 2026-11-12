package me.roviczzz.marp.gui;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Identity marker attached to every Marp chest inventory.
 *
 * <p>Menus are identified exclusively by {@code holder instanceof MarpMenuHolder},
 * never by inventory title.
 */
public final class MarpMenuHolder implements InventoryHolder {

    public enum MenuType {
        HOME_LIST,
        WARP_LIST,
        ENTRY_EDIT,
        DELETE_CONFIRM,
        FAVORITES
    }

    public enum TargetKind {
        HOME,
        WARP,
        MIXED
    }

    private final MenuType menuType;
    private final TargetKind targetKind;
    private final UUID owner;
    private final int page;
    private final long entryId;
    private final long expectedRev;
    private Inventory inventory;
    private Menu menu;

    public MarpMenuHolder(
            MenuType menuType,
            TargetKind targetKind,
            UUID owner,
            int page,
            long entryId,
            long expectedRev) {
        this.menuType = menuType;
        this.targetKind = targetKind;
        this.owner = owner;
        this.page = Math.max(0, page);
        this.entryId = entryId;
        this.expectedRev = expectedRev;
    }

    public MenuType menuType() {
        return menuType;
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    public UUID owner() {
        return owner;
    }

    public int page() {
        return page;
    }

    public long entryId() {
        return entryId;
    }

    public long expectedRev() {
        return expectedRev;
    }

    public void bind(Inventory inventory, Menu menu) {
        this.inventory = inventory;
        this.menu = menu;
    }

    public Menu menu() {
        return menu;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
