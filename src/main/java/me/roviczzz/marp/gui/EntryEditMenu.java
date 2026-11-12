package me.roviczzz.marp.gui;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.home.HomeService.EditOutcome;
import me.roviczzz.marp.input.TextLimits;
import me.roviczzz.marp.input.TextLimits.IconResult;
import me.roviczzz.marp.input.TextLimits.LoreResult;
import me.roviczzz.marp.input.TextLimits.NameResult;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.teleport.TeleportReason;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 27-slot entry metadata editor (DESIGN.md §8.4).
 *
 * <p>Only the display name, lore, and icon are editable here; the command
 * identifier is immutable.
 */
public final class EntryEditMenu implements Menu {

    public static final int SLOT_NAME = 10;
    public static final int SLOT_LORE = 12;
    public static final int SLOT_ICON = 14;
    public static final int SLOT_TELEPORT = 16;
    public static final int SLOT_BACK = 22;
    public static final int SLOT_DELETE = 26;

    private final MenuManager manager;
    private final TargetKind targetKind;
    private final Home home;
    private final Warp warp;
    private final int returnPage;
    private final MarpMenuHolder holder;
    private final Inventory inventory;

    private EntryEditMenu(
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
                MenuType.ENTRY_EDIT, targetKind, owner, this.returnPage, id, rev);
        this.inventory = Bukkit.createInventory(
                holder,
                27,
                manager.messages().component(Messages.GUI_EDIT_NAME_LORE, Map.of("name", key)));
        this.holder.bind(inventory, this);
    }

    public static EntryEditMenu forHome(MenuManager manager, UUID owner, Home home, int returnPage) {
        return new EntryEditMenu(manager, owner, TargetKind.HOME, home, null, returnPage);
    }

    public static EntryEditMenu forWarp(MenuManager manager, UUID owner, Warp warp, int returnPage) {
        return new EntryEditMenu(manager, owner, TargetKind.WARP, null, warp, returnPage);
    }

    public void open(Player player) {
        render();
        player.openInventory(inventory);
    }

    private void render() {
        GuiItemFactory factory = manager.itemFactory();
        ItemStack filler = factory.filler();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        String currentName = currentDisplayName() != null ? currentDisplayName() : identifier();
        inventory.setItem(
                SLOT_NAME,
                factory.simpleButton(
                        Material.NAME_TAG,
                        manager.messages().component(Messages.GUI_EDIT_NAME),
                        List.of(manager.messages().component(
                                Messages.GUI_EDIT_NAME_LORE, Map.of("name", currentName)))));

        inventory.setItem(
                SLOT_LORE,
                factory.simpleButton(
                        Material.WRITABLE_BOOK,
                        manager.messages().component(Messages.GUI_EDIT_LORE),
                        List.of(manager.messages().component(
                                Messages.GUI_EDIT_LORE_LORE,
                                Map.of("lines", Integer.toString(manager.config().maxLoreLines()))))));

        Material iconMat = GuiItemFactory.resolveMaterial(
                currentIcon().material(), Material.ITEM_FRAME);
        inventory.setItem(
                SLOT_ICON,
                factory.simpleButton(
                        iconMat,
                        manager.messages().component(Messages.GUI_EDIT_ICON),
                        List.of(manager.messages().component(Messages.GUI_EDIT_ICON_LORE))));

        inventory.setItem(
                SLOT_TELEPORT,
                factory.simpleButton(
                        Material.ENDER_PEARL,
                        manager.messages().component(Messages.GUI_EDIT_TELEPORT),
                        List.of()));

        inventory.setItem(
                SLOT_BACK,
                factory.simpleButton(
                        Material.ARROW,
                        manager.messages().component(Messages.GUI_EDIT_BACK),
                        List.of()));

        inventory.setItem(
                SLOT_DELETE,
                factory.simpleButton(
                        Material.BARRIER,
                        manager.messages().component(Messages.GUI_EDIT_DELETE),
                        List.of()));
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
        if (click.isShiftClick()) {
            return;
        }
        if (!checkEditPermission(player)) {
            manager.messages().send(player, Messages.NO_PERMISSION);
            return;
        }
        switch (slot) {
            case SLOT_NAME -> beginNameInput(player);
            case SLOT_LORE -> beginLoreInput(player);
            case SLOT_ICON -> updateIconFromHand(player);
            case SLOT_TELEPORT -> teleportToEntry(player);
            case SLOT_BACK -> {
                manager.playClickSound(player);
                reopenList(player);
            }
            case SLOT_DELETE -> openDeleteConfirmation(player);
            default -> {
            }
        }
    }

    private boolean checkEditPermission(Player player) {
        if (targetKind == TargetKind.HOME) {
            return player.hasPermission("marp.homes");
        }
        return player.hasPermission("marp.admin.setwarp")
                || player.hasPermission("marp.admin.editwarp");
    }

    private void beginNameInput(Player player) {
        UUID uuid = player.getUniqueId();
        manager.playClickSound(player);
        player.closeInventory();
        manager.messages().send(player, Messages.GUI_INPUT_NAME_PROMPT);
        manager.textInput().beginInput(
                uuid,
                typed -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    NameResult validated = TextLimits.validateDisplayName(
                            typed, manager.messages(), manager.config());
                    if (validated instanceof NameResult.Invalid invalid) {
                        manager.messages().send(
                                online, Messages.GUI_INPUT_INVALID, Map.of("reason", invalid.reason()));
                        reopenSelf(online);
                        return;
                    }
                    String newName = ((NameResult.Valid) validated).value();
                    applyMetadataPatch(online, null, Optional.of(newName), null, null);
                },
                () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        reopenSelf(online);
                    }
                });
    }

    private void beginLoreInput(Player player) {
        UUID uuid = player.getUniqueId();
        manager.playClickSound(player);
        player.closeInventory();
        manager.messages().send(
                player,
                Messages.GUI_INPUT_LORE_PROMPT,
                Map.of("lines", Integer.toString(manager.config().maxLoreLines())));
        manager.textInput().beginInput(
                uuid,
                typed -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    LoreResult validated = TextLimits.validateLore(
                            typed, manager.messages(), manager.config());
                    if (validated instanceof LoreResult.Invalid invalid) {
                        manager.messages().send(
                                online, Messages.GUI_INPUT_INVALID, Map.of("reason", invalid.reason()));
                        reopenSelf(online);
                        return;
                    }
                    List<String> newLore = ((LoreResult.Valid) validated).lines();
                    applyMetadataPatch(online, null, null, Optional.of(newLore), null);
                },
                () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        reopenSelf(online);
                    }
                });
    }

    private void updateIconFromHand(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        ItemStack held = (cursor != null && !cursor.getType().isAir())
                ? cursor
                : player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir() || !held.getType().isItem()) {
            manager.messages().send(player, Messages.GUI_ICON_INVALID);
            return;
        }
        ItemStack singleCopy = held.asOne();
        byte[] bytes;
        try {
            bytes = singleCopy.serializeAsBytes();
        } catch (RuntimeException exception) {
            manager.messages().send(player, Messages.GUI_ICON_INVALID);
            return;
        }
        IconResult validated = TextLimits.validateIcon(
                singleCopy.getType().name(), singleCopy.getType().isItem(), bytes, manager.config());
        if (validated instanceof IconResult.Invalid) {
            manager.messages().send(player, Messages.GUI_ICON_INVALID);
            return;
        }
        if (validated instanceof IconResult.TooLarge) {
            manager.messages().send(player, Messages.GUI_ICON_TOO_LARGE);
            return;
        }
        IconSpec newIcon = ((IconResult.Valid) validated).icon();
        manager.playClickSound(player);
        applyMetadataPatch(player, newIcon, null, null, Messages.GUI_ICON_SET);
    }

    private void applyMetadataPatch(
            Player player,
            IconSpec newIcon,
            Optional<String> newDisplayName,
            Optional<List<String>> newLore,
            String successMessageKey) {
        UUID uuid = player.getUniqueId();
        if (targetKind == TargetKind.HOME) {
            manager.homeService()
                    .patchMetadata(home.id(), uuid, home.rev(), newIcon, newDisplayName, newLore)
                    .whenComplete((outcome, error) -> manager.scheduler().runOnMain(() -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online == null || !online.isOnline()) {
                            return;
                        }
                        if (error != null || outcome == null) {
                            manager.messages().send(online, Messages.STORAGE_ERROR);
                            return;
                        }
                        if (outcome instanceof EditOutcome.Saved<Home> saved) {
                            if (successMessageKey != null) {
                                manager.messages().send(online, successMessageKey);
                            }
                            manager.openHomeEdit(online, saved.entry(), returnPage);
                        } else if (outcome instanceof EditOutcome.Conflict) {
                            manager.messages().send(online, Messages.CONFLICT);
                            manager.openHomeList(online, returnPage);
                        } else {
                            manager.messages().send(
                                    online, Messages.HOME_NOT_FOUND, Map.of("name", home.nameKey()));
                            manager.openHomeList(online, returnPage);
                        }
                    }));
        } else {
            manager.warpService()
                    .patchMetadata(warp.id(), warp.rev(), newIcon, newDisplayName, newLore)
                    .whenComplete((outcome, error) -> manager.scheduler().runOnMain(() -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online == null || !online.isOnline()) {
                            return;
                        }
                        if (error != null || outcome == null) {
                            manager.messages().send(online, Messages.STORAGE_ERROR);
                            return;
                        }
                        if (outcome instanceof EditOutcome.Saved<Warp> saved) {
                            if (successMessageKey != null) {
                                manager.messages().send(online, successMessageKey);
                            }
                            manager.openWarpEdit(online, saved.entry(), returnPage);
                        } else if (outcome instanceof EditOutcome.Conflict) {
                            manager.messages().send(online, Messages.CONFLICT);
                            manager.openWarpList(online, returnPage);
                        } else {
                            manager.messages().send(
                                    online, Messages.WARP_NOT_FOUND, Map.of("name", warp.nameKey()));
                            manager.openWarpList(online, returnPage);
                        }
                    }));
        }
    }

    private void teleportToEntry(Player player) {
        LocationSnapshot target = targetKind == TargetKind.HOME ? home.location() : warp.location();
        TeleportReason reason = targetKind == TargetKind.HOME ? TeleportReason.HOME : TeleportReason.WARP;
        if (Bukkit.getWorld(target.world()) == null) {
            manager.messages().send(player, Messages.TELEPORT_FAILED);
            return;
        }
        manager.playClickSound(player);
        player.closeInventory();
        manager.teleportService().requestTeleport(
                player.getUniqueId(), reason, identifier(), target, null);
    }

    private void openDeleteConfirmation(Player player) {
        if (targetKind == TargetKind.HOME) {
            if (!player.hasPermission("marp.delhome")) {
                manager.messages().send(player, Messages.NO_PERMISSION);
                return;
            }
            manager.playClickSound(player);
            manager.openHomeDeleteConfirm(player, home, returnPage);
        } else {
            if (!player.hasPermission("marp.admin.delwarp")) {
                manager.messages().send(player, Messages.NO_PERMISSION);
                return;
            }
            manager.playClickSound(player);
            manager.openWarpDeleteConfirm(player, warp, returnPage);
        }
    }

    private void reopenSelf(Player player) {
        if (targetKind == TargetKind.HOME) {
            manager.openHomeEdit(player, home, returnPage);
        } else {
            manager.openWarpEdit(player, warp, returnPage);
        }
    }

    private void reopenList(Player player) {
        if (targetKind == TargetKind.HOME) {
            manager.openHomeList(player, returnPage);
        } else {
            manager.openWarpList(player, returnPage);
        }
    }

    private String identifier() {
        return targetKind == TargetKind.HOME ? home.nameKey() : warp.nameKey();
    }

    private String currentDisplayName() {
        return targetKind == TargetKind.HOME ? home.displayName() : warp.displayName();
    }

    private IconSpec currentIcon() {
        return targetKind == TargetKind.HOME ? home.icon() : warp.icon();
    }
}
