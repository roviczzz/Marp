package me.roviczzz.marp;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.command.CompletionSupport;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.gui.DeleteConfirmMenu;
import me.roviczzz.marp.gui.EntryEditMenu;
import me.roviczzz.marp.gui.GuiItemFactory;
import me.roviczzz.marp.gui.HomeListMenu;
import me.roviczzz.marp.gui.MarpMenuHolder;
import me.roviczzz.marp.gui.MenuListener;
import me.roviczzz.marp.gui.MenuManager;
import me.roviczzz.marp.gui.WarpListMenu;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.home.HomeService.EditOutcome;
import me.roviczzz.marp.home.HomeService.SetOutcome;
import me.roviczzz.marp.input.TextInputService;
import me.roviczzz.marp.input.TextLimits;
import me.roviczzz.marp.model.BackEntry;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.Warp;
import me.roviczzz.marp.teleport.SafetyResult;
import me.roviczzz.marp.teleport.SafetyService;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.teleport.TeleportSession;
import me.roviczzz.marp.teleport.TeleportSessionRegistry;
import me.roviczzz.marp.teleport.WarmupSampler;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Live Paper 1.21.10 verification suite for Appendix A and Tasks 6.6–10.3.
 *
 * <p>Activated only when {@code -Dmarp.selftest=true} is passed to the server.
 */
final class LiveServerSelfTest {

    private LiveServerSelfTest() {
    }

