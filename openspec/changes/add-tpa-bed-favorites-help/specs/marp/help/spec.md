# Spec Delta

## Purpose

Replaces the bare `/marp` usage output with a permission-aware, feature-aware, category-grouped, paginated help directory featuring clickable commands and hover tooltips.

## ADDED Requirements

### Requirement: `/marp` and `/marp help` list available commands by permission and feature state

The system SHALL display every Marp command the sender has permission to use and whose feature switch is enabled when `/marp` (no arguments) or `/marp help [page]` is run.

#### Scenario: Player sees only permitted commands

- **WHEN** a non-operator player runs `/marp` or `/marp help`
- **THEN** player commands they hold permission for appear and operator-only commands like `/setwarp` and `/marp reload` are omitted

#### Scenario: Disabled features are hidden from help

- **WHEN** `warps.enabled` is false and a player runs `/marp`
- **THEN** warp commands are excluded from the help output

#### Scenario: Console can read help

- **WHEN** console runs `/marp` or `/marp help`
- **THEN** the help directory renders cleanly without rejecting console as player-only

### Requirement: Help entries are grouped, clickable, and show hover details

The system SHALL group help entries by category (`Homes`, `Warps`, `Teleporting`, `General & Admin`), display syntax plus a one-line description per entry, attach hover details, and bind smart click actions (run for no-arg/GUI/safe actions; suggest for argument-taking or admin actions).

#### Scenario: Clickable entries with hover tooltips

- **WHEN** a player views `/marp help`
- **THEN** clicking `/homes` runs `/homes`, clicking `/tpa <player>` suggests `/tpa ` in chat, and hovering shows syntax, description, example, and click hint

### Requirement: Long help lists paginate cleanly with clickable controls

The system SHALL paginate help entries at the configured page size (`help.entries-per-page`), clamp out-of-range page numbers, and render clickable previous/next page buttons in the footer.

#### Scenario: Multi-page navigation

- **WHEN** a player has more permitted commands than fit on one page
- **THEN** page 1 renders a clickable next-page button running `/marp help 2` and page 2 renders a clickable previous-page button running `/marp help 1`
