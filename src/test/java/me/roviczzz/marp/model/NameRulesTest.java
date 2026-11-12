package me.roviczzz.marp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class NameRulesTest {

    private final NameRules rules = new NameRules(
            java.util.regex.Pattern.compile(NameRules.DEFAULT_PATTERN), 32, Set.of("spawn"));

    @Test
    void mixedCaseIsNormalised() {
        NameRules.Result result = rules.validate("Base");
        assertTrue(result.valid());
        assertEquals("base", result.key());
    }

    @Test
    void spacesAreRejected() {
        assertFalse(rules.validate("my home").valid());
        assertEquals(NameRules.Reason.INVALID, rules.validate("my home").reason());
    }

    @Test
    void emptyIsRejected() {
        assertEquals(NameRules.Reason.EMPTY, rules.validate("").reason());
        assertEquals(NameRules.Reason.EMPTY, rules.validate(null).reason());
    }

    @Test
    void tooLongIsReportedSeparately() {
        NameRules.Result result = rules.validate("a".repeat(33));
        assertFalse(result.valid());
        assertEquals(NameRules.Reason.TOO_LONG, result.reason());
    }

    @Test
    void nonAsciiIsRejected() {
        assertFalse(rules.validate("héllo").valid());
        assertFalse(rules.validate("家").valid());
    }

    @Test
    void reservedNamesAreRejectedCaseInsensitively() {
        assertEquals(NameRules.Reason.RESERVED, rules.validate("SPAWN").reason());
    }
}
