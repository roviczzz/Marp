package me.roviczzz.marp.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.home.HomeLimitResolver;
import me.roviczzz.marp.input.TextLimits;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.Page;
import me.roviczzz.marp.model.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Builds every {@link ItemStack} rendered inside a Marp menu (DESIGN.md §8.6).
 *
 * <p>Corrupt, oversized, or unrecognised icon data logs at most once per entry
 * and falls back to the stored material (or a safe default material) so a bad
 * icon can never break pagination or crash a menu.
 */
public final class GuiItemFactory {

    private final Supplier<MarpConfig> config;
    private final Supplier<MessageService> messages;
    private final Consumer<String> warn;
    private final Set<String> loggedCorruptIcons = new LinkedHashSet<>();

    public GuiItemFactory(
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages,
            Consumer<String> warn) {
        this.config = config;
        this.messages = messages;
        this.warn = warn;
    }

    public ItemStack homeEntry(Home home) {
        return homeEntry(home, false);
    }

    public ItemStack homeEntry(Home home, boolean isFavorite) {
        boolean worldLoaded = Bukkit.getWorld(home.location().world()) != null;
        return buildEntryItem(
                "home:" + home.id(),
                home.nameKey(),
                home.displayName(),
                home.lore(),
                home.icon(),
                home.isBed() ? Material.RED_BED : Material.GRASS_BLOCK,
                home.location(),
                home.createdAt(),
                worldLoaded,
                false,
                home.isBed(),
                isFavorite);
    }

    public ItemStack warpEntry(Warp warp, boolean locked) {
        return warpEntry(warp, locked, false);
    }

    public ItemStack warpEntry(Warp warp, boolean locked, boolean isFavorite) {
        boolean worldLoaded = Bukkit.getWorld(warp.location().world()) != null;
        return buildEntryItem(
                "warp:" + warp.id(),
                warp.nameKey(),
                warp.displayName(),
                warp.lore(),
                warp.icon(),
                Material.ENDER_PEARL,
                warp.location(),
                warp.createdAt(),
                worldLoaded,
                locked,
                false,
                isFavorite);
    }

    public ItemStack filler() {
        Material mat = resolveMaterial(config.get().fillerMaterial(), Material.GRAY_STAINED_GLASS_PANE);
        return namedItem(mat, Component.text(" "), List.of());
    }

    public ItemStack previousButton(Page page) {
        if (!page.hasPrevious()) {
            return filler();
        }
        return namedItem(Material.ARROW, noItalic(messages.get().component(Messages.GUI_PREV_PAGE)), List.of());
    }

    public ItemStack nextButton(Page page) {
        if (!page.hasNext()) {
            return filler();
        }
        return namedItem(Material.ARROW, noItalic(messages.get().component(Messages.GUI_NEXT_PAGE)), List.of());
    }

    public ItemStack closeButton() {
        return namedItem(Material.BARRIER, noItalic(messages.get().component(Messages.GUI_CLOSE)), List.of());
    }

    public ItemStack setHomeButton(boolean allowed) {
        Material mat = allowed ? Material.NAME_TAG : resolveMaterial(
                config.get().fillerMaterial(), Material.GRAY_STAINED_GLASS_PANE);
        if (!allowed) {
            return filler();
        }
        return namedItem(mat, noItalic(messages.get().component(Messages.GUI_SET_HOME_HERE)), List.of());
    }

    public ItemStack warpHelpButton() {
        return namedItem(Material.COMPASS, noItalic(messages.get().component(Messages.GUI_WARP_HELP)), List.of());
    }

    public ItemStack bedToggleButton(boolean autoBedEnabled) {
        Material mat = autoBedEnabled ? Material.RED_BED : Material.GRAY_DYE;
        Component title = noItalic(messages.get().component(
                autoBedEnabled ? Messages.GUI_NAV_BED_ON : Messages.GUI_NAV_BED_OFF));
        Component lore = noItalic(messages.get().component(Messages.GUI_NAV_BED_LORE));
        return namedItem(mat, title, List.of(lore));
    }

    public ItemStack emptyHomesItem() {
        return namedItem(
                Material.CHEST,
                noItalic(messages.get().component(Messages.GUI_NO_HOMES)),
                List.of(noItalic(messages.get().component(Messages.HOME_NONE))));
    }

    public ItemStack emptyWarpsItem() {
        return namedItem(
                Material.COMPASS,
                noItalic(messages.get().component(Messages.GUI_NO_WARPS)),
                List.of(noItalic(messages.get().component(Messages.GUI_WARP_HELP))));
    }

