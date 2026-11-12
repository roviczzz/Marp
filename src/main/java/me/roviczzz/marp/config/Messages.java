package me.roviczzz.marp.config;

/**
 * Every message key referenced by code. The resource test walks these constants
 * and asserts the bundled {@code messages.yml} defines each one.
 */
public final class Messages {

    private Messages() {
    }

    // general
    public static final String NO_PERMISSION = "general.no-permission";
    public static final String PLAYER_ONLY = "general.player-only";
    public static final String STORAGE_NOT_READY = "general.storage-not-ready";
    public static final String STORAGE_ERROR = "general.storage-error";
    public static final String CONFLICT = "general.conflict";
    public static final String WORLD_MISSING = "general.world-missing";
    public static final String RELOADED = "general.reloaded";
    public static final String FEATURE_DISABLED = "general.feature-disabled";
    public static final String CROSS_WORLD_DISABLED = "general.cross-world-disabled";
    public static final String INVALID_USAGE = "general.invalid-usage";
    public static final String CANCELLED = "general.cancelled";

    // home
    public static final String HOME_SET = "home.set";
    public static final String HOME_UPDATED = "home.updated";
    public static final String HOME_DELETED = "home.deleted";
    public static final String HOME_NOT_FOUND = "home.not-found";
    public static final String HOME_NONE = "home.none";
    public static final String HOME_LIMIT_REACHED = "home.limit-reached";
    public static final String HOME_LIMIT_ZERO = "home.limit-zero";
    public static final String HOME_DEFAULT_MISSING = "home.default-missing";
    public static final String HOME_NAME_INVALID = "home.name-invalid";
    public static final String HOME_NAME_TOO_LONG = "home.name-too-long";
    public static final String HOME_NAME_RESERVED = "home.name-reserved";

    // warp
    public static final String WARP_SET = "warp.set";
    public static final String WARP_UPDATED = "warp.updated";
    public static final String WARP_DELETED = "warp.deleted";
    public static final String WARP_NOT_FOUND = "warp.not-found";
    public static final String WARP_NONE = "warp.none";
    public static final String WARP_PERMISSION_DENIED = "warp.permission-denied";
    public static final String WARP_NAME_INVALID = "warp.name-invalid";

    // back
    public static final String BACK_NO_LOCATION = "back.no-location";
    public static final String BACK_ALREADY_THERE = "back.already-there";
    public static final String BACK_TARGET_DEATH = "back.target-death";
    public static final String BACK_TARGET_TELEPORT = "back.target-teleport";
    public static final String BACK_FALLBACK_LAST_GROUND = "back.fallback-last-ground";
    public static final String BACK_FALLBACK_NONE = "back.fallback-none";

    // teleport
    public static final String TELEPORT_WARMUP = "teleport.warmup";
    public static final String TELEPORT_WARMUP_ACTIONBAR = "teleport.warmup-actionbar";
    public static final String TELEPORT_WARMUP_TITLE = "teleport.warmup-title";
    public static final String TELEPORT_MOVED = "teleport.moved";
    public static final String TELEPORT_DAMAGED = "teleport.damaged";
    public static final String TELEPORT_COMMAND = "teleport.command";
    public static final String TELEPORT_TELEPORTED_ELSEWHERE = "teleport.teleported-elsewhere";
    public static final String TELEPORT_UNSAFE = "teleport.unsafe";
    public static final String TELEPORT_UNSAFE_NO_SPOT = "teleport.unsafe-no-spot";
    public static final String TELEPORT_ADJUSTED = "teleport.adjusted";
    public static final String TELEPORT_WORLD_BORDER = "teleport.world-border";
    public static final String TELEPORT_FAILED = "teleport.failed";
    public static final String TELEPORT_TELEPORTED = "teleport.teleported";
    public static final String TELEPORT_COOLDOWN = "teleport.cooldown";
    public static final String TELEPORT_SAME_WORLD = "teleport.same-world";

    // gui
    public static final String GUI_TITLE_HOMES = "gui.title-homes";
    public static final String GUI_TITLE_WARPS = "gui.title-warps";
    public static final String GUI_PAGE_INFO = "gui.page-info";
    public static final String GUI_PREV_PAGE = "gui.prev-page";
    public static final String GUI_NEXT_PAGE = "gui.next-page";
    public static final String GUI_CLOSE = "gui.close";
    public static final String GUI_SET_HOME_HERE = "gui.set-home-here";
    public static final String GUI_NO_HOMES = "gui.no-homes";
    public static final String GUI_NO_WARPS = "gui.no-warps";
    public static final String GUI_LOADING = "gui.loading";
    public static final String GUI_HOME_LORE_WORLD = "gui.home-lore-world";
    public static final String GUI_HOME_LORE_COORDS = "gui.home-lore-coords";
    public static final String GUI_HOME_LORE_CREATED = "gui.home-lore-created";
    public static final String GUI_ACTIONS = "gui.actions";
    public static final String GUI_WARP_LOCKED = "gui.warp-locked";
    public static final String GUI_WARP_HELP = "gui.warp-help";
    public static final String GUI_EDIT_NAME = "gui.edit-name";
    public static final String GUI_EDIT_NAME_LORE = "gui.edit-name-lore";
    public static final String GUI_EDIT_LORE = "gui.edit-lore";
    public static final String GUI_EDIT_LORE_LORE = "gui.edit-lore-lore";
    public static final String GUI_EDIT_ICON = "gui.edit-icon";
    public static final String GUI_EDIT_ICON_LORE = "gui.edit-icon-lore";
    public static final String GUI_EDIT_TELEPORT = "gui.edit-teleport";
    public static final String GUI_EDIT_BACK = "gui.edit-back";
    public static final String GUI_EDIT_DELETE = "gui.edit-delete";
    public static final String GUI_CONFIRM_TITLE = "gui.confirm-title";
    public static final String GUI_CONFIRM_YES = "gui.confirm-yes";
    public static final String GUI_CONFIRM_NO = "gui.confirm-no";
    public static final String GUI_CONFIRM_LORE = "gui.confirm-lore";
    public static final String GUI_DELETED = "gui.deleted";
    public static final String GUI_ICON_SET = "gui.icon-set";
    public static final String GUI_ICON_INVALID = "gui.icon-invalid";
    public static final String GUI_ICON_TOO_LARGE = "gui.icon-too-large";
    public static final String GUI_INPUT_NAME_PROMPT = "gui.input-name-prompt";
    public static final String GUI_INPUT_LORE_PROMPT = "gui.input-lore-prompt";
    public static final String GUI_INPUT_CANCELLED = "gui.input-cancelled";
    public static final String GUI_INPUT_TIMEOUT = "gui.input-timeout";
    public static final String GUI_INPUT_INVALID = "gui.input-invalid";

    // admin
    public static final String ADMIN_INFO = "admin.info";
}
