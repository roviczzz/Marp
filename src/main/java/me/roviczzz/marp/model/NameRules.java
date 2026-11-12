package me.roviczzz.marp.model;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One identifier policy shared by homes and warps.
 *
 * <p>Identifiers are lower-case ASCII, stable after creation, and resolved
 * case-insensitively. Display names are a separate, editable field.
 */
public final class NameRules {

    public static final String DEFAULT_PATTERN = "^[a-z0-9_-]{1,32}$";
    public static final int DEFAULT_MAX_LENGTH = 32;

    public enum Reason {
        EMPTY,
        TOO_LONG,
        INVALID,
        RESERVED
    }

    public record Result(boolean valid, String key, Reason reason) {

        public static Result ok(String key) {
            return new Result(true, key, null);
        }

        public static Result rejected(Reason reason) {
            return new Result(false, null, reason);
        }
    }

    private final Pattern pattern;
    private final int maxLength;
    private final Set<String> reserved;

    public NameRules(Pattern pattern, int maxLength, Set<String> reserved) {
        this.pattern = pattern;
        this.maxLength = maxLength;
        this.reserved = Set.copyOf(reserved);
    }

    public static NameRules defaults() {
        return new NameRules(Pattern.compile(DEFAULT_PATTERN), DEFAULT_MAX_LENGTH, Set.of());
    }

    public Result validate(String input) {
        if (input == null || input.isEmpty()) {
            return Result.rejected(Reason.EMPTY);
        }
        String key = input.toLowerCase(Locale.ROOT);
        if (key.length() > maxLength) {
            return Result.rejected(Reason.TOO_LONG);
        }
        if (!pattern.matcher(key).matches()) {
            return Result.rejected(Reason.INVALID);
        }
        if (reserved.contains(key)) {
            return Result.rejected(Reason.RESERVED);
        }
        return Result.ok(key);
    }

    public int maxLength() {
        return maxLength;
    }

    public String patternText() {
        return pattern.pattern();
    }
}
