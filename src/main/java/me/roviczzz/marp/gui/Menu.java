package me.roviczzz.marp.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/** Base contract implemented by each Marp menu view. */
public interface Menu {

    MarpMenuHolder holder();

    Inventory inventory();

    void onClick(Player player, int slot, ClickType click);

    default void onClose(Player player) {
    }
}
