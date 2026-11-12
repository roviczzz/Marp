package me.roviczzz.marp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.gui.GuiItemFactory;
import me.roviczzz.marp.gui.MenuManager;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.input.ChatTextInput;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.teleport.CooldownService;
import me.roviczzz.marp.teleport.SafetyResult;
import me.roviczzz.marp.teleport.TeleportPlatform;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.teleport.TeleportSessionRegistry;
import me.roviczzz.marp.teleport.WarmupSampler;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandAndAdminTest {

    @TempDir
    Path tempDir;

    @Test
    void commandsEnforceStorageReadinessPlayerOnlyPermissionsFeatureSwitchesAndCrud() {
        SqlStorage storage = new SqlStorage(tempDir.resolve("cmd.db"), 5000, true);
        try {
            YamlConfiguration configYaml = new YamlConfiguration();
            configYaml.set("teleport.warmup.home", 0);
            configYaml.set("teleport.warmup.warp", 0);
            configYaml.set("teleport.warmup.back", 0);
            configYaml.set("teleport.cooldown.home", 0);
            configYaml.set("teleport.cooldown.warp", 0);
            configYaml.set("teleport.cooldown.back", 0);

            MessageService messages = loadBundledMessages();
            AtomicBoolean readyFlag = new AtomicBoolean(false);
            Scheduler scheduler = Scheduler.direct();

            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
            HomeCache homeCache = new HomeCache();
            WarpCache warpCache = new WarpCache();
            HomeService homeService = new HomeService(
                    storage.homes(),
                    homeCache,
                    () -> new MarpConfig(configYaml),
                    NameRules::defaults,
                    clock::incrementAndGet);
            WarpService warpService = new WarpService(
                    storage.warps(),
                    warpCache,
                    () -> new MarpConfig(configYaml),
                    NameRules::defaults,
                    clock::incrementAndGet);
            BackService backService = new BackService(
                    storage.back(),
                    () -> new MarpConfig(configYaml),
                    clock::incrementAndGet);

            List<String> sentPlain = java.util.Collections.synchronizedList(new ArrayList<>());
            UUID playerUuid = UUID.randomUUID();
            Set<String> perms = new HashSet<>(Set.of(
                    "marp.home", "marp.sethome", "marp.delhome", "marp.homes",
                    "marp.warp", "marp.warps", "marp.back", "marp.cancel"));

            World fakeWorld = proxyWorld("world");
            Location[] currentLoc = {new Location(fakeWorld, 10.5, 64.0, 10.5, 90.0f, 0.0f)};
            Player player = proxyPlayer(playerUuid, perms, currentLoc, c -> sentPlain.add(messages.plain(c)));
            CommandSender console = proxyConsole(c -> sentPlain.add(messages.plain(c)));

            TeleportSessionRegistry registry = new TeleportSessionRegistry();
            CooldownService cooldowns = new CooldownService(System::currentTimeMillis);
            TeleportPlatform platform = new TeleportPlatform() {
                @Override
                public java.util.Optional<PlayerContext> player(UUID uuid) {
                    return java.util.Optional.of(new PlayerContext(
                            playerUuid,
                            new LocationSnapshot(
                                    "world",
                                    currentLoc[0].getX(),
                                    currentLoc[0].getY(),
                                    currentLoc[0].getZ(),
                                    currentLoc[0].getYaw(),
                                    currentLoc[0].getPitch()),
                            false,
                            false,
                            false));
                }

                @Override
                public boolean isWorldLoaded(String worldName) {
                    return "world".equals(worldName);
                }

                @Override
                public java.util.concurrent.CompletableFuture<SafetyResult> evaluateSafety(
                        LocationSnapshot target, MarpConfig config) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new SafetyResult.Safe(target));
                }

                @Override
                public void preparePlayerForArrival(UUID uuid, MarpConfig config) {
                }

                @Override
                public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(
                        UUID uuid, LocationSnapshot target) {
                    currentLoc[0] = new Location(
                            fakeWorld, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
                    return java.util.concurrent.CompletableFuture.completedFuture(true);
                }

                @Override
                public void sendMessage(UUID uuid, String key, java.util.Map<String, String> ph) {
                    sentPlain.add(messages.plain(messages.component(key, ph)));
                }

                @Override
                public void sendCountdown(UUID uuid, int seconds, MarpConfig config) {
                }

                @Override
                public void clearActionBar(UUID uuid) {
                }

                @Override
                public void playArrivalEffects(UUID uuid, MarpConfig config) {
                }

                @Override
                public void closeMarpMenuIfOpen(UUID uuid) {
                }
            };

            TeleportService teleportService = new TeleportService(
                    registry, cooldowns, backService, platform, scheduler, () -> new MarpConfig(configYaml));
            WarmupSampler sampler = new WarmupSampler(
                    registry,
                    backService,
                    new WarmupSampler.PlayerStateProvider() {
                        @Override
                        public java.util.Collection<PlayerSample> onlinePlayers() {
                            return List.of();
                        }

                        @Override
                        public java.util.Optional<PlayerSample> sample(UUID uuid) {
                            return java.util.Optional.empty();
                        }
                    },
                    () -> new MarpConfig(configYaml),
                    (u, r) -> {
                        if (r.messageKey() != null) {
                            platform.sendMessage(u, r.messageKey(), java.util.Map.of());
                        }
                    },
                    (u, s) -> {
                    });

            ChatTextInput textInput = new ChatTextInput(
                    scheduler, () -> new MarpConfig(configYaml), (u, k) -> sentPlain.add(k));
            GuiItemFactory itemFactory = new GuiItemFactory(
                    () -> new MarpConfig(configYaml), () -> messages, ignored -> {
                    });
            MenuManager menuManager = new MenuManager(
                    homeService,
                    warpService,
                    teleportService,
                    textInput,
                    itemFactory,
                    scheduler,
                    () -> new MarpConfig(configYaml),
                    () -> messages);
            CommandSupport support = new CommandSupport(
                    readyFlag::get,
                    () -> new MarpConfig(configYaml),
                    () -> messages,
                    uuid -> uuid.equals(playerUuid) ? player : null);

            SetHomeCommand setHomeCmd = new SetHomeCommand(
                    support, homeService, homeCache, menuManager, scheduler);
            HomeCommand homeCmd = new HomeCommand(
                    support, homeService, homeCache, teleportService, menuManager, scheduler);
            DelHomeCommand delHomeCmd = new DelHomeCommand(
                    support, homeService, homeCache, scheduler);
            SetWarpCommand setWarpCmd = new SetWarpCommand(support, warpService, scheduler);
            WarpCommand warpCmd = new WarpCommand(
                    support, warpService, warpCache, teleportService, menuManager, scheduler);
            DelWarpCommand delWarpCmd = new DelWarpCommand(
                    support, warpService, warpCache, scheduler);
            BackCommand backCmd = new BackCommand(
                    support, backService, teleportService, scheduler);
            AtomicBoolean reloaded = new AtomicBoolean(false);
            MarpCommand marpCmd = new MarpCommand(
                    support,
                    "1.0-SNAPSHOT",
                    storage,
                    homeCache,
                    warpCache,
                    sampler,
                    () -> reloaded.set(true),
                    scheduler);

            Command dummy = dummyCommand();

            // 1. Before storage is ready -> storage-not-ready.
            setHomeCmd.onCommand(player, dummy, "sethome", new String[] {"base"});
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("Storage is still starting")), sentPlain.toString());
            sentPlain.clear();

            // Initialize storage and mark ready.
            storage.init().join();
            readyFlag.set(true);

            // 2. Console /setwarp is refused as player-only; non-op player /setwarp is denied.
            setWarpCmd.onCommand(console, dummy, "setwarp", new String[] {"spawn"});
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("only be used by a player")));
            sentPlain.clear();

            setWarpCmd.onCommand(player, dummy, "setwarp", new String[] {"spawn"});
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("do not have permission")));
            sentPlain.clear();

            // 3. Grant admin perms -> create warp, then console /delwarp deletes it.
            perms.add("marp.admin.setwarp");
            setWarpCmd.onCommand(player, dummy, "setwarp", new String[] {"Spawn"});
            awaitMessage(sentPlain, "Warp spawn created");
            assertEquals(1, storage.warps().count().join());
            assertTrue(warpCache.findByKey("spawn").isPresent());
            sentPlain.clear();

            delWarpCmd.onCommand(console, dummy, "delwarp", new String[] {"SPAWN"});
            awaitMessage(sentPlain, "Warp spawn deleted");
            assertEquals(0, storage.warps().count().join());
            assertTrue(warpCache.findByKey("spawn").isEmpty());
            sentPlain.clear();

            // 4. Feature switch: disable homes -> /sethome & /home report feature disabled without losing data.
            setHomeCmd.onCommand(player, dummy, "sethome", new String[] {"Base"});
            awaitMessage(sentPlain, "Home base set");
            assertEquals(1, storage.homes().countByPlayer(playerUuid).join());
            sentPlain.clear();

            configYaml.set("homes.enabled", false);
            homeCmd.onCommand(player, dummy, "home", new String[] {"base"});
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("feature is disabled")));
            assertEquals(1, storage.homes().countByPlayer(playerUuid).join());
            configYaml.set("homes.enabled", true);
            sentPlain.clear();

            // 5. /marp info, /marp reload, /marp cancel (with nothing pending -> safe no-op).
            perms.add("marp.admin.info");
            perms.add("marp.admin.reload");
            marpCmd.onCommand(player, dummy, "marp", new String[] {"info"});
            awaitMessage(sentPlain, "1.0-SNAPSHOT");
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("1.0-SNAPSHOT") && s.contains("homes 1")));
            sentPlain.clear();

            marpCmd.onCommand(player, dummy, "marp", new String[] {"reload"});
            assertTrue(reloaded.get());
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("reloaded")));
            sentPlain.clear();

            marpCmd.onCommand(player, dummy, "marp", new String[] {"cancel"});
            assertTrue(sentPlain.stream().anyMatch(s -> s.contains("Cancelled")));
            sentPlain.clear();

            // 6. Case-insensitive /home BASE, missing home not-found, and stranger /delhome isolation.
            currentLoc[0] = new Location(fakeWorld, 50.5, 64.0, 50.5, 0.0f, 0.0f);
            homeCmd.onCommand(player, dummy, "home", new String[] {"BASE"});
            awaitMessage(sentPlain, "Teleported to base");
            // Drain the async recordTeleportOrigin write on Marp-DB.
            storage.executor().submit(() -> null).join();
            assertEquals(10.5, currentLoc[0].getX(), 1e-4);
            sentPlain.clear();

            homeCmd.onCommand(player, dummy, "home", new String[] {"nonexistent"});
            awaitMessage(sentPlain, "No home named nonexistent");
            sentPlain.clear();

            UUID strangerUuid = UUID.randomUUID();
            Player stranger = proxyPlayer(strangerUuid, perms, currentLoc, c -> sentPlain.add(messages.plain(c)));
            delHomeCmd.onCommand(stranger, dummy, "delhome", new String[] {"base"});
            assertEquals(1, storage.homes().countByPlayer(playerUuid).join());

            // 7. /back: after /home teleport, /back returns to pre-teleport location (50.5, 64, 50.5);
            //    using /back again at that location short-circuits with already-there (no ping-pong).
            backCmd.onCommand(player, dummy, "back", new String[0]);
            awaitMessage(sentPlain, "Teleported to back");
            assertEquals(50.5, currentLoc[0].getX(), 1e-4);
            sentPlain.clear();

            backCmd.onCommand(player, dummy, "back", new String[0]);
            awaitMessage(sentPlain, "already there");
            assertEquals(50.5, currentLoc[0].getX(), 1e-4);
            sentPlain.clear();

            // Record a newer death at (99.5, 64, 99.5) -> /back now goes to the death point,
            // and a second /back after moving away still targets (99.5, 64, 99.5) (never ping-pongs).
            backService.recordDeath(
                    playerUuid, new LocationSnapshot("world", 99.5, 64.0, 99.5, 0, 0), "LAVA").join();
            backCmd.onCommand(player, dummy, "back", new String[0]);
            awaitMessage(sentPlain, "Teleported to back");
            assertEquals(99.5, currentLoc[0].getX(), 1e-4);
            sentPlain.clear();

            currentLoc[0] = new Location(fakeWorld, 0.5, 64.0, 0.5, 0.0f, 0.0f);
            backCmd.onCommand(player, dummy, "back", new String[0]);
            awaitMessage(sentPlain, "Teleported to back");
            assertEquals(99.5, currentLoc[0].getX(), 1e-4);
            sentPlain.clear();

            // 8. /warp access-denied hide vs visible denial.
            setWarpCmd.onCommand(player, dummy, "setwarp", new String[] {"vip"});
            awaitMessage(sentPlain, "Warp vip created");
            sentPlain.clear();

            configYaml.set("warps.per-warp-permissions", true);
            configYaml.set("warps.hide-unauthorized", true);
            warpCmd.onCommand(player, dummy, "warp", new String[] {"vip"});
            awaitMessage(sentPlain, "No warp named vip");
            sentPlain.clear();

            configYaml.set("warps.hide-unauthorized", false);
            warpCmd.onCommand(player, dummy, "warp", new String[] {"vip"});
            awaitMessage(sentPlain, "do not have access");
        } finally {
            storage.shutdown(Duration.ofSeconds(5));
        }
    }

    private static void awaitMessage(List<String> sentPlain, String expectedSubstring) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (sentPlain) {
                for (String msg : sentPlain) {
                    if (msg.contains(expectedSubstring)) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
                "Timed out waiting for message containing '" + expectedSubstring + "', got: " + sentPlain);
    }

    private static MessageService loadBundledMessages() {
        try (InputStreamReader reader = new InputStreamReader(
                CommandAndAdminTest.class.getResourceAsStream("/messages.yml"),
                StandardCharsets.UTF_8)) {
            return new MessageService(
                    YamlConfiguration.loadConfiguration(reader), true, ignored -> {
                    });
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Command dummyCommand() {
        return new Command("test") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return true;
            }
        };
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

    private static Player proxyPlayer(
            UUID uuid,
            Set<String> permissions,
            Location[] currentLocation,
            java.util.function.Consumer<Component> onMessage) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOnline" -> true;
                    case "isDead" -> false;
                    case "getLocation" -> currentLocation[0];
                    case "getWorld" -> currentLocation[0].getWorld();
                    case "hasPermission" -> permissions.contains(String.valueOf(args[0]));
                    case "sendMessage" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                            onMessage.accept(comp);
                        }
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> uuid.hashCode();
                    default -> defaultReturn(method.getReturnType());
                });
    }

    private static CommandSender proxyConsole(java.util.function.Consumer<Component> onMessage) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                            onMessage.accept(comp);
                        }
                        yield null;
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
