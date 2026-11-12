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
    public static final String UNKNOWN_PLAYER = "general.unknown-player";

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
    public static final String GUI_TITLE_FAVORITES = "gui.title-favorites";
    public static final String GUI_TITLE_STAR_MODE = "gui.title-star-mode";
    public static final String GUI_PAGE_INFO = "gui.page-info";
    public static final String GUI_PREV_PAGE = "gui.prev-page";
    public static final String GUI_NEXT_PAGE = "gui.next-page";
    public static final String GUI_CLOSE = "gui.close";
    public static final String GUI_SET_HOME_HERE = "gui.set-home-here";
    public static final String GUI_NO_HOMES = "gui.no-homes";
    public static final String GUI_NO_WARPS = "gui.no-warps";
    public static final String GUI_NO_FAVORITES = "gui.no-favorites";
    public static final String GUI_LOADING = "gui.loading";
    public static final String GUI_HOME_LORE_WORLD = "gui.home-lore-world";
    public static final String GUI_HOME_LORE_COORDS = "gui.home-lore-coords";
    public static final String GUI_HOME_LORE_CREATED = "gui.home-lore-created";
    public static final String GUI_HOME_LORE_BED = "gui.home-lore-bed";
    public static final String GUI_FAVORITE_BADGE = "gui.favorite-badge";
    public static final String GUI_FAVORITE_LORE = "gui.favorite-lore";
    public static final String GUI_FAVORITE_HOME_KIND = "gui.favorite-home-kind";
    public static final String GUI_FAVORITE_WARP_KIND = "gui.favorite-warp-kind";
    public static final String GUI_NAV_FAVORITES = "gui.nav-favorites";
    public static final String GUI_NAV_HOMES = "gui.nav-homes";
    public static final String GUI_NAV_WARPS = "gui.nav-warps";
    public static final String GUI_NAV_STAR_MODE_ON = "gui.nav-star-mode-on";
    public static final String GUI_NAV_STAR_MODE_OFF = "gui.nav-star-mode-off";
    public static final String GUI_NAV_STAR_MODE_LORE = "gui.nav-star-mode-lore";
    public static final String GUI_NAV_BED_ON = "gui.nav-bed-on";
    public static final String GUI_NAV_BED_OFF = "gui.nav-bed-off";
    public static final String GUI_NAV_BED_LORE = "gui.nav-bed-lore";
    public static final String GUI_NAV_FILTER = "gui.nav-filter";
    public static final String GUI_NAV_FILTER_LORE = "gui.nav-filter-lore";
    public static final String GUI_EDIT_FAVORITE_ON = "gui.edit-favorite-on";
    public static final String GUI_EDIT_FAVORITE_OFF = "gui.edit-favorite-off";
    public static final String GUI_EDIT_FAVORITE_LORE = "gui.edit-favorite-lore";
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

    // tpa
    public static final String TPA_CANNOT_REQUEST_SELF = "tpa.cannot-request-self";
    public static final String TPA_ALREADY_PENDING = "tpa.already-pending";
    public static final String TPA_TARGET_TOGGLED_OFF = "tpa.target-toggled-off";
    public static final String TPA_TARGET_BUSY = "tpa.target-busy";
    public static final String TPA_REQUEST_COOLDOWN = "tpa.request-cooldown";
    public static final String TPA_IN_COMBAT = "tpa.in-combat";
    public static final String TPA_TARGET_IN_COMBAT = "tpa.target-in-combat";
    public static final String TPA_REQUEST_SENT = "tpa.request-sent";
    public static final String TPA_REQUEST_RECEIVED_TPA = "tpa.request-received-tpa";
    public static final String TPA_REQUEST_RECEIVED_TPAHERE = "tpa.request-received-tpahere";
    public static final String TPA_BUTTON_ACCEPT = "tpa.button-accept";
    public static final String TPA_BUTTON_ACCEPT_HOVER = "tpa.button-accept-hover";
    public static final String TPA_BUTTON_DENY = "tpa.button-deny";
    public static final String TPA_BUTTON_DENY_HOVER = "tpa.button-deny-hover";
    public static final String TPA_BUTTON_CANCEL = "tpa.button-cancel";
    public static final String TPA_BUTTON_CANCEL_HOVER = "tpa.button-cancel-hover";
    public static final String TPA_NONE_PENDING = "tpa.none-pending";
    public static final String TPA_NO_PENDING_FROM = "tpa.no-pending-from";
    public static final String TPA_AMBIGUOUS_HEADER = "tpa.ambiguous-header";
    public static final String TPA_AMBIGUOUS_ENTRY = "tpa.ambiguous-entry";
    public static final String TPA_ACCEPTED_SENDER = "tpa.accepted-sender";
    public static final String TPA_ACCEPTED_TARGET = "tpa.accepted-target";
    public static final String TPA_DENIED_SENDER = "tpa.denied-sender";
    public static final String TPA_DENIED_TARGET = "tpa.denied-target";
    public static final String TPA_CANCELLED_SENDER = "tpa.cancelled-sender";
    public static final String TPA_CANCELLED_TARGET = "tpa.cancelled-target";
    public static final String TPA_CANCELLED_OFFLINE = "tpa.cancelled-offline";
    public static final String TPA_EXPIRED_SENDER = "tpa.expired-sender";
    public static final String TPA_EXPIRED_TARGET = "tpa.expired-target";
    public static final String TPA_TARGET_OFFLINE = "tpa.target-offline";
    public static final String TPA_TARGET_DIED = "tpa.target-died";
    public static final String TPA_TOGGLE_ON = "tpa.toggle-on";
    public static final String TPA_TOGGLE_OFF = "tpa.toggle-off";

    // bed
    public static final String BED_SET = "bed.set";
    public static final String BED_UPDATED = "bed.updated";
    public static final String BED_MISSING_OR_OBSTRUCTED = "bed.missing-or-obstructed";
    public static final String BED_TOGGLE_ON = "bed.toggle-on";
    public static final String BED_TOGGLE_OFF = "bed.toggle-off";

    // favorites
    public static final String FAVORITES_ADDED = "favorites.added";
    public static final String FAVORITES_REMOVED = "favorites.removed";
    public static final String FAVORITES_LIMIT_REACHED = "favorites.limit-reached";
    public static final String FAVORITES_LIMIT_ZERO = "favorites.limit-zero";
    public static final String FAVORITES_NONE = "favorites.none";
    public static final String FAVORITES_INVALID_TARGET = "favorites.invalid-target";

    // help
    public static final String HELP_HEADER = "help.header";
    public static final String HELP_CATEGORY_HEADER = "help.category-header";
    public static final String HELP_CATEGORY_CONT = "help.category-cont";
    public static final String HELP_ENTRY = "help.entry";
    public static final String HELP_HOVER = "help.hover";
    public static final String HELP_HOVER_ADMIN_PERM = "help.hover-admin-perm";
    public static final String HELP_HOVER_CLICK_RUN = "help.hover-click-run";
    public static final String HELP_HOVER_CLICK_SUGGEST = "help.hover-click-suggest";
    public static final String HELP_FOOTER = "help.footer";
    public static final String HELP_PREV = "help.prev";
    public static final String HELP_PREV_DISABLED = "help.prev-disabled";
    public static final String HELP_PREV_HOVER = "help.prev-hover";
    public static final String HELP_NEXT = "help.next";
    public static final String HELP_NEXT_DISABLED = "help.next-disabled";
    public static final String HELP_NEXT_HOVER = "help.next-hover";
    public static final String HELP_NONE_AVAILABLE = "help.none-available";
    public static final String HELP_CAT_HOMES = "help.category.homes";
    public static final String HELP_CAT_WARPS = "help.category.warps";
    public static final String HELP_CAT_TELEPORT = "help.category.teleport";
    public static final String HELP_CAT_GENERAL_ADMIN = "help.category.general-admin";
    public static final String HELP_CMD_HOME = "help.cmd.home";
    public static final String HELP_CMD_SETHOME = "help.cmd.sethome";
    public static final String HELP_CMD_DELHOME = "help.cmd.delhome";
    public static final String HELP_CMD_HOMES = "help.cmd.homes";
    public static final String HELP_CMD_BEDTOGGLE = "help.cmd.bedtoggle";
    public static final String HELP_CMD_WARP = "help.cmd.warp";
    public static final String HELP_CMD_WARPS = "help.cmd.warps";
    public static final String HELP_CMD_SETWARP = "help.cmd.setwarp";
    public static final String HELP_CMD_DELWARP = "help.cmd.delwarp";
    public static final String HELP_CMD_TPA = "help.cmd.tpa";
    public static final String HELP_CMD_TPAHERE = "help.cmd.tpahere";
    public static final String HELP_CMD_TPACCEPT = "help.cmd.tpaccept";
    public static final String HELP_CMD_TPDENY = "help.cmd.tpdeny";
    public static final String HELP_CMD_TPCANCEL = "help.cmd.tpcancel";
    public static final String HELP_CMD_TPTOGGLE = "help.cmd.tptoggle";
    public static final String HELP_CMD_BACK = "help.cmd.back";
    public static final String HELP_CMD_FAVORITES = "help.cmd.favorites";
    public static final String HELP_CMD_MARP_HELP = "help.cmd.marp-help";
    public static final String HELP_CMD_MARP_CANCEL = "help.cmd.marp-cancel";
    public static final String HELP_CMD_MARP_BED = "help.cmd.marp-bed";
    public static final String HELP_CMD_MARP_INFO = "help.cmd.marp-info";
    public static final String HELP_CMD_MARP_RELOAD = "help.cmd.marp-reload";
}
