package me.roviczzz.marp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import me.roviczzz.marp.back.BackService;
import me.roviczzz.marp.command.BackCommand;
import me.roviczzz.marp.command.CommandSupport;
import me.roviczzz.marp.command.DelHomeCommand;
import me.roviczzz.marp.command.DelWarpCommand;
import me.roviczzz.marp.command.HomeCommand;
import me.roviczzz.marp.command.HomesCommand;
import me.roviczzz.marp.command.MarpCommand;
import me.roviczzz.marp.command.SetHomeCommand;
import me.roviczzz.marp.command.SetWarpCommand;
import me.roviczzz.marp.command.WarpCommand;
import me.roviczzz.marp.command.WarpsCommand;
import me.roviczzz.marp.config.ConfigLoader;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.gui.GuiItemFactory;
import me.roviczzz.marp.gui.MenuListener;
import me.roviczzz.marp.gui.MenuManager;
import me.roviczzz.marp.home.HomeCache;
import me.roviczzz.marp.home.HomeService;
import me.roviczzz.marp.input.ChatTextInput;
import me.roviczzz.marp.input.TextInputService;
import me.roviczzz.marp.listener.DeathListener;
import me.roviczzz.marp.listener.ExternalTeleportListener;
import me.roviczzz.marp.listener.InputListener;
import me.roviczzz.marp.listener.PlayerConnectionListener;
import me.roviczzz.marp.listener.WarmupListener;
import me.roviczzz.marp.model.NameRules;
import me.roviczzz.marp.storage.SqlStorage;
import me.roviczzz.marp.teleport.BukkitTeleportPlatform;
import me.roviczzz.marp.teleport.CooldownService;
import me.roviczzz.marp.teleport.SafetyService;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.teleport.TeleportSessionRegistry;
import me.roviczzz.marp.teleport.WarmupSampler;
import me.roviczzz.marp.util.Scheduler;
import me.roviczzz.marp.warp.WarpCache;
import me.roviczzz.marp.warp.WarpService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Marp plugin entrypoint.
 *
 * <p>Wires configuration, SQLite storage, services, menus, listeners, and
 * commands per DESIGN.md §2.3. No feature logic lives in this class.
 */
public final class Marp extends JavaPlugin {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private volatile MarpConfig config;
    private volatile MessageService messages;
    private volatile NameRules homeNameRules;
    private volatile NameRules warpNameRules;

    private String initialStorageType;
    private String initialSqliteFile;

    private Scheduler scheduler;
    private SqlStorage storage;
    private HomeCache homeCache;
    private WarpCache warpCache;
    private HomeService homeService;
    private WarpService warpService;
    private BackService backService;
    private TeleportSessionRegistry sessionRegistry;
    private CooldownService cooldownService;
    private TeleportService teleportService;
    private WarmupSampler warmupSampler;
    private Scheduler.Cancellable samplerTask;
    private TextInputService textInputService;
    private MenuManager menuManager;
    private MenuListener menuListener;