    public ItemStack emptyFavoritesItem() {
        return namedItem(
                Material.NETHER_STAR,
                noItalic(messages.get().component(Messages.GUI_NO_FAVORITES)),
                List.of(noItalic(messages.get().component(Messages.FAVORITES_NONE))));
    }

    public ItemStack favoritesNavButton() {
        return namedItem(
                Material.NETHER_STAR,
                noItalic(messages.get().component(Messages.GUI_NAV_FAVORITES)),
                List.of());
    }

    public ItemStack homesNavButton() {
        return namedItem(
                Material.OAK_DOOR,
                noItalic(messages.get().component(Messages.GUI_NAV_HOMES)),
                List.of());
    }

    public ItemStack warpsNavButton() {
        return namedItem(
                Material.ENDER_PEARL,
                noItalic(messages.get().component(Messages.GUI_NAV_WARPS)),
                List.of());
    }

    public ItemStack starModeButton(boolean active) {
        Material mat = active ? Material.GLOWSTONE_DUST : Material.GUNPOWDER;
        Component title = noItalic(messages.get().component(
                active ? Messages.GUI_NAV_STAR_MODE_ON : Messages.GUI_NAV_STAR_MODE_OFF));
        Component lore = noItalic(messages.get().component(Messages.GUI_NAV_STAR_MODE_LORE));
        return namedItem(mat, title, List.of(lore));
    }

    public ItemStack filterButton(String filterLabel) {
        Component title = noItalic(messages.get().component(
                Messages.GUI_NAV_FILTER, Map.of("filter", filterLabel)));
        Component lore = noItalic(messages.get().component(Messages.GUI_NAV_FILTER_LORE));
        return namedItem(Material.HOPPER, title, List.of(lore));
    }

    public ItemStack favoriteSlotButton(boolean isFavorited) {
        Material mat = isFavorited ? Material.NETHER_STAR : Material.GUNPOWDER;
        Component title = noItalic(messages.get().component(
                isFavorited ? Messages.GUI_EDIT_FAVORITE_ON : Messages.GUI_EDIT_FAVORITE_OFF));
        Component lore = noItalic(messages.get().component(Messages.GUI_EDIT_FAVORITE_LORE));
        ItemStack stack = namedItem(mat, title, List.of(lore));
        if (isFavorited) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    public ItemStack homePageInfo(Page page, int usedCount, int limit) {
        Component title = noItalic(messages.get().component(
                Messages.GUI_PAGE_INFO,
                Map.of(
                        "page", Integer.toString(page.number()),
                        "pages", Integer.toString(page.pages()),
                        "count", Integer.toString(usedCount))));
        String limitText = limit == HomeLimitResolver.UNLIMITED ? "∞" : Integer.toString(limit);
        boolean overLimit = limit != HomeLimitResolver.UNLIMITED && usedCount > limit;
        Component usageLine = noItalic(Component.text(
                usedCount + " / " + limitText,
                overLimit ? NamedTextColor.RED : NamedTextColor.GRAY));
        return namedItem(Material.WRITABLE_BOOK, title, List.of(usageLine));
    }

    public ItemStack warpPageInfo(Page page) {
        Component title = noItalic(messages.get().component(
                Messages.GUI_PAGE_INFO,
                Map.of(
                        "page", Integer.toString(page.number()),
                        "pages", Integer.toString(page.pages()),
                        "count", Integer.toString(page.total()))));
        return namedItem(Material.WRITABLE_BOOK, title, List.of());
    }

    public ItemStack simpleButton(Material material, Component name, List<Component> lore) {
        List<Component> cleanLore = new ArrayList<>(lore.size());
        for (Component line : lore) {
            cleanLore.add(noItalic(line));
        }
        return namedItem(material, noItalic(name), cleanLore);
    }

    public List<Component> buildEntryLore(
            List<String> customLore,
            LocationSnapshot location,
            long createdAt,
            boolean worldLoaded,
            boolean locked) {
        return buildEntryLore(customLore, location, createdAt, worldLoaded, locked, false);
    }

    public List<Component> buildEntryLore(
            List<String> customLore,
            LocationSnapshot location,
            long createdAt,
            boolean worldLoaded,
            boolean locked,
            boolean isBed) {
        return buildEntryLore(customLore, location, createdAt, worldLoaded, locked, isBed, false);
    }

    public List<Component> buildEntryLore(
            List<String> customLore,
            LocationSnapshot location,
            long createdAt,
            boolean worldLoaded,
            boolean locked,
            boolean isBed,
            boolean isFavorite) {
        MessageService msg = messages.get();
        MarpConfig cfg = config.get();
        List<Component> lines = new ArrayList<>();

        if (customLore != null && !customLore.isEmpty()) {
            for (String rawLine : customLore) {
                lines.add(noItalic(msg.renderUserText(rawLine, cfg.allowMiniMessage())));
            }
            lines.add(Component.empty());
        }

        if (isFavorite) {
            lines.add(noItalic(msg.component(Messages.GUI_FAVORITE_LORE)));
        }
        if (isBed) {
            lines.add(noItalic(msg.component(Messages.GUI_HOME_LORE_BED)));
        }
        lines.add(noItalic(msg.component(
                Messages.GUI_HOME_LORE_WORLD, Map.of("world", location.world()))));
        if (worldLoaded) {
            lines.add(noItalic(msg.component(
                    Messages.GUI_HOME_LORE_COORDS,
                    Map.of(
                            "x", formatCoord(location.x()),
                            "y", formatCoord(location.y()),
                            "z", formatCoord(location.z())))));
        } else {
            lines.add(noItalic(msg.component(
                    Messages.WORLD_MISSING, Map.of("world", location.world()))));
        }
        lines.add(noItalic(msg.component(
                Messages.GUI_HOME_LORE_CREATED, Map.of("date", msg.formatDate(createdAt)))));

        if (locked) {
            lines.add(noItalic(msg.component(Messages.GUI_WARP_LOCKED)));
        }

        lines.add(Component.empty());
        lines.add(noItalic(msg.component(Messages.GUI_ACTIONS)));
        return lines;
    }

    public static String normalizeMaterialName(String materialName, String fallback) {
        if (materialName == null || materialName.isBlank()) {
            return fallback;
        }
        String normalized = materialName.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("MINECRAFT:")) {
            normalized = normalized.substring("MINECRAFT:".length());
        }
        if (normalized.equals("AIR")
                || normalized.equals("CAVE_AIR")
                || normalized.equals("VOID_AIR")) {
            return fallback;
        }
        return normalized;
    }

