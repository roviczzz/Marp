# Spec Delta

## Purpose

Lets each player keep a named set of personal teleport points, with a home count bounded by their rank rather than by a fixed server-wide number.

## ADDED Requirements

### Requirement: Player can create or update a named home

The system SHALL let a player save their current location as a home under an identifier, using a configured default identifier when none is given, and SHALL update the location when that identifier already exists.

#### Scenario: New named home

- **WHEN** a player runs `/sethome base`
- **THEN** a home named `base` is stored at their current position, including facing

#### Scenario: Default name

- **WHEN** a player runs `/sethome` with no identifier
- **THEN** the configured default identifier is used

#### Scenario: Existing name is overwritten

- **WHEN** a player runs `/sethome base` while `base` exists
- **THEN** its location is updated and its icon, display name, and lore are preserved

#### Scenario: Saving is never blocked by safety

- **WHEN** a player saves a home at a position that would fail a teleport safety check
- **THEN** the home is still saved

### Requirement: Player can teleport to a home

The system SHALL teleport a player to one of their own homes by identifier, applying the shared teleport pipeline, and SHALL report a missing home without starting a teleport.

#### Scenario: Named home

- **WHEN** a player runs `/home base` and the home exists
- **THEN** the teleport pipeline starts for that home

#### Scenario: No identifier, default exists

- **WHEN** a player runs `/home` and the default home exists
- **THEN** the player is taken to the default home

#### Scenario: No identifier, one home

- **WHEN** a player runs `/home`, has no default home, and has exactly one home
- **THEN** that single home is used

#### Scenario: No identifier, several homes

- **WHEN** a player runs `/home`, has no default home, and has more than one home
- **THEN** the homes menu opens and the player is told no default home is set

#### Scenario: Missing home

- **WHEN** a player runs `/home base` and no such home exists
- **THEN** the player is told the home was not found and no teleport starts

### Requirement: Player can delete a home

The system SHALL delete a home only when it belongs to the requesting player, identifying the record by its stable id and owner rather than by name alone.

#### Scenario: Delete own home

- **WHEN** a player runs `/delhome base` and owns `base`
- **THEN** the home is removed and the confirmation names it

#### Scenario: Delete missing home

- **WHEN** a player runs `/delhome base` and it does not exist
- **THEN** the player is told the home was not found

#### Scenario: Another player's data is untouchable

- **WHEN** a delete is attempted for a record owned by a different player
- **THEN** nothing is deleted and the request is treated as not found

### Requirement: Home identifiers are case-insensitive and stable

The system SHALL normalise home identifiers to lower case, enforce a configured character pattern, resolve lookups case-insensitively, and SHALL NOT allow an existing home's identifier to change.

#### Scenario: Mixed-case input

- **WHEN** a player runs `/sethome Base` and later `/home BASE`
- **THEN** both refer to the same home

#### Scenario: Invalid identifier

- **WHEN** a player supplies a name with spaces or characters outside the configured pattern
- **THEN** the request is rejected with a message naming the accepted pattern

#### Scenario: Renaming is not possible

- **WHEN** a player edits a home's display name
- **THEN** the identifier used by commands is unchanged

### Requirement: Home count is limited per player

The system SHALL resolve a player's home limit from the highest matching `marp.homelimit.<n>` permission, fall back to the configured default when no node applies, honour `marp.homelimit.unlimited`, and deny new homes at the limit without removing existing ones.

#### Scenario: Highest node wins

- **WHEN** a player has `marp.homelimit.2` and `marp.homelimit.5`
- **THEN** the limit is 5

#### Scenario: Fallback limit

- **WHEN** a player has no limit node
- **THEN** the configured default limit applies

#### Scenario: Unlimited

- **WHEN** a player has `marp.homelimit.unlimited`
- **THEN** creating homes is not capped

#### Scenario: Zero limit

- **WHEN** a player's resolved limit is 0
- **THEN** new homes are refused and existing homes are kept

#### Scenario: Limit lowered after homes exist

- **WHEN** a player has more homes than their current limit
- **THEN** the extra homes are kept, new homes are refused, and the menu shows the count over the limit

#### Scenario: Updating at the limit

- **WHEN** a player at their limit runs `/sethome` with an existing identifier
- **THEN** the update is allowed because no new home is created

### Requirement: Home display metadata is editable

The system SHALL let the owner set a home's icon, display name, and lore independently of its location and identifier, and SHALL fall back to a safe rendering when the stored icon cannot be used.

#### Scenario: Icon from held item

- **WHEN** the owner sets an icon from a held item
- **THEN** the menu renders that item and the held item is not consumed

#### Scenario: Unusable icon

- **WHEN** a stored icon cannot be read back, is too large, or is not an item
- **THEN** the menu renders the fallback material and stays usable

#### Scenario: Metadata limits

- **WHEN** a display name or lore line exceeds the configured limits
- **THEN** the change is rejected with a message and the previous value is kept