    @Override
    public void onEnable() {
        this.scheduler = Scheduler.bukkit(this);
        loadConfigurationFiles(true);

        this.initialStorageType = config.storageType();
        this.initialSqliteFile = config.sqliteFile();

        Path dbFile = getDataFolder().toPath().resolve(config.sqliteFile());
        this.storage = new SqlStorage(dbFile, config.sqliteBusyTimeoutMs(), config.sqliteWal());

        this.homeCache = new HomeCache();
        this.warpCache = new WarpCache();
        this.homeService = new HomeService(
                storage.homes(),
                homeCache,
                () -> config,
                () -> homeNameRules,
                System::currentTimeMillis);
        this.warpService = new WarpService(
                storage.warps(),
                warpCache,
                () -> config,
                () -> warpNameRules,
                System::currentTimeMillis);
        this.backService = new BackService(
                storage.back(),
                () -> config,
                System::currentTimeMillis);

        this.sessionRegistry = new TeleportSessionRegistry();
        this.cooldownService = new CooldownService(System::currentTimeMillis);
        SafetyService safetyService = new SafetyService(scheduler);

        BukkitTeleportPlatform platform = new BukkitTeleportPlatform(
                safetyService,
                () -> messages,
                uuid -> {
                    if (menuManager != null) {
                        menuManager.closeMarpMenuIfOpen(uuid);
                    }
                });

        this.teleportService = new TeleportService(
                sessionRegistry,
                cooldownService,
                backService,
                platform,
                scheduler,
                () -> config);

        this.warmupSampler = new WarmupSampler(
                sessionRegistry,
                backService,
                platform,
                () -> config,
                (uuid, reason) -> {
                    if (reason.messageKey() != null) {
                        platform.sendMessage(uuid, reason.messageKey(), Map.of());
                    }
                    platform.clearActionBar(uuid);
                },
                (uuid, seconds) -> platform.sendCountdown(uuid, seconds, config));

        this.textInputService = new ChatTextInput(scheduler, () -> config, () -> messages);
        GuiItemFactory itemFactory = new GuiItemFactory(
                () -> config,
                () -> messages,
                msg -> getLogger().warning(msg));
        this.menuManager = new MenuManager(
                homeService,
                warpService,
                teleportService,
                textInputService,
                itemFactory,
                scheduler,
                () -> config,
                () -> messages);
        this.menuListener = new MenuListener(System::currentTimeMillis);

        scheduleWarmupSampler();
        registerListeners();
        registerCommands();

        storage.init().whenComplete((unused, error) -> scheduler.runOnMain(() -> {
            if (!isEnabled()) {
                return;
            }
            if (error != null) {
                getLogger().log(
                        Level.SEVERE,
                        "Failed to initialize Marp database at " + storage.databaseFile() + "; disabling plugin.",
                        error);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            warpService.refreshCache().whenComplete((warps, warpErr) -> scheduler.runOnMain(() -> {
                if (warpErr != null) {
                    getLogger().log(Level.WARNING, "Failed to load initial warp cache", warpErr);
                } else {
                    getLogger().info("Marp storage ready (sqlite: " + storage.databaseFile()
                            + ", " + (warps == null ? 0 : warps.size()) + " warp(s) cached).");
                }
            }));
            for (Player online : Bukkit.getOnlinePlayers()) {
                homeService.loadCache(online.getUniqueId());
            }
            if (Boolean.getBoolean("marp.selftest")) {
                LiveServerSelfTest.run(
                        this,
                        scheduler,
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
                        textInputService);
            }
        }));
    }

    @Override
    public void onDisable() {
        if (samplerTask != null) {
            samplerTask.cancel();
            samplerTask = null;
        }
        if (sessionRegistry != null) {
            sessionRegistry.cancelAllSilently();
        }
        if (textInputService != null) {
            textInputService.cancelAllWithNotice();
        }
        if (menuManager != null) {
            menuManager.closeAllMarpMenus();
        }
        if (storage != null) {
            boolean clean = storage.shutdown(SHUTDOWN_TIMEOUT);
            if (!clean) {
                getLogger().warning("Timed out waiting for Marp-DB to drain pending writes during shutdown.");
            } else {
                getLogger().info("Marp storage checkpointed and closed cleanly.");
            }
        }
    }

    public void reloadMarp() {
        loadConfigurationFiles(false);
        if (!config.storageType().equalsIgnoreCase(initialStorageType)
                || !config.sqliteFile().equals(initialSqliteFile)) {
            getLogger().warning(
                    "Storage settings in config.yml changed; a full server restart is required to reconnect.");
        }
        sessionRegistry.cancelAllSilently();
        textInputService.cancelAllWithNotice();
        menuManager.closeAllMarpMenus();
        scheduleWarmupSampler();
        if (storage.isReady()) {
            warpService.refreshCache();
        }
    }

    private void loadConfigurationFiles(boolean firstLoad) {
        Path dataDir = getDataFolder().toPath();
        YamlConfiguration rawConfig = ConfigLoader.load(
                dataDir, "config.yml", msg -> getLogger().warning(msg));
        YamlConfiguration rawMessages = ConfigLoader.load(
                dataDir, "messages.yml", msg -> getLogger().warning(msg));

        MarpConfig parsedConfig = new MarpConfig(rawConfig, msg -> getLogger().warning(msg));
        MessageService parsedMessages = new MessageService(
                rawMessages, parsedConfig.allowMiniMessage(), msg -> getLogger().warning(msg));

        this.config = parsedConfig;
        this.messages = parsedMessages;
        this.homeNameRules = compileNameRules(
                parsedConfig.homeNamePattern(), parsedConfig.reservedHomeNames());
        this.warpNameRules = compileNameRules(parsedConfig.warpNamePattern(), Set.of());
    }

    private NameRules compileNameRules(String rawPattern, Set<String> reserved) {
        try {
            return new NameRules(
                    Pattern.compile(rawPattern), NameRules.DEFAULT_MAX_LENGTH, reserved);
        } catch (PatternSyntaxException exception) {
            getLogger().warning(
                    "Invalid name-pattern '" + rawPattern + "'; falling back to " + NameRules.DEFAULT_PATTERN);
            return new NameRules(
                    Pattern.compile(NameRules.DEFAULT_PATTERN), NameRules.DEFAULT_MAX_LENGTH, reserved);
        }
    }

    private void scheduleWarmupSampler() {
        if (samplerTask != null) {
            samplerTask.cancel();
        }
        int interval = config.sampleIntervalTicks();
        this.samplerTask = scheduler.runTimer(
                () -> warmupSampler.tick(interval), interval, interval);
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(menuListener, this);
        pm.registerEvents(new InputListener(textInputService), this);
        pm.registerEvents(
                new WarmupListener(warmupSampler, uuid -> textInputService.cancelWithNotice(uuid)),
                this);
        pm.registerEvents(new DeathListener(warmupSampler, backService), this);
        pm.registerEvents(new ExternalTeleportListener(sessionRegistry, backService), this);
        pm.registerEvents(
                new PlayerConnectionListener(
                        storage::isReady,
                        homeService,
                        homeCache,
                        backService,
                        warmupSampler,
                        uuid -> {
                            textInputService.clearSilent(uuid);
                            menuListener.clearPlayer(uuid);
                        }),
                this);
    }

    private void registerCommands() {
        CommandSupport support = new CommandSupport(
                storage::isReady, () -> config, () -> messages);

        bindCommand("sethome", new SetHomeCommand(
                support, homeService, homeCache, menuManager, scheduler));
        bindCommand("home", new HomeCommand(
                support, homeService, homeCache, teleportService, menuManager, scheduler));
        bindCommand("delhome", new DelHomeCommand(
                support, homeService, homeCache, scheduler));
        bindCommand("homes", new HomesCommand(support, menuManager));

        bindCommand("setwarp", new SetWarpCommand(support, warpService, scheduler));
        bindCommand("warp", new WarpCommand(
                support, warpService, warpCache, teleportService, menuManager, scheduler));
        bindCommand("delwarp", new DelWarpCommand(
                support, warpService, warpCache, scheduler));
        bindCommand("warps", new WarpsCommand(support, menuManager));

        bindCommand("back", new BackCommand(
                support, backService, teleportService, scheduler));
        bindCommand("marp", new MarpCommand(
                support,
                getPluginMeta().getVersion(),
                storage,
                homeCache,
                warpCache,
                warmupSampler,
                this::reloadMarp,
                scheduler));
    }

    private <T extends CommandExecutor & TabCompleter> void bindCommand(String name, T handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command /" + name + " is missing from plugin.yml");
            return;
        }
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
    }
}
