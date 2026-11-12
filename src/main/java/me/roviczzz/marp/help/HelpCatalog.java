package me.roviczzz.marp.help;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.Messages;

/** Declarative registry of all Marp commands and their help metadata. */
public final class HelpCatalog {

    private static final List<HelpEntry> ENTRIES = List.of(
            // Homes
            new HelpEntry(
                    HelpCategory.HOMES,
                    "/home [name]",
                    Messages.HELP_CMD_HOME,
                    "/home base",
                    "marp.home",
                    MarpConfig::homesEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/home "),
            new HelpEntry(
                    HelpCategory.HOMES,
                    "/sethome [name]",
                    Messages.HELP_CMD_SETHOME,
                    "/sethome base",
                    "marp.sethome",
                    MarpConfig::homesEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/sethome "),
            new HelpEntry(
                    HelpCategory.HOMES,
                    "/delhome [name]",
                    Messages.HELP_CMD_DELHOME,
                    "/delhome base",
                    "marp.delhome",
                    MarpConfig::homesEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/delhome "),
            new HelpEntry(
                    HelpCategory.HOMES,
                    "/homes",
                    Messages.HELP_CMD_HOMES,
                    "/homes",
                    "marp.homes",
                    MarpConfig::homesEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/homes"),
            new HelpEntry(
                    HelpCategory.HOMES,
                    "/bedtoggle [on|off]",
                    Messages.HELP_CMD_BEDTOGGLE,
                    "/bedtoggle on",
                    "marp.bedhome.toggle",
                    cfg -> cfg.homesEnabled() && cfg.bedHomeEnabled(),
                    HelpClickMode.SUGGEST_COMMAND,
                    "/bedtoggle "),

            // Warps
            new HelpEntry(
                    HelpCategory.WARPS,
                    "/warp [name]",
                    Messages.HELP_CMD_WARP,
                    "/warp spawn",
                    "marp.warp",
                    MarpConfig::warpsEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/warp "),
            new HelpEntry(
                    HelpCategory.WARPS,
                    "/warps",
                    Messages.HELP_CMD_WARPS,
                    "/warps",
                    "marp.warps",
                    MarpConfig::warpsEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/warps"),
            new HelpEntry(
                    HelpCategory.WARPS,
                    "/setwarp <name>",
                    Messages.HELP_CMD_SETWARP,
                    "/setwarp spawn",
                    "marp.admin.setwarp",
                    MarpConfig::warpsEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/setwarp "),
            new HelpEntry(
                    HelpCategory.WARPS,
                    "/delwarp <name>",
                    Messages.HELP_CMD_DELWARP,
                    "/delwarp spawn",
                    "marp.admin.delwarp",
                    MarpConfig::warpsEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/delwarp "),

            // Teleporting
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/tpa <player>",
                    Messages.HELP_CMD_TPA,
                    "/tpa Steve",
                    "marp.tpa",
                    MarpConfig::tpaEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/tpa "),
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/tpahere <player>",
                    Messages.HELP_CMD_TPAHERE,
                    "/tpahere Steve",
                    "marp.tpahere",
                    MarpConfig::tpaEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/tpahere "),
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/tpaccept [player]",
                    Messages.HELP_CMD_TPACCEPT,
                    "/tpaccept",
                    "marp.tpaccept",
                    MarpConfig::tpaEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/tpaccept"),
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/tpdeny [player]",
                    Messages.HELP_CMD_TPDENY,
                    "/tpdeny",
                    "marp.tpdeny",
                    MarpConfig::tpaEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/tpdeny"),
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/tpcancel",
                    Messages.HELP_CMD_TPCANCEL,
                    "/tpcancel",
                    "marp.tpcancel",
                    MarpConfig::tpaEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/tpcancel"),
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/tptoggle [on|off]",
                    Messages.HELP_CMD_TPTOGGLE,
                    "/tptoggle on",
                    "marp.tptoggle",
                    MarpConfig::tpaEnabled,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/tptoggle "),
            new HelpEntry(
                    HelpCategory.TELEPORT,
                    "/back",
                    Messages.HELP_CMD_BACK,
                    "/back",
                    "marp.back",
                    MarpConfig::backEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/back"),

            // General & Admin
            new HelpEntry(
                    HelpCategory.GENERAL_ADMIN,
                    "/favorites",
                    Messages.HELP_CMD_FAVORITES,
                    "/favorites",
                    "marp.favorites",
                    MarpConfig::favoritesEnabled,
                    HelpClickMode.RUN_COMMAND,
                    "/favorites"),
            new HelpEntry(
                    HelpCategory.GENERAL_ADMIN,
                    "/marp help [page]",
                    Messages.HELP_CMD_MARP_HELP,
                    "/marp help 1",
                    "marp.help",
                    cfg -> true,
                    HelpClickMode.RUN_COMMAND,
                    "/marp help 1"),
            new HelpEntry(
                    HelpCategory.GENERAL_ADMIN,
                    "/marp cancel",
                    Messages.HELP_CMD_MARP_CANCEL,
                    "/marp cancel",
                    "marp.cancel",
                    cfg -> true,
                    HelpClickMode.RUN_COMMAND,
                    "/marp cancel"),
            new HelpEntry(
                    HelpCategory.GENERAL_ADMIN,
                    "/marp bed [on|off]",
                    Messages.HELP_CMD_MARP_BED,
                    "/marp bed on",
                    "marp.bedhome.toggle",
                    cfg -> cfg.homesEnabled() && cfg.bedHomeEnabled(),
                    HelpClickMode.SUGGEST_COMMAND,
                    "/marp bed "),
            new HelpEntry(
                    HelpCategory.GENERAL_ADMIN,
                    "/marp info",
                    Messages.HELP_CMD_MARP_INFO,
                    "/marp info",
                    "marp.admin.info",
                    cfg -> true,
                    HelpClickMode.RUN_COMMAND,
                    "/marp info"),
            new HelpEntry(
                    HelpCategory.GENERAL_ADMIN,
                    "/marp reload",
                    Messages.HELP_CMD_MARP_RELOAD,
                    "/marp reload",
                    "marp.admin.reload",
                    cfg -> true,
                    HelpClickMode.SUGGEST_COMMAND,
                    "/marp reload"));

    private HelpCatalog() {}

    public static List<HelpEntry> all() {
        return ENTRIES;
    }

    public static List<HelpEntry> visibleEntries(Predicate<String> hasPermission, MarpConfig config) {
        List<HelpEntry> visible = new ArrayList<>();
        for (HelpEntry entry : ENTRIES) {
            if (entry.featureEnabled().test(config) && hasPermission.test(entry.permission())) {
                visible.add(entry);
            }
        }
        return List.copyOf(visible);
    }
}
