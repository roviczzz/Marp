package me.roviczzz.marp.teleport;

import me.roviczzz.marp.model.LocationSnapshot;

/**
 * Outcome of evaluating a teleport destination.
 *
 * <p>{@code reason} is a stable message code ({@code lava}, {@code void},
 * {@code suffocation}, {@code fire}, {@code fall}, {@code water},
 * {@code border}, {@code floor}, {@code search}), never an English sentence.
 */
public sealed interface SafetyResult {

    record Safe(LocationSnapshot location) implements SafetyResult {}

    record Adjusted(LocationSnapshot location, String reason) implements SafetyResult {}

    record Unsafe(String reason) implements SafetyResult {}
}
