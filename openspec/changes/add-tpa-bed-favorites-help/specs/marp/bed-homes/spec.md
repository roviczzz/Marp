# Spec Delta

## Purpose

Automatically creates and updates a player's bed home when their respawn point is set, so players can return to their bed without manually running `/sethome`.

## ADDED Requirements

### Requirement: Respawn point set creates or updates the bed home

The system SHALL automatically create or overwrite the configured bed home (default `bed`) when a player sets a respawn point from a bed (and from a respawn anchor when configured), preserving any custom display metadata on overwrite.

#### Scenario: First bed click sets bed home

- **WHEN** an eligible player sets their respawn point at a bed in an allowed world
- **THEN** a home named `bed` is saved at the bed with a bed icon material

#### Scenario: New bed overwrites existing bed home

- **WHEN** a player who already has a `bed` home sets their respawn point at a different bed
- **THEN** the `bed` home's location updates and any custom display name, lore, and icon are preserved

#### Scenario: Respawn anchors follow configuration

- **WHEN** a player sets their respawn point at a Nether respawn anchor while `include-respawn-anchors` is false
- **THEN** the player's Overworld `bed` home is not overwritten

### Requirement: Bed home limit and name collision behavior are configurable

The system SHALL exempt the automatic bed home from the player's home limit by default, and SHALL optionally reserve the bed home name from manual `/sethome` overwrites while enabled for that player.

#### Scenario: Bed home created when at manual limit

- **WHEN** a player has reached their manual home limit and sets a bed respawn while limit exemption is enabled
- **THEN** the `bed` home is still created and the GUI home counter counts only manual homes

#### Scenario: Manual deletion works normally

- **WHEN** a player runs `/delhome bed` or deletes it in the homes GUI
- **THEN** the `bed` home is removed

### Requirement: Vanilla respawn validation is configurable with snapshot-home default

The system SHALL treat the saved bed home as a persistent Marp home regardless of later bed destruction or obstruction by default, and SHALL validate the live bed or charged anchor after async chunk loading when strict validation is enabled.

#### Scenario: Default mode allows returning to a broken bed

- **WHEN** a player's bed is destroyed or obstructed after capture while `require-valid-bed` is false
- **THEN** `/home bed` runs the normal teleport and safety pipeline

#### Scenario: Strict mode blocks missing or obstructed bed

- **WHEN** a player's bed is missing or obstructed while `require-valid-bed` is true
- **THEN** `/home bed` fails with a clear message without synchronously stalling the main thread

### Requirement: Bed home integration supports world exclusions, player opt-out, and GUI marking

The system SHALL allow servers to disable bed homes globally or per world, allow each player to toggle automatic bed capture via command or GUI, and display bed homes distinctly in the `/homes` menu.

#### Scenario: Disabled world is ignored

- **WHEN** a player clicks a bed in a world listed in `bed-home.disabled-worlds`
- **THEN** their existing `bed` home is left unchanged

#### Scenario: Player opts out via command or GUI

- **WHEN** a player disables auto-bed capture via `/bedtoggle` (`/marp bed off`) or the `/homes` menu toggle
- **THEN** subsequent bed interactions do not create or overwrite `bed`, while existing homes remain untouched

#### Scenario: Bed home renders distinctly in `/homes`

- **WHEN** a player opens `/homes` with a saved `bed` home
- **THEN** the entry displays a bed lore marker and bed default icon