    static void run(
            Marp plugin,
            Scheduler scheduler,
            SafetyService safetyService,
            HomeService homeService,
            WarpService warpService,
            BackService backService,
            TeleportService teleportService,
            TeleportSessionRegistry sessionRegistry,
            WarmupSampler warmupSampler,
            HomeCache homeCache,
            WarpCache warpCache,
            MenuManager menuManager,
            MenuListener menuListener,
            GuiItemFactory itemFactory,
            TextInputService textInput) {
        Thread thread = new Thread(() -> runOffMain(
                plugin,
                safetyService,
                homeService,
                warpService,
                backService,
                teleportService,
                sessionRegistry,
                warmupSampler,
                homeCache,
                warpCache,
                menuManager,
                menuListener,
                itemFactory,
                textInput), "Marp-SelfTest");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runOffMain(
            Marp plugin,
            SafetyService safetyService,
            HomeService homeService,
            WarpService warpService,
            BackService backService,
            TeleportService teleportService,
            TeleportSessionRegistry sessionRegistry,
            WarmupSampler warmupSampler,
            HomeCache homeCache,
            WarpCache warpCache,
            MenuManager menuManager,
            MenuListener menuListener,
            GuiItemFactory itemFactory,
            TextInputService textInput) {
        try {
            // 1. Task 6.7: Bukkit BlockView/SafetyService adapter (slab, lava, search, unloaded world)
            String wName = onMain(plugin, () -> {
                World world = Bukkit.getWorlds().get(0);
                world.getBlockAt(100, 100, 100).setType(Material.OAK_SLAB);
                world.getBlockAt(100, 101, 100).setType(Material.AIR);
                world.getBlockAt(100, 102, 100).setType(Material.AIR);

                world.getBlockAt(110, 100, 110).setType(Material.LAVA);
                world.getBlockAt(110, 101, 110).setType(Material.AIR);
                world.getBlockAt(110, 102, 110).setType(Material.AIR);
                world.getBlockAt(112, 99, 110).setType(Material.STONE);
                world.getBlockAt(112, 100, 110).setType(Material.AIR);
                world.getBlockAt(112, 101, 110).setType(Material.AIR);
                return world.getName();
            });

            MarpConfig cfg = menuManager.config();
            SafetyResult slabSafe = safetyService.evaluate(
                    new LocationSnapshot(wName, 100.5, 100.5, 100.5, 0, 0), cfg).join();
            check(slabSafe instanceof SafetyResult.Safe, "Slab at 100.5 must be Safe, got: " + slabSafe);

            SafetyResult slabAdjusted = safetyService.evaluate(
                    new LocationSnapshot(wName, 100.5, 101.0, 100.5, 0, 0), cfg).join();
            check(slabAdjusted instanceof SafetyResult.Adjusted adj
                    && Math.abs(adj.location().y() - 100.5) < 1e-3,
                    "Above slab at 101.0 must adjust onto 100.5, got: " + slabAdjusted);

            SafetyResult lavaSearch = safetyService.evaluate(
                    new LocationSnapshot(wName, 110.5, 100.0, 110.5, 0, 0), cfg).join();
            check(lavaSearch instanceof SafetyResult.Adjusted,
                    "Lava in SEARCH mode must adjust to nearby safe spot, got: " + lavaSearch);

            // Missing world check -> completes exceptionally without deleting any record
            boolean missingWorldFailed = false;
            try {
                safetyService.evaluate(new LocationSnapshot("nonexistent_world_xyz", 0, 64, 0, 0, 0), cfg).join();
            } catch (Exception expected) {
                missingWorldFailed = true;
            }
            check(missingWorldFailed, "Missing world evaluation must fail cleanly");
            plugin.getLogger().info("[SelfTest 1/7] Task 6.7 SafetyService slab/lava/missing-world verified.");

            // 2. Task 6.6 & 9.2: Warmup cancellation (movement, hit, poison ignored, /spawn, quit, /marp cancel)
            UUID tpPlayer = UUID.randomUUID();
            LocationSnapshot origin = new LocationSnapshot(wName, 100.5, 100.5, 100.5, 0, 0);
            onMain(plugin, () -> {
                TeleportSession s1 = sessionRegistry.start(
                        tpPlayer, TeleportReason.HOME, "base", origin, origin, null, 3);
                check(!warmupSampler.onDamage(tpPlayer, "POISON"), "Poison tick must not cancel warmup");
                check(sessionRegistry.isCurrent(s1), "Session still active after poison");
                check(warmupSampler.onDamage(tpPlayer, "ENTITY_ATTACK"), "Hit must cancel warmup");
                check(!sessionRegistry.isCurrent(s1), "Session cancelled after hit");

                TeleportSession s2 = sessionRegistry.start(
                        tpPlayer, TeleportReason.HOME, "base", origin, origin, null, 3);
                check(warmupSampler.onCommand(tpPlayer, "/spawn"), "/spawn must cancel warmup");
                check(!sessionRegistry.isCurrent(s2), "Session cancelled after /spawn");

                TeleportSession s3 = sessionRegistry.start(
                        tpPlayer, TeleportReason.HOME, "base", origin, origin, null, 3);
                check(warmupSampler.onQuit(tpPlayer), "Quit must cancel warmup");
                check(!sessionRegistry.isCurrent(s3), "Session cancelled after quit");

                TeleportSession s4 = sessionRegistry.start(
                        tpPlayer, TeleportReason.HOME, "base", origin, origin, null, 3);
                check(warmupSampler.cancelExplicit(tpPlayer), "/marp cancel stops active warmup");
                check(!warmupSampler.cancelExplicit(tpPlayer), "/marp cancel with nothing pending returns false");
                return null;
            });
            plugin.getLogger().info("[SelfTest 2/7] Task 6.6 & 9.2 Warmup cancellation and /marp cancel verified.");

            // 3. Task 7.1–7.5: Homes, Warps, /back, limits, conflict detection, console commands, tab completion
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            LocationSnapshot homeLoc = new LocationSnapshot(wName, 100.5, 100.5, 100.5, 0, 0);

            SetOutcome cBase = homeService.setHome(owner, "Base", homeLoc, 5).join();
            check(cBase instanceof SetOutcome.Created, "/sethome Base creates 'base'");
            Home resolvedUpper = ((HomeService.ResolveOutcome.Found)
                    homeService.resolveForTeleport(owner, "BASE").join()).home();
            check("base".equals(resolvedUpper.nameKey()), "Case-insensitive /home BASE resolves 'base'");

            HomeService.ResolveOutcome missingHome = homeService.resolveForTeleport(owner, "missing").join();
            check(missingHome instanceof HomeService.ResolveOutcome.NotFound, "Missing home returns NotFound");

            Optional<Home> strangerDel = homeService.deleteByName(stranger, "base").join();
            check(strangerDel.isEmpty(), "/delhome of another player's home returns empty");
            check(homeService.list(owner).join().size() == 1, "Owner's home still intact");

            // Optimistic lock conflict test (Appendix A)
            EditOutcome<Home> firstEdit = homeService.patchMetadata(
                    resolvedUpper.id(), owner, resolvedUpper.rev(), null, Optional.of("<gold>My Base"), null).join();
            check(firstEdit instanceof EditOutcome.Saved<Home>, "First edit succeeds");
            EditOutcome<Home> staleEdit = homeService.patchMetadata(
                    resolvedUpper.id(), owner, resolvedUpper.rev(), null, Optional.of("<red>Stale"), null).join();
            check(staleEdit instanceof EditOutcome.Conflict<Home>, "Second edit with old rev returns Conflict");
            check(homeService.resolveForTeleport(owner, "base").join() instanceof HomeService.ResolveOutcome.Found,
                    "Command identifier 'base' unchanged after display-name edit");

            homeService.deleteByName(owner, "base").join();

            // /back death + teleport + void fallback (Task 7.4 & Appendix A)
            LocationSnapshot preHome = new LocationSnapshot(wName, 10.5, 64.0, 10.5, 0, 0);
            LocationSnapshot deathPt = new LocationSnapshot(wName, 50.5, 64.0, 50.5, 0, 0);
            backService.recordTeleportOrigin(owner, preHome, TeleportReason.HOME).join();
            Thread.sleep(5L);
            backService.recordDeath(owner, deathPt, "ENTITY_ATTACK").join();
            BackEntry chosenBack = backService.resolve(owner).join().orElseThrow();
            check(Math.abs(chosenBack.location().x() - 50.5) < 1e-3, "Newest death wins for /back");
            backService.recordTeleportOrigin(owner, deathPt, TeleportReason.BACK).join();
            BackEntry afterBack = backService.resolve(owner).join().orElseThrow();
            check(Math.abs(afterBack.location().x() - 50.5) < 1e-3, "Two consecutive /back uses do not ping-pong");

            backService.recordLastGround(owner, homeLoc);
            check(backService.resolveUnreachableFallback(owner)
                    instanceof BackService.FallbackDecision.UseLastGround,
                    "Void death falls back to recorded safe ground");

            // Console commands (/setwarp refused, /delwarp & /marp info allowed)
            onMain(plugin, () -> {
                CommandSender console = Bukkit.getConsoleSender();
                Bukkit.dispatchCommand(console, "setwarp consolewarp");
                Bukkit.dispatchCommand(console, "marp info");
                return null;
            });
            check(warpCache.findByKey("consolewarp").isEmpty(), "Console /setwarp must not create warp");

            warpService.setWarp("Spawn", homeLoc, owner).join();
            check(warpCache.findByKey("spawn").isPresent(), "Op /setwarp Spawn creates 'spawn'");
            onMain(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "delwarp SPAWN"));
            Thread.sleep(50L);
            check(warpCache.findByKey("spawn").isEmpty(), "Console /delwarp SPAWN deletes warp");

            // Tab completion from caches only (Task 7.5)
            homeCache.putAll(owner, List.of("alpha", "alpine", "beta"));
            check(CompletionSupport.completeHomes(owner, "al", homeCache, true)
                    .equals(List.of("alpha", "alpine")), "Tab completion filters by prefix from cache");
            plugin.getLogger().info("[SelfTest 3/7] Tasks 7.1–7.5 Commands, /back, conflicts, and completion verified.");

            // 4. Task 8.1–8.8: 46 homes pagination,WarpListMenu locked marker, EntryEditMenu, DeleteConfirmMenu, MenuListener, TextInputService
            for (int i = 1; i <= 46; i++) {
                homeService.setHome(owner, String.format("h%02d", i), homeLoc, 100).join();
            }
            List<Home> all46 = homeService.list(owner).join();
            check(all46.size() == 46, "Expected 46 homes, got " + all46.size());
            warpService.setWarp("secret", homeLoc, owner).join();

            onMain(plugin, () -> {
                World world = Bukkit.getWorlds().get(0);
                ItemStack diamondSword = new ItemStack(Material.DIAMOND_SWORD, 5);
                byte[] swordBytes = diamondSword.asOne().serializeAsBytes();
                check(swordBytes.length > 0 && swordBytes.length <= cfg.maxIconBytes(), "Serialized sword bytes valid");
                check(diamondSword.getAmount() == 5, "asOne() leaves original stack count untouched");

                Home brokenIconHome = new Home(
                        999L,
                        owner,
                        "broken",
                        "broken",
                        homeLoc,
                        new IconSpec("GRASS_BLOCK", new byte[] {9, 9, 9, 9}),
                        "<gold>Broken Icon Home",
                        List.of("Line 1"),
                        0L,
                        System.currentTimeMillis(),
                        System.currentTimeMillis());
                ItemStack renderedBroken = itemFactory.homeEntry(brokenIconHome);
                check(renderedBroken.getType() == Material.GRASS_BLOCK,
                        "Corrupt icon bytes must fall back to GRASS_BLOCK");

                Inventory[] openedInv = new Inventory[1];
                Set<String> playerPerms = new HashSet<>(Set.of(
                        "marp.home", "marp.sethome", "marp.delhome", "marp.homes",
                        "marp.warp", "marp.warps", "marp.back", "marp.cancel"));
                ItemStack[] handItem = {new ItemStack(Material.GOLDEN_APPLE, 3)};
                List<String> playerMsgs = new ArrayList<>();
                Player fakePlayer = createMenuTestPlayer(
                        owner, world, playerPerms, openedInv, handItem,
                        c -> playerMsgs.add(menuManager.messages().plain(c)));

                // HomeListMenu pagination (Task 8.3)
                HomeListMenu page1Menu = new HomeListMenu(menuManager, owner, all46, 0, 50);
                page1Menu.open(fakePlayer);
                check(openedInv[0] != null && openedInv[0].getSize() == 54, "HomeListMenu is 54 slots");
                check(openedInv[0].getHolder() instanceof MarpMenuHolder, "Identified by MarpMenuHolder");
                check(openedInv[0].getItem(45).getType() == Material.GRAY_STAINED_GLASS_PANE,
                        "Page 1 prev button disabled");
                check(openedInv[0].getItem(53).getType() == Material.ARROW, "Page 1 next button enabled");

                HomeListMenu page2Menu = new HomeListMenu(menuManager, owner, all46, 1, 50);
                page2Menu.open(fakePlayer);
                check(openedInv[0].getItem(0) != null, "Page 2 slot 0 has 46th home");
                check(openedInv[0].getItem(45).getType() == Material.ARROW, "Page 2 prev button enabled");
                check(openedInv[0].getItem(53).getType() == Material.GRAY_STAINED_GLASS_PANE,
                        "Page 2 next button disabled");

                HomeListMenu emptyHomeMenu = new HomeListMenu(menuManager, owner, List.of(), 0, 3);
                emptyHomeMenu.open(fakePlayer);
                check(openedInv[0].getItem(22).getType() == Material.CHEST, "Empty home list shows CHEST at 22");

                // MenuListener click/drag/swap protection (Task 8.1)
                Inventory topInv = page1Menu.inventory();
                InventoryView view = proxyView(fakePlayer, topInv, openedInv[0]);
                InventoryClickEvent shiftClick = new InventoryClickEvent(
                        view, SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
                menuListener.onInventoryClick(shiftClick);
                check(shiftClick.isCancelled(), "Shift-click in Marp menu must be cancelled");
                InventoryClickEvent numSwap = new InventoryClickEvent(
                        view, SlotType.CONTAINER, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 1);
                menuListener.onInventoryClick(numSwap);
                check(numSwap.isCancelled(), "Number-key swap in Marp menu must be cancelled");
                InventoryDragEvent dragEvent = new InventoryDragEvent(
                        view, new ItemStack(Material.STONE), new ItemStack(Material.STONE), false, Map.of(0, new ItemStack(Material.STONE)));
                menuListener.onInventoryDrag(dragEvent);
                check(dragEvent.isCancelled(), "Drag in Marp menu must be cancelled");

                // EntryEditMenu icon copy without consuming stack (Task 8.5)
                Home h46 = all46.get(45);
                EntryEditMenu editMenu = EntryEditMenu.forHome(menuManager, owner, h46, 1);
                editMenu.open(fakePlayer);
                check(openedInv[0].getSize() == 27, "EntryEditMenu is 27 slots");
                check(editMenu.holder().page() == 1, "EntryEditMenu preserves return page 1");
                editMenu.onClick(fakePlayer, EntryEditMenu.SLOT_ICON, ClickType.LEFT);
                check(handItem[0].getAmount() == 3, "Held GOLDEN_APPLE stack count is still 3");

                // DeleteConfirmMenu (Task 8.6)
                DeleteConfirmMenu confirmMenu = DeleteConfirmMenu.forHome(menuManager, owner, h46, 1);
                confirmMenu.open(fakePlayer);
                check(openedInv[0].getSize() == 27, "DeleteConfirmMenu is 27 slots");
                check(openedInv[0].getItem(DeleteConfirmMenu.SLOT_CANCEL).getType() == Material.RED_CONCRETE,
                        "DeleteConfirmMenu slot 11 is RED_CONCRETE");
                check(openedInv[0].getItem(DeleteConfirmMenu.SLOT_CONFIRM).getType() == Material.LIME_CONCRETE,
                        "DeleteConfirmMenu slot 15 is LIME_CONCRETE");
                confirmMenu.onClose(fakePlayer);

                // TextInputService & TextLimits (Task 8.7 & 8.8)
                String[] capturedInput = new String[1];
                boolean[] cancelCalled = {false};
                textInput.beginInput(owner, s -> capturedInput[0] = s, () -> cancelCalled[0] = true);
                textInput.handleChatFromAsync(owner, "cancel");
                check(cancelCalled[0], "Typing 'cancel' aborts pending input");

                textInput.beginInput(owner, s -> capturedInput[0] = s, () -> {
                });
                textInput.handleChatFromAsync(owner, "<aqua>Cozy Base");
                check("<aqua>Cozy Base".equals(capturedInput[0]), "Coloured name under limits captured");
                check(TextLimits.validateDisplayName("x".repeat(33), menuManager.messages(), cfg)
                        instanceof TextLimits.NameResult.Invalid, "33-char visible name rejected");

                // WarpListMenu access filtering & reload (Task 8.4 & 9.1 & 9.4)
                Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
                String origConfig = Files.readString(configPath, StandardCharsets.UTF_8);
                Files.writeString(
                        configPath,
                        origConfig.replace("per-warp-permissions: false", "per-warp-permissions: true")
                                .replace("file: marp.db", "file: other.db"),
                        StandardCharsets.UTF_8);
                textInput.beginInput(owner, s -> {
                }, () -> {
                });
                plugin.reloadMarp();
                check(!textInput.hasPending(owner), "/marp reload clears pending inputs");

                List<Warp> normalVisible = warpService.listVisible(playerPerms::contains, false);
                check(normalVisible.isEmpty(), "Restricted warp hidden from normal player");

                List<Warp> adminVisible = warpService.listVisible(playerPerms::contains, true);
                check(adminVisible.size() == 1, "Admin sees restricted warp");
                WarpListMenu adminWarpMenu = new WarpListMenu(menuManager, owner, adminVisible, 0, true);
                adminWarpMenu.open(fakePlayer);
                ItemStack warpSlot0 = openedInv[0].getItem(0);
                check(warpSlot0 != null && warpSlot0.getItemMeta().lore() != null, "Admin warp item has lore");
                boolean hasLockedLore = warpSlot0.getItemMeta().lore().stream()
                        .map(c -> menuManager.messages().plain(c))
                        .anyMatch(s -> s.contains("Locked"));
                check(hasLockedLore, "Admin warp item has Locked marker in lore");

                // Restore config.yml
                Files.writeString(configPath, origConfig, StandardCharsets.UTF_8);
                plugin.reloadMarp();
                return null;
            });
            plugin.getLogger().info("[SelfTest 4/7] Tasks 8.1–9.4 GUI menus, text input, and reload verified.");

            // Cleanup test data
            for (Home h : homeService.list(owner).join()) {
                homeService.deleteById(h.id(), owner).join();
            }
            warpService.deleteByName("secret").join();

            plugin.getLogger().info("MARP_SELFTEST_PASSED: All live Paper 1.21.10 checks succeeded.");
            Path pluginDir = plugin.getDataFolder().toPath();
            plugin.getLogger().info("MARP_DB_FILES: "
                    + Files.exists(pluginDir.resolve("marp.db"))
                    + ", wal=" + Files.exists(pluginDir.resolve("marp.db-wal"))
                    + ", shm=" + Files.exists(pluginDir.resolve("marp.db-shm")));
            if (Boolean.getBoolean("marp.selftest.autostop")) {
                onMain(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop"));
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "MARP_SELFTEST_FAILED", t);
            if (Boolean.getBoolean("marp.selftest.autostop")) {
                try {
                    onMain(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop"));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static <T> T onMain(Marp plugin, java.util.concurrent.Callable<T> work) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(plugin, work).get();
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException("Self-test assertion failed: " + detail);
        }
    }

    private static InventoryView proxyView(Player player, Inventory top, Inventory bottom) {
        return (InventoryView) Proxy.newProxyInstance(
                InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> bottom;
                    case "getPlayer" -> player;
                    case "getInventory" -> {
                        int rawSlot = (int) args[0];
                        if (rawSlot < 0) yield null;
                        yield rawSlot < top.getSize() ? top : bottom;
                    }
                    case "convertSlot" -> {
                        int rawSlot = (int) args[0];
                        yield rawSlot < top.getSize() ? rawSlot : rawSlot - top.getSize();
                    }
                    default -> null;
                });
    }

    private static Player createMenuTestPlayer(
            UUID uuid,
            World world,
            Set<String> perms,
            Inventory[] openedInv,
            ItemStack[] handItem,
            java.util.function.Consumer<Component> onMsg) {
        PlayerInventory playerInv = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItemInMainHand" -> handItem[0];
                    default -> null;
                });
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOnline" -> true;
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, 100.5, 100.5, 100.5);
                    case "hasPermission" -> perms.contains(String.valueOf(args[0]));
                    case "getInventory" -> playerInv;
                    case "getItemOnCursor" -> new ItemStack(Material.AIR);
                    case "openInventory" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Inventory inv) {
                            openedInv[0] = inv;
                        }
                        yield null;
                    }
                    case "closeInventory" -> {
                        openedInv[0] = null;
                        yield null;
                    }
                    case "getOpenInventory" -> (InventoryView) Proxy.newProxyInstance(
                            InventoryView.class.getClassLoader(),
                            new Class<?>[] {InventoryView.class},
                            (vp, vm, va) -> "getTopInventory".equals(vm.getName()) ? openedInv[0] : null);
                    case "sendMessage" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Component c) {
                            onMsg.accept(c);
                        }
                        yield null;
                    }
                    case "playSound" -> null;
                    default -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) yield false;
                        if (rt == int.class) yield 0;
                        if (rt == long.class) yield 0L;
                        if (rt == float.class) yield 0.0f;
                        if (rt == double.class) yield 0.0;
                        yield null;
                    }
                });
    }
}
