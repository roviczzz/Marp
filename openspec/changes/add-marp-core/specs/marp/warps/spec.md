# Spec Delta

## Purpose

Provides server-wide teleport destinations that staff create and maintain, with optional per-warp access control for restricted areas.

## ADDED Requirements

### Requirement: Staff can create or move a warp

The system SHALL let an operator create a warp at their current location, and SHALL treat creating an existing identifier as a move that preserves the warp's presentation and access settings.

#### Scenario: New warp

- **WHEN** an operator runs `/setwarp spawn`
- **THEN** a warp named `spawn` is created at their current position

#### Scenario: Existing warp is moved

- **WHEN** an operator runs `/setwarp spawn` while `spawn` exists
- **THEN** its location changes and its icon, display name, lore, and access stay as they were

#### Scenario: Non-operator denied

- **WHEN** a player without warp administration permission runs `/setwarp`
- **THEN** the request is refused and no warp is created

#### Scenario: Warp creation is player-only

- **WHEN** the console runs `/setwarp`
- **THEN** the request is refused because there is no current location

### Requirement: Staff can delete a warp

The system SHALL let an operator delete a warp by identifier, including from the console, and SHALL report a missing warp without side effects.

#### Scenario: Delete from game

- **WHEN** an operator runs `/delwarp spawn`
- **THEN** the warp is removed for all players

#### Scenario: Delete from console

- **WHEN** the console runs `/delwarp spawn`
- **THEN** the warp is removed

#### Scenario: Delete missing warp

- **WHEN** an operator runs `/delwarp` with an identifier that does not exist
- **THEN** the player is told the warp was not found

### Requirement: Players teleport to permitted warps

The system SHALL teleport a player to a warp through the shared teleport pipeline, after verifying the player's access to that warp, and SHALL refuse without teleporting when access is denied.

#### Scenario: Public warp

- **WHEN** per-warp access is disabled and a player runs `/warp spawn`
- **THEN** the teleport pipeline starts for `spawn`

#### Scenario: Per-warp access

- **WHEN** per-warp access is enabled and the player has `marp.warp.access.spawn`
- **THEN** the teleport pipeline starts for `spawn`

#### Scenario: Access denied, hiding enabled

- **WHEN** access is denied and unauthorized warps are hidden
- **THEN** the player is told the warp does not exist

#### Scenario: Access denied, hiding disabled

- **WHEN** access is denied and unauthorized warps are not hidden
- **THEN** the player is told they lack access to that warp

#### Scenario: No identifier

- **WHEN** a player runs `/warp` with no identifier
- **THEN** the warps menu opens instead of a default-warp teleport

### Requirement: Warp listings show only accessible warps

The system SHALL show a player only the warps they may use, except that operators see every warp including inaccessible ones marked as locked.

#### Scenario: Restricted warps are hidden

- **WHEN** a player opens the warps menu and lacks access to a restricted warp
- **THEN** that warp does not appear in the list or in completion

#### Scenario: Operator sees everything

- **WHEN** an operator opens the warps menu
- **THEN** inaccessible warps appear with a locked marker

#### Scenario: Empty list

- **WHEN** no accessible warps exist
- **THEN** the menu opens with an empty-state item explaining that warps are staff-managed

### Requirement: Warp identifiers are globally unique and case-insensitive

The system SHALL normalise warp identifiers to lower case, enforce the configured name pattern, and SHALL guarantee that no two warps share an identifier regardless of case.

#### Scenario: Case-insensitive lookup

- **WHEN** a warp was created as `Spawn` and a player runs `/warp SPAWN`
- **THEN** the same warp is used

#### Scenario: Concurrent creation of the same name

- **WHEN** two creations of the same identifier race
- **THEN** one warp exists afterwards and the other request is reported as an update

#### Scenario: Invalid identifier

- **WHEN** an operator supplies a name outside the configured pattern
- **THEN** the warp is not created and the accepted pattern is reported

### Requirement: Warp presentation is editable by staff

The system SHALL let an operator set a warp's icon, display name, and lore, and SHALL render a safe fallback when a stored icon cannot be used.

#### Scenario: Restyled warp

- **WHEN** an operator changes a warp's icon, name, or lore
- **THEN** every player's warps menu shows the new presentation on the next open

#### Scenario: Invalid stored icon

- **WHEN** a warp's stored icon cannot be read back
- **THEN** the menu renders the fallback material and the rest of the list still works
