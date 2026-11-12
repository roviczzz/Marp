package me.roviczzz.marp.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.gui.MarpMenuHolder;
import me.roviczzz.marp.gui.MarpMenuHolder.MenuType;
import me.roviczzz.marp.gui.MarpMenuHolder.TargetKind;
import me.roviczzz.marp.gui.Menu;
import me.roviczzz.marp.gui.MenuListener;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.model.Home;
import me.roviczzz.marp.model.IconSpec;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.teleport.TeleportSession;
import me.roviczzz.marp.teleport.TeleportSessionRegistry;
import me.roviczzz.marp.teleport.WarmupSampler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListenerAndMenuTest {

    @TempDir
    Path tempDir;

    @Test
    void warmupDeathExternalTeleportAndConnectionListenersRouteEventsCorrectly() {
        SqlStorage storage = new SqlStorage(tempDir.resolve("listeners.db"), 5000, true);
        try {
            storage.init().join();
            YamlConfiguration yaml = new YamlConfiguration();
            UUID playerUuid = UUID.randomUUID();
            World world = proxyWorld("world");
            Location originLoc = new Location(world, 5.5, 64.0, 5.5, 0.0f, 0.0f);
            Location destLoc = new Location(world, 25.5, 64.0, 25.5, 0.0f, 0.0f);
            Player player = proxyPlayer(playerUuid, originLoc);

            HomeCache homeCache = new HomeCache();
            HomeService homeService = new HomeService(
                    storage.homes(),
                    homeCache,
                    () -> new MarpConfig(yaml),
                    NameRules::defaults,
                    System::currentTimeMillis);
            BackService backService = new BackService(
                    storage.back(),
                    () -> new MarpConfig(yaml),
                    System::currentTimeMillis);
            TeleportSessionRegistry registry = new TeleportSessionRegistry();
            List<String> cancelMessages = new ArrayList<>();

            WarmupSampler sampler = new WarmupSampler(
                    registry,
                    backService,
                    new WarmupSampler.PlayerStateProvider() {
                        @Override
                        public Collection<PlayerSample> onlinePlayers() {
                            return List.of();
                        }

                        @Override
                        public Optional<PlayerSample> sample(UUID uuid) {
                            return Optional.empty();
                        }
                    },
                    () -> new MarpConfig(yaml),
                    (u, reason) -> {
                        if (reason.messageKey() != null) {
                            cancelMessages.add(reason.messageKey());
                        }
                    },
                    (u, s) -> {
                    });

            AtomicBoolean inputCancelled = new AtomicBoolean(false);
            AtomicBoolean quitCleaned = new AtomicBoolean(false);
            WarmupListener warmupListener = new WarmupListener(sampler, u -> inputCancelled.set(true));
            DeathListener deathListener = new DeathListener(sampler, backService);
            ExternalTeleportListener externalTeleportListener =
                    new ExternalTeleportListener(registry, backService);
            PlayerConnectionListener connectionListener = new PlayerConnectionListener(
                    storage::isReady,
                    homeService,
                    homeCache,
                    backService,
                    sampler,
                    u -> quitCleaned.set(true));

            // 1. Join warms the player's home cache.
            homeService.setHome(
                    playerUuid, "base", new LocationSnapshot("world", 1, 64, 1, 0, 0), 5).join();
            homeCache.clearAll();
            assertFalse(homeCache.isLoaded(playerUuid));
            connectionListener.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
            storage.executor().submit(() -> null).join();
            assertTrue(homeCache.isLoaded(playerUuid));
            assertEquals(List.of("base"), homeCache.names(playerUuid));

            // 2. Command preprocess cancels pending input and cancels active warmup.
            LocationSnapshot snapOrigin = new LocationSnapshot("world", 5.5, 64.0, 5.5, 0, 0);
            registry.start(playerUuid, TeleportReason.HOME, "base", snapOrigin, snapOrigin, null, 3);
            warmupListener.onCommand(new PlayerCommandPreprocessEvent(player, "/spawn", Set.of()));
            assertTrue(inputCancelled.get());
            assertTrue(registry.get(playerUuid).isEmpty());
            assertTrue(cancelMessages.contains("teleport.command"));
            cancelMessages.clear();

            // 3. External teleport cancels warmup; when record-external-teleports is enabled, records origin,
            //    unless the session itself is arriving.
            yaml.set("back.record-external-teleports", true);
            registry.start(playerUuid, TeleportReason.HOME, "base", snapOrigin, snapOrigin, null, 3);
            PlayerTeleportEvent externalTp = new PlayerTeleportEvent(
                    player, originLoc, destLoc, TeleportCause.PLUGIN);
            warmupListener.onTeleport(externalTp);
            externalTeleportListener.onTeleport(externalTp);
            storage.executor().submit(() -> null).join();
            assertTrue(registry.get(playerUuid).isEmpty());
            assertTrue(cancelMessages.contains("teleport.teleported-elsewhere"));
            assertEquals(snapOrigin, backService.resolve(playerUuid).join().orElseThrow().location());

            // Session's own arrival is ignored by both WarmupListener and ExternalTeleportListener.
            TeleportSession arrivingSession = registry.start(
                    playerUuid, TeleportReason.HOME, "base", snapOrigin, snapOrigin, null, 0);
            arrivingSession.markArriving();
            Location otherOrigin = new Location(world, 99.5, 64.0, 99.5, 0.0f, 0.0f);
            PlayerTeleportEvent ownArrival = new PlayerTeleportEvent(
                    player, otherOrigin, destLoc, TeleportCause.PLUGIN);
            warmupListener.onTeleport(ownArrival);
            externalTeleportListener.onTeleport(ownArrival);
            storage.executor().submit(() -> null).join();
            assertEquals(snapOrigin, backService.resolve(playerUuid).join().orElseThrow().location());
            registry.removeIfMatch(arrivingSession);

            // 4. Death cancels warmup silently and records the death point.
            Location deathLoc = new Location(world, -42.5, 70.0, 18.5, 0.0f, 0.0f);
            Player dyingPlayer = proxyPlayer(playerUuid, deathLoc);
            registry.start(playerUuid, TeleportReason.HOME, "base", snapOrigin, snapOrigin, null, 3);
            deathListener.onDeath(new PlayerDeathEvent(
                    dyingPlayer,
                    DamageSourceProxy.none(),
                    List.of(),
                    0,
                    (String) null));
            storage.executor().submit(() -> null).join();
            assertTrue(registry.get(playerUuid).isEmpty());
            assertEquals(-42.5, backService.resolve(playerUuid).join().orElseThrow().location().x(), 1e-4);

            // 5. Quit clears cache, transient last-ground, and cancels warmup.
            backService.recordLastGround(playerUuid, snapOrigin);
            registry.start(playerUuid, TeleportReason.HOME, "base", snapOrigin, snapOrigin, null, 3);
            connectionListener.onQuit(new PlayerQuitEvent(
                    player, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
            assertFalse(homeCache.isLoaded(playerUuid));
            assertTrue(backService.lastGround(playerUuid).isEmpty());
            assertTrue(registry.get(playerUuid).isEmpty());
            assertTrue(quitCleaned.get());
        } finally {
            storage.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void menuListenerCancelsAllClicksAndDragsEnforcesOwnerAndDebouncesRapidDoubleClick() {
        long[] clock = {1_000L};
        MenuListener listener = new MenuListener(() -> clock[0]);

        UUID ownerUuid = UUID.randomUUID();
        UUID intruderUuid = UUID.randomUUID();
        AtomicBoolean intruderClosed = new AtomicBoolean(false);
        List<Integer> clickedSlots = new ArrayList<>();

        MarpMenuHolder holder = new MarpMenuHolder(
                MenuType.HOME_LIST, TargetKind.HOME, ownerUuid, 0, 0L, 0L);
        Inventory topInv = proxyInventory(holder, 54);
        Inventory bottomInv = proxyInventory(null, 36);
        Menu fakeMenu = new Menu() {
            @Override
            public MarpMenuHolder holder() {
                return holder;
            }

            @Override
            public Inventory inventory() {
                return topInv;
            }

            @Override
            public void onClick(Player player, int slot, ClickType click) {
                clickedSlots.add(slot);
            }
        };
        holder.bind(topInv, fakeMenu);

        Player owner = proxyPlayerWithClose(ownerUuid, () -> {
        });
        Player intruder = proxyPlayerWithClose(intruderUuid, () -> intruderClosed.set(true));

        // 1. Click in top inventory by owner -> cancelled and routed once; rapid second click within 200ms -> debounced.
        InventoryView ownerView = proxyView(owner, topInv, bottomInv);
        InventoryClickEvent firstClick = new InventoryClickEvent(
                ownerView, SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(firstClick);
        assertTrue(firstClick.isCancelled());
        assertEquals(List.of(0), clickedSlots);

        clock[0] += 50L;
        InventoryClickEvent rapidSecond = new InventoryClickEvent(
                ownerView, SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(rapidSecond);
        assertTrue(rapidSecond.isCancelled());
        assertEquals(List.of(0), clickedSlots);

        // 2. Click in player's own bottom inventory (rawSlot = 60) -> still cancelled, not routed to menu.
        clock[0] += 250L;
        InventoryClickEvent bottomClick = new InventoryClickEvent(
                ownerView, SlotType.CONTAINER, 60, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        listener.onInventoryClick(bottomClick);
        assertTrue(bottomClick.isCancelled());
        assertEquals(List.of(0), clickedSlots);

        // 3. Number-key swap is cancelled and ignored.
        clock[0] += 250L;
        InventoryClickEvent numberSwap = new InventoryClickEvent(
                ownerView, SlotType.CONTAINER, 2, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 1);
        listener.onInventoryClick(numberSwap);
        assertTrue(numberSwap.isCancelled());
        assertEquals(List.of(0), clickedSlots);

        // 4. Another player clicking the menu -> cancelled and closes their inventory.
        InventoryView intruderView = proxyView(intruder, topInv, bottomInv);
        InventoryClickEvent intruderClick = new InventoryClickEvent(
                intruderView, SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(intruderClick);
        assertTrue(intruderClick.isCancelled());
        assertTrue(intruderClosed.get());
        assertEquals(List.of(0), clickedSlots);
    }

    private static final class DamageSourceProxy {
        static org.bukkit.damage.DamageSource none() {
            return (org.bukkit.damage.DamageSource) Proxy.newProxyInstance(
                    org.bukkit.damage.DamageSource.class.getClassLoader(),
                    new Class<?>[] {org.bukkit.damage.DamageSource.class},
                    (proxy, method, args) -> null);
        }
    }

    private static World proxyWorld(String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private static Player proxyPlayer(UUID uuid, Location loc) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getLocation" -> loc;
                    case "getWorld" -> loc.getWorld();
                    case "isOnline" -> true;
                    default -> defaultReturn(method.getReturnType());
                });
    }

    private static Player proxyPlayerWithClose(UUID uuid, Runnable onClose) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "closeInventory" -> {
                        onClose.run();
                        yield null;
                    }
                    default -> defaultReturn(method.getReturnType());
                });
    }

    private static Inventory proxyInventory(MarpMenuHolder holder, int size) {
        return (Inventory) Proxy.newProxyInstance(
                Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHolder" -> holder;
                    case "getSize" -> size;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> defaultReturn(method.getReturnType());
                });
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
                        if (rawSlot < 0) {
                            yield null;
                        }
                        yield rawSlot < top.getSize() ? top : bottom;
                    }
                    case "convertSlot" -> {
                        int rawSlot = (int) args[0];
                        yield rawSlot < top.getSize() ? rawSlot : rawSlot - top.getSize();
                    }
                    default -> defaultReturn(method.getReturnType());
                });
    }

    private static Object defaultReturn(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0;
        }
        return null;
    }
}
