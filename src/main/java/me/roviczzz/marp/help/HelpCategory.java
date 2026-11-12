package me.roviczzz.marp.help;

import me.roviczzz.marp.config.Messages;

/** Category groups for the {@code /marp help} command directory. */
public enum HelpCategory {
    HOMES(Messages.HELP_CAT_HOMES),
    WARPS(Messages.HELP_CAT_WARPS),
    TELEPORT(Messages.HELP_CAT_TELEPORT),
    GENERAL_ADMIN(Messages.HELP_CAT_GENERAL_ADMIN);

    private final String messageKey;

    HelpCategory(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