    public static Material resolveMaterial(String materialName, Material fallback) {
        String normalized = normalizeMaterialName(materialName, fallback.name());
        Material match = Material.matchMaterial(normalized);
        if (match == null || match.isAir() || !match.isItem()) {
            return fallback;
        }
        return match;
    }

    private ItemStack buildEntryItem(
            String entryToken,
            String identifier,
            String displayName,
            List<String> customLore,
            IconSpec icon,
            Material defaultMaterial,
            LocationSnapshot location,
            long createdAt,
            boolean worldLoaded,
            boolean locked,
            boolean isBed,
            boolean isFavorite) {
        ItemStack stack = resolveIconStack(entryToken, icon, defaultMaterial);
        Component titlePrefix = isFavorite
                ? messages.get().component(Messages.GUI_FAVORITE_BADGE)
                : Component.empty();
        Component title = noItalic(titlePrefix.append(TextLimits.renderEntryName(
                identifier, displayName, messages.get(), config.get())));
        List<Component> lore = buildEntryLore(
                customLore, location, createdAt, worldLoaded, locked, isBed, isFavorite);
        applyMeta(stack, title, lore);
        if (isFavorite) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private ItemStack resolveIconStack(String entryToken, IconSpec icon, Material defaultMaterial) {
        Material fallback = icon == null
                ? defaultMaterial
                : resolveMaterial(icon.material(), defaultMaterial);
        if (icon != null && icon.hasItemBytes()) {
            byte[] bytes = icon.itemBytes();
            if (bytes.length <= config.get().maxIconBytes()) {
                try {
                    ItemStack deserialized = ItemStack.deserializeBytes(bytes);
                    if (deserialized != null
                            && !deserialized.getType().isAir()
                            && deserialized.getType().isItem()) {
                        ItemStack copy = deserialized.asOne();
                        return copy;
                    }
                } catch (RuntimeException exception) {
                    if (loggedCorruptIcons.add(entryToken)) {
                        warn.accept("Stored icon for " + entryToken + " could not be read ("
                                + exception.getMessage() + "); using fallback material " + fallback.name());
                    }
                }
            } else if (loggedCorruptIcons.add(entryToken)) {
                warn.accept("Stored icon for " + entryToken + " exceeds max-icon-bytes; using " + fallback.name());
            }
        }
        return new ItemStack(fallback, 1);
    }

    private static ItemStack namedItem(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material, 1);
        applyMeta(stack, name, lore);
        return stack;
    }

    private static void applyMeta(ItemStack stack, Component name, List<Component> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String formatCoord(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
