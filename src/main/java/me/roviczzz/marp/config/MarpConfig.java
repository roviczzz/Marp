package me.roviczzz.marp.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Typed, validated accessors over {@code config.yml}.
 *
 * <p>Every lookup has a documented default. Invalid values never fail startup:
 * they warn and fall back, so a typo degrades one feature instead of disabling
 * the plugin.
 */
public final class MarpConfig {

    public enum SafetyMode {
        BLOCK,
        SEARCH,
        WARN
    }

    public enum BackPreference {
        NEWEST,
        DEATH,
        TELEPORT
    }

    public enum UnreachableFallback {
        LAST_GROUND,
        BLOCK
    }

    public enum HomeNoArgPriority {
        DEFAULT_FIRST,
        FAVORITE_FIRST
    }

    private static final List<String> DEFAULT_HAZARDS = List.of(
            "LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE",
            "POWDER_SNOW", "CACTUS", "SWEET_BERRY_BUSH", "WITHER_ROSE");
    private static final List<String> DEFAULT_IGNORED_DAMAGE_CAUSES = List.of(
            "POISON", "WITHER", "FIRE_TICK", "STARVATION", "DROWNING", "CONTACT", "SUFFOCATION");

    private final ConfigurationSection root;
    private final Consumer<String> warn;
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> emittedWarnings = new LinkedHashSet<>();

    public MarpConfig(ConfigurationSection root) {
        this(root, message -> {
        });
    }

