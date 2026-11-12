# Spec Delta

## Purpose

Gives players a chest-inventory interface for browsing, teleporting to, and personalising their homes and the server's warps, without exposing item duplication or misclick risk.

## ADDED Requirements

### Requirement: Menus are identified by identity, not title

The system SHALL identify its menus by an attached menu identity and SHALL cancel every click and drag inside them, including clicks in the player's own inventory while a menu is open, so no item can be moved, duplicated, or dropped.

#### Scenario: Item cannot be extracted

- **WHEN** a player clicks, shift-clicks, number-swaps, or drags an item in a Marp menu
- **THEN** the action is cancelled and nothing enters or leaves the player's inventory

#### Scenario: Renamed title

- **WHEN** a plugin or the server changes display text that resembles a Marp title
- **THEN** no inventory without the Marp menu identity is treated as a Marp menu

#### Scenario: Another player's menu

- **WHEN** a click arrives for a menu owned by a different player
- **THEN** the click is ignored and the menu closes

#### Scenario: Rapid double click

- **WHEN** a player double-clicks an action button
- **THEN** only one action runs

### Requirement: Lists paginate deterministically

The system SHALL paginate entries at a fixed page size, clamp the requested page into range, keep ordering stable across refreshes, and preserve the current page across edits.

#### Scenario: Boundary counts

- **WHEN** a list has 0, 1, exactly one page, or one more than one page of entries
- **THEN** the reported page count and the enabled navigation buttons match the content

#### Scenario: Stable order

- **WHEN** an entry is renamed or its lore changes
- **THEN** its position in the list does not change

#### Scenario: Empty list

- **WHEN** the list has no entries
- **THEN** the menu opens with an empty-state item explaining how to add one

#### Scenario: Delete on a later page

- **WHEN** the last entry on a page is deleted
- **THEN** the menu returns to the previous page instead of showing an empty page

### Requirement: List entries expose the documented actions

The system SHALL map left-click to teleport, right-click to edit, and shift-click to a delete confirmation on entries, SHALL provide previous/next, close, page information, and a set-home action on the homes list, and SHALL ignore other click types.

#### Scenario: Teleport from the list

- **WHEN** a player left-clicks an entry they may use
- **THEN** the teleport pipeline starts for that entry

#### Scenario: Edit from the list

- **WHEN** a player right-clicks an entry they may edit
- **THEN** the entry edit menu opens

#### Scenario: Delete from the list

- **WHEN** a player shift-clicks an entry they may delete
- **THEN** the delete confirmation opens

#### Scenario: Unauthorized action

- **WHEN** a player clicks an action their permissions do not allow
- **THEN** the action is refused with a message and nothing changes

#### Scenario: Navigation limits

- **WHEN** the player is on the first or last page
- **THEN** the corresponding navigation button is visually disabled and does nothing

#### Scenario: Page information

- **WHEN** the homes list is open
- **THEN** page information shows the page, page count, and homes used against the limit

### Requirement: Entry metadata is edited through the menu

The system SHALL let an authorized user change an entry's icon, display name, and lore from the edit menu, SHALL apply each change immediately, and SHALL NOT allow the command identifier to be changed there.

#### Scenario: Icon copied from held item

- **WHEN** the user clicks the icon slot while holding an item
- **THEN** a single copy of that item becomes the entry icon and the held stack is untouched

#### Scenario: Icon rejected

- **WHEN** the held item cannot be used as an icon or exceeds the storage limit
- **THEN** the previous icon is kept and the user is told why

#### Scenario: Name and lore edited by chat

- **WHEN** the user chooses to edit the display name or lore
- **THEN** the next chat message is captured, validated, and applied, and `cancel` aborts the edit

#### Scenario: Identifier unchanged

- **WHEN** any metadata edit is applied
- **THEN** the identifier used by commands is unchanged

### Requirement: Destructive actions require timed confirmation

The system SHALL require a separate confirmation menu for GUI deletion, SHALL expire that confirmation after a configured time, and SHALL treat closing the menu as a cancel.

#### Scenario: Confirmed delete

- **WHEN** the user confirms deletion
- **THEN** the entry is deleted and the list reopens without it

#### Scenario: Cancelled delete

- **WHEN** the user chooses cancel or closes the menu
- **THEN** the entry is untouched

#### Scenario: Expired confirmation

- **WHEN** the confirmation is left open past its timeout
- **THEN** the menu closes and the entry is untouched

### Requirement: Player-authored text is validated and rendered safely

The system SHALL enforce visible and raw length limits on display names and lore, SHALL render formatting through a restricted tag set that cannot resolve selectors, scores, NBT, clicks, or hover data, and SHALL reject invalid input instead of truncating it.

#### Scenario: Formatted name accepted

- **WHEN** a player enters a coloured display name within the visible and raw limits
- **THEN** the menu shows it formatted

#### Scenario: Oversized input rejected

- **WHEN** a player enters text beyond the visible or raw limit, or too many lore lines
- **THEN** the edit is refused with a message naming the limit and the previous value is kept

#### Scenario: Dangerous tag is neutral

- **WHEN** a player enters a tag that would select players or read scores
- **THEN** it renders as literal text and resolves to no data

#### Scenario: Invalid stored value

- **WHEN** a stored display value no longer parses
- **THEN** that entry falls back to its plain identifier and the rest of the menu is unaffected

### Requirement: Stale entries are revalidated before acting

The system SHALL re-fetch an entry by its identity before acting on a click and SHALL report a conflict or missing entry instead of acting on stale data.

#### Scenario: Entry edited elsewhere

- **WHEN** a click acts on an entry whose revision has changed
- **THEN** the action is refused as a conflict and the menu refreshes

#### Scenario: Entry deleted elsewhere

- **WHEN** a click acts on an entry that no longer exists
- **THEN** the user is told it is gone and the list refreshes
