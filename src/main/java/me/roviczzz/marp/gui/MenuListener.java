package me.roviczzz.marp.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Intercepts all interactions with Marp menus (DESIGN.md §8.1).
 *
 * <p>Menus are identified exclusively by {@link MarpMenuHolder}, never by
 * title. Every click and drag while a Marp menu is open is cancelled so items
 * can never be extracted, swapped, or duplicated.
 */
public final class MenuListener implements Listener {

    public static final long DEBOUNCE_MILLIS = 200L;

    private final LongSupplier clock;
    private final Map<UUID, Long> lastActionMillis = new ConcurrentHashMap<>();

    public MenuListener(LongSupplier clock) {
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MarpMenuHolder holder)) {
            return;
        }
        // Cancel every click while a Marp menu is open, including clicks inside
        // the player's own inventory.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.getUniqueId().equals(holder.owner())) {
            player.closeInventory();
            return;
        }
        if (!isSupportedClickType(event.getClick())) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        if (!allowAction(player.getUniqueId())) {
            return;
        }
        Menu menu = holder.menu();
        if (menu != null) {
            menu.onClick(player, rawSlot, event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MarpMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MarpMenuHolder holder)) {
            return;
        }
        if (event.getPlayer() instanceof Player player && holder.menu() != null) {
            holder.menu().onClose(player);
        }
    }

    public boolean allowAction(UUID player) {
        long now = clock.getAsLong();
        Long previous = lastActionMillis.get(player);
        if (previous != null && now - previous < DEBOUNCE_MILLIS) {
            return false;
        }
        lastActionMillis.put(player, now);
        return true;
    }

    public void clearPlayer(UUID player) {
        lastActionMillis.remove(player);
    }

    public static boolean isSupportedClickType(ClickType click) {
        return switch (click) {
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT -> true;
            default -> false;
        };
    }
}