    public MarpConfig(ConfigurationSection root, Consumer<String> warn) {
        this.root = root;
        this.warn = message -> {
            if (emittedWarnings.add(message)) {
                warnings.add(message);
                warn.accept(message);
            }
        };
        // Read the values that need validation once so warnings are emitted at
        // load time rather than on first use.
        guiRows();
        safetyMode();
        backPreference();
        unreachableFallback();
        homeNoArgPriority();
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public int configVersion() {
        return integer("config-version", 1);
    }

    // ------------------------------------------------------------------ storage

    public String storageType() {
        return string("storage.type", "sqlite").toLowerCase(Locale.ROOT);
    }

    public String sqliteFile() {
        return string("storage.sqlite.file", "marp.db");
    }

    public int sqliteBusyTimeoutMs() {
        return Math.max(0, integer("storage.sqlite.busy-timeout-ms", 5000));
    }

    public boolean sqliteWal() {
        return bool("storage.sqlite.wal", true);
    }

    // -------------------------------------------------------------------- homes

    public boolean homesEnabled() {
        return bool("homes.enabled", true);
    }

    public String defaultHomeName() {
        return string("homes.default-name", "home").toLowerCase(Locale.ROOT);
    }

    public int defaultHomeLimit() {
        return Math.max(0, integer("homes.default-limit", 3));
    }

    public int maxLimitNodeScan() {
        return Math.max(1, integer("homes.max-limit-node-scan", 100));
    }

    public String homeNamePattern() {
        return string("homes.name-pattern", "^[a-z0-9_-]{1,32}$");
    }

    public boolean homesCaseInsensitive() {
        return bool("homes.case-insensitive", true);
    }

    public Set<String> reservedHomeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String raw : stringList("homes.reserved-names", List.of())) {
            names.add(raw.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    public boolean homesCrossWorld() {
        return bool("homes.cross-world", true);
    }

    // -------------------------------------------------------------------- warps

    public boolean warpsEnabled() {
        return bool("warps.enabled", true);
    }

    public String warpNamePattern() {
        return string("warps.name-pattern", "^[a-z0-9_-]{1,32}$");
    }

    public boolean warpsCaseInsensitive() {
        return bool("warps.case-insensitive", true);
    }

    public boolean perWarpPermissions() {
        return bool("warps.per-warp-permissions", false);
    }

    public boolean overwritePreservesMetadata() {
        return bool("warps.overwrite-preserves-metadata", true);
    }

    public boolean hideUnauthorizedWarps() {
        return bool("warps.hide-unauthorized", true);
    }

    // ----------------------------------------------------------------- teleport

    public int sampleIntervalTicks() {
        return Math.max(1, integer("teleport.sample-interval-ticks", 4));
    }

    public int warmupHomeSeconds() {
        return Math.max(0, integer("teleport.warmup.home", 3));
    }

    public int warmupWarpSeconds() {
        return Math.max(0, integer("teleport.warmup.warp", 3));
    }

    public int warmupBackSeconds() {
        return Math.max(0, integer("teleport.warmup.back", 3));
    }

    public int warmupTpaSeconds() {
        return Math.max(0, integer("teleport.warmup.tpa", 3));
    }

    public boolean cancelOnMove() {
        return bool("teleport.warmup.cancel-on-move", true);
    }

    public double movementTolerance() {
        return Math.max(0.0, decimal("teleport.warmup.movement-tolerance", 0.5));
    }

    public boolean cancelOnDamage() {
        return bool("teleport.warmup.cancel-on-damage", true);
    }

    public boolean cancelOnCommand() {
        return bool("teleport.warmup.cancel-on-command", true);
    }

    public Set<String> allowedCommands() {
        Set<String> commands = new LinkedHashSet<>();
        for (String raw : stringList("teleport.warmup.allowed-commands", List.of())) {
            String cleaned = raw.toLowerCase(Locale.ROOT).trim();
            while (cleaned.startsWith("/")) {
                cleaned = cleaned.substring(1);
            }
            if (!cleaned.isEmpty()) {
                commands.add(cleaned);
            }
        }
        return commands;
    }

    public boolean cancelOnTeleport() {
        return bool("teleport.warmup.cancel-on-teleport", true);
    }

    public boolean cancelOnQuit() {
        return bool("teleport.warmup.cancel-on-quit", true);
    }

    public boolean cancelOnDeath() {
        return bool("teleport.warmup.cancel-on-death", true);
    }

    public Set<String> ignoredDamageCauses() {
        Set<String> causes = new LinkedHashSet<>();
        for (String raw : stringList("teleport.warmup.ignored-damage-causes", DEFAULT_IGNORED_DAMAGE_CAUSES)) {
            causes.add(raw.toUpperCase(Locale.ROOT));
        }
        return causes;
    }

    public boolean countdownActionbar() {
        return bool("teleport.warmup.countdown-actionbar", true);
    }

    public boolean countdownSound() {
        return bool("teleport.warmup.countdown-sound", true);
    }

    public boolean titleOnStart() {
        return bool("teleport.warmup.title-on-start", false);
    }

    public int cooldownHomeSeconds() {
        return Math.max(0, integer("teleport.cooldown.home", 5));
    }

    public int cooldownWarpSeconds() {
        return Math.max(0, integer("teleport.cooldown.warp", 10));
    }

    public int cooldownBackSeconds() {
        return Math.max(0, integer("teleport.cooldown.back", 30));
    }

    public int cooldownTpaSeconds() {
        return Math.max(0, integer("teleport.cooldown.tpa", 15));
    }

    public boolean cooldownPersist() {
        return bool("teleport.cooldown.persist", false);
    }

    public boolean safetyEnabled() {
        return bool("teleport.safety.enabled", true);
    }

    public SafetyMode safetyMode() {
        return enumValue("teleport.safety.mode", SafetyMode.class, SafetyMode.SEARCH);
    }

    public int searchRadius() {
        return Math.max(0, integer("teleport.safety.search-radius", 3));
    }

    public int searchVertical() {
        return Math.max(0, integer("teleport.safety.search-vertical", 3));
    }

    public double maxFallDistance() {
        return Math.max(0.0, decimal("teleport.safety.max-fall-distance", 3.0));
    }

    public boolean allowWater() {
        return bool("teleport.safety.allow-water", true);
    }

    public boolean checkWorldBorder() {
        return bool("teleport.safety.check-world-border", true);
    }

    public boolean centerOnBlock() {
        return bool("teleport.safety.center-on-block", true);
    }

    public Set<String> hazardMaterials() {
        Set<String> hazards = new LinkedHashSet<>();
        for (String raw : stringList("teleport.safety.hazards", DEFAULT_HAZARDS)) {
            hazards.add(raw.toUpperCase(Locale.ROOT));
        }
        return hazards;
    }

    public boolean dismountVehicle() {
        return bool("teleport.dismount-vehicle", true);
    }

    public boolean closeInventory() {
        return bool("teleport.close-inventory", true);
    }

    public boolean resetFallDistance() {
        return bool("teleport.reset-fall-distance", true);
    }

    // --------------------------------------------------------------------- back

    public boolean backEnabled() {
        return bool("back.enabled", true);
    }

    public boolean recordDeaths() {
        return bool("back.record-deaths", true);
    }

    public boolean recordMarpTeleports() {
        return bool("back.record-marp-teleports", true);
    }

    public boolean recordExternalTeleports() {
        return bool("back.record-external-teleports", false);
    }

    public BackPreference backPreference() {
        return enumValue("back.prefer", BackPreference.class, BackPreference.NEWEST);
    }

    public boolean backPersist() {
        return bool("back.persist", true);
    }

    public boolean backConsume() {
        return bool("back.consume", false);
    }

    public boolean backCrossWorld() {
        return bool("back.cross-world", true);
    }

    public UnreachableFallback unreachableFallback() {
        return enumValue(
                "back.unreachable-fallback", UnreachableFallback.class, UnreachableFallback.LAST_GROUND);
    }

    // ---------------------------------------------------------------------- gui

    public int guiRows() {
        int configured = integer("gui.rows", 6);
        int clamped = Math.max(3, Math.min(6, configured));
        if (clamped != configured) {
            warn.accept("gui.rows=" + configured + " is outside 3..6; using " + clamped);
        }
        return clamped;
    }

    public String fillerMaterial() {
        return string("gui.filler-material", "GRAY_STAINED_GLASS_PANE");
    }

    public boolean guiSounds() {
        return bool("gui.sounds", true);
    }

    public boolean allowMiniMessage() {
        return bool("gui.allow-minimessage", true);
    }

    public boolean stripInteractiveTags() {
        return bool("gui.strip-interactive-tags", true);
    }

    public int maxDisplayNameLength() {
        return Math.max(1, integer("gui.max-display-name-length", 32));
    }

    public int maxLoreLines() {
        return Math.max(0, integer("gui.max-lore-lines", 4));
    }

    public int maxLoreLineLength() {
        return Math.max(1, integer("gui.max-lore-line-length", 64));
    }

    public int maxDisplayNameRaw() {
        return Math.max(1, integer("gui.max-display-name-raw", 128));
    }

    public int maxLoreLineRaw() {
        return Math.max(1, integer("gui.max-lore-line-raw", 256));
    }

    public int maxIconBytes() {
        return Math.max(1, integer("gui.max-icon-bytes", 16384));
    }

    public int confirmationSeconds() {
        return Math.max(1, integer("gui.confirmation-seconds", 10));
    }

    public int inputTimeoutSeconds() {
        return Math.max(1, integer("gui.input-timeout-seconds", 60));
    }

    // ---------------------------------------------------------------------- tpa

    public boolean tpaEnabled() {
        return bool("tpa.enabled", true);
    }

    public int tpaRequestTimeoutSeconds() {
        return Math.max(1, integer("tpa.request-timeout-seconds", 60));
    }

    public int tpaDenyCooldownSeconds() {
        return Math.max(0, integer("tpa.deny-cooldown-seconds", 30));
    }

    public int tpaMaxPendingIncoming() {
        return Math.max(1, integer("tpa.max-pending-incoming", 10));
    }

    public boolean tpaCrossWorld() {
        return bool("tpa.cross-world", true);
    }

    public int tpaCombatTagSeconds() {
        return Math.max(0, integer("tpa.combat-tag-seconds", 0));
    }

    public boolean tpaBlockIfTargetInCombat() {
        return bool("tpa.block-if-target-in-combat", true);
    }

    // ----------------------------------------------------------------- bed-home

    public boolean bedHomeEnabled() {
        return bool("bed-home.enabled", true);
    }

    public String bedHomeName() {
        return string("bed-home.name", "bed").toLowerCase(Locale.ROOT);
    }

    public boolean bedHomeCountsTowardLimit() {
        return bool("bed-home.counts-toward-limit", false);
    }

    public boolean bedHomeReserveName() {
        return bool("bed-home.reserve-name", true);
    }

    public boolean bedHomeRequireValidBed() {
        return bool("bed-home.require-valid-bed", false);
    }

    public boolean bedHomeIncludeRespawnAnchors() {
        return bool("bed-home.include-respawn-anchors", false);
    }

    public Set<String> bedHomeDisabledWorlds() {
        Set<String> worlds = new LinkedHashSet<>();
        for (String raw : stringList("bed-home.disabled-worlds", List.of())) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                worlds.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return worlds;
    }

    // ---------------------------------------------------------------- favorites

    public boolean favoritesEnabled() {
        return bool("favorites.enabled", true);
    }

    public int favoritesDefaultLimit() {
        return Math.max(0, integer("favorites.default-limit", 9));
    }

    public int favoritesMaxLimitNodeScan() {
        return Math.max(1, integer("favorites.max-limit-node-scan", 50));
    }

    public int favoritesAbsoluteMax() {
        return Math.max(1, integer("favorites.absolute-max", 45));
    }

    public HomeNoArgPriority homeNoArgPriority() {
        return enumValue(
                "favorites.home-no-arg-priority", HomeNoArgPriority.class, HomeNoArgPriority.DEFAULT_FIRST);
    }

    // --------------------------------------------------------------------- help

    public int helpEntriesPerPage() {
        return Math.max(1, integer("help.entries-per-page", 7));
    }

    // ------------------------------------------------------------------ helpers

    private String string(String path, String fallback) {
        String value = root.getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean bool(String path, boolean fallback) {
        if (!root.isSet(path)) {
            return fallback;
        }
        Object raw = root.get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("false")) {
            return Boolean.parseBoolean(text);
        }
        warn.accept(path + " is not a boolean; using " + fallback);
        return fallback;
    }

    private int integer(String path, int fallback) {
        if (!root.isSet(path)) {
            return fallback;
        }
        Object raw = root.get(path);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException exception) {
            warn.accept(path + " is not a number; using " + fallback);
            return fallback;
        }
    }

    private double decimal(String path, double fallback) {
        if (!root.isSet(path)) {
            return fallback;
        }
        Object raw = root.get(path);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException exception) {
            warn.accept(path + " is not a number; using " + fallback);
            return fallback;
        }
    }

    private List<String> stringList(String path, List<String> fallback) {
        if (!root.isSet(path)) {
            return fallback;
        }
        List<?> raw = root.getList(path);
        if (raw == null) {
            warn.accept(path + " is not a list; using the default");
            return fallback;
        }
        List<String> values = new ArrayList<>(raw.size());
        for (Object entry : raw) {
            if (entry != null) {
                values.add(String.valueOf(entry));
            }
        }
        return values;
    }

    private <E extends Enum<E>> E enumValue(String path, Class<E> type, E fallback) {
        String raw = root.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            warn.accept(path + "=" + raw + " is not a valid " + type.getSimpleName() + "; using " + fallback);
            return fallback;
        }
    }
}
