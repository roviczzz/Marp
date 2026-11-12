# Spec Delta

## Purpose

Returns a player to the place they died or the place they stood before their last teleport, without turning repeated use into an infinite loop.

## ADDED Requirements

### Requirement: Death points are recorded

The system SHALL record where a player died as a back target, separate from their respawn point, when death recording is enabled.

#### Scenario: Death recorded

- **WHEN** a player dies
- **THEN** their death location becomes the newest back target

#### Scenario: Respawn elsewhere

- **WHEN** a player dies in one world and respawns in another
- **THEN** `/back` targets the world and position where they died

#### Scenario: Death recording disabled

- **WHEN** death recording is disabled
- **THEN** dying does not create or replace a back target

### Requirement: Marp teleports record their origin

The system SHALL record the location a player left before a successful home or warp teleport, and SHALL NOT record anything for a teleport that was cancelled or failed.

#### Scenario: Home teleport records origin

- **WHEN** a player teleports to a home successfully
- **THEN** their previous location becomes a back target

#### Scenario: Cancelled teleport records nothing

- **WHEN** a home teleport is cancelled during warmup
- **THEN** the previous back target is unchanged

#### Scenario: External teleports are ignored by default

- **WHEN** another plugin teleports a player and external recording is disabled
- **THEN** the back target is unchanged

#### Scenario: External teleports are opt-in

- **WHEN** external recording is enabled and another plugin teleports a player
- **THEN** the player's previous location becomes a back target

### Requirement: The back target follows the configured preference

The system SHALL choose between the stored death point and the stored teleport origin according to a configured preference, using newest-first by default and treating a tie as the death point.

#### Scenario: Newest wins

- **WHEN** both a death point and a teleport origin exist and preference is newest
- **THEN** the more recently recorded target is used

#### Scenario: Death preferred

- **WHEN** preference is death and both exist
- **THEN** the death point is used

#### Scenario: Only one target

- **WHEN** only a death point or only a teleport origin exists
- **THEN** that target is used

#### Scenario: No target

- **WHEN** the player has neither a death point nor a teleport origin
- **THEN** `/back` reports that there is nothing to return to and starts no teleport

### Requirement: Repeated back use does not ping-pong

The system SHALL NOT modify the back target when a player uses `/back`, and SHALL keep the chosen target after arrival unless consumption is enabled.

#### Scenario: Repeated use

- **WHEN** a player uses `/back` twice with consumption disabled
- **THEN** both requests target the same location, subject to cooldown

#### Scenario: Consumption enabled

- **WHEN** consumption is enabled and `/back` succeeds
- **THEN** the used target is removed

#### Scenario: Already there

- **WHEN** the player is already at the target location
- **THEN** no warmup starts and the player is told they are already there

### Requirement: Unreachable back targets fall back to safe ground

The system SHALL, when the chosen target cannot be reached safely, fall back to the player's last recorded on-ground position if the fallback is enabled, and SHALL otherwise report that no safe destination exists.

#### Scenario: Void death

- **WHEN** the player died in the void and no safe spot exists near the death point
- **THEN** the player is returned to their last recorded on-ground position

#### Scenario: Fallback disabled

- **WHEN** the fallback is disabled and the target is unreachable
- **THEN** `/back` reports that no safe spot was found and does not teleport

#### Scenario: No recorded ground

- **WHEN** the target is unreachable and no on-ground position has been recorded for that session
- **THEN** `/back` reports that the target cannot be reached safely

#### Scenario: Reachable target is never redirected

- **WHEN** the chosen target passes the safety checks
- **THEN** the player is taken to that exact target, not the fallback position
