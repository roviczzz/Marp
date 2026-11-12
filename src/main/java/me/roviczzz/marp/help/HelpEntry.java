package me.roviczzz.marp.help;

import java.util.function.Predicate;
import me.roviczzz.marp.config.MarpConfig;

/** Pure immutable descriptor for an entry in the Marp help directory. */
public record HelpEntry(
        HelpCategory category,
        String syntax,
        String descriptionKey,
        String example,
        String permission,
        Predicate<MarpConfig> featureEnabled,
        HelpClickMode clickMode,
        String clickPayload) {}
