# Spec Delta

## Purpose

Lets players star their most-used homes and server warps for pinned ordering in lists, a unified `/favorites` quick-access GUI, and `/home` fallback resolution.

## ADDED Requirements

### Requirement: Players can star and unstar homes and warps within a limit

The system SHALL allow players to toggle favorites on their own homes and permitted warps up to a limit resolved from `marp.favoritelimit.<n>` / `marp.favoritelimit.unlimited` (or the configured default), without removing existing favorites if the limit later drops.

#### Scenario: Star a home and a warp

- **WHEN** a player stars a home and a permitted warp while below their favorite limit
- **THEN** both entries are saved as favorites in deterministic insertion order

#### Scenario: Favorite limit enforced on add

- **WHEN** a player at their favorite limit tries to star another entry
- **THEN** the action is refused with a limit message and existing favorites remain untouched

#### Scenario: Unstarring frees a slot

- **WHEN** a player unstars an existing favorite
- **THEN** the favorite record is removed immediately

### Requirement: Favorites appear visually marked, pinned first, and in a dedicated GUI

The system SHALL display starred entries with a star indicator, pin favorites before non-favorites in `/homes` and `/warps` ordered by favorite insertion order, provide non-conflicting GUI star controls, and expose a unified `/favorites` GUI.

#### Scenario: Pinned ordering in `/homes` and `/warps`

- **WHEN** a player opens `/homes` or `/warps` with starred entries
- **THEN** starred entries appear first in insertion order, followed by unstarred entries sorted by identifier

#### Scenario: Star Mode toggles favorites without breaking existing clicks

- **WHEN** a player enables Star Mode in `/homes` or `/warps` and left-clicks an entry
- **THEN** the entry toggles its favorite status in place without teleporting or opening delete confirmation

#### Scenario: Unified `/favorites` menu supports filtering

- **WHEN** a player runs `/favorites` (or clicks the Favorites tab button)
- **THEN** a chest menu displays starred homes and permitted starred warps with `All`, `Homes`, and `Warps` filter cycling and one-click teleporting

### Requirement: `/home` without arguments honors favorites cleanly

The system SHALL resolve `/home` with no arguments using the configured priority (`DEFAULT_FIRST`: default home, then top favorite home, then single home, else open `/homes`; or `FAVORITE_FIRST`).

#### Scenario: Default home absent, top favorite used

- **WHEN** a player runs `/home` with no arguments, has no default `home`, and has two starred homes
- **THEN** the player teleports to the first-starred favorite home

#### Scenario: Default home present under `DEFAULT_FIRST`

- **WHEN** a player runs `/home` with no arguments while `home` exists and `home-no-arg-priority` is `DEFAULT_FIRST`
- **THEN** the player teleports to `home`

### Requirement: Favorites survive edits and clean up on deletion

The system SHALL reference favorites by immutable record ID so display-name, lore, icon, and location updates preserve favorite state, and SHALL remove favorite mappings transactionally when a home or warp is deleted.

#### Scenario: Editing or overwriting preserves star

- **WHEN** a starred home or warp has its display metadata edited or its coordinates updated
- **THEN** its favorite status and position remain intact

#### Scenario: Deleting home or warp cleans up favorites

- **WHEN** a favorited home or warp is deleted
- **THEN** its favorite records are removed in the same transaction without leaving broken GUI entries

#### Scenario: Revoked warp permission hides entry

- **WHEN** a player loses permission to a warp they previously starred
- **THEN** the warp is omitted from their `/favorites` GUI (unless `hide-unauthorized` is false or they are an admin) without failing the menu
