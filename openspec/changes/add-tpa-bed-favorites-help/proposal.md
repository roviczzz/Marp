# Proposal

## Why

Marp v0.1 covers personal homes, admin warps, and `/back`, but survival servers also expect player-to-player teleport requests, automatic bed homes so players are not stranded without `/sethome`, quick-access favorites across homes and warps, and a discoverable in-game command directory. Adding these four capabilities builds directly on Marp's existing async SQLite persistence, collision-box safety pipeline, and holder-identified chest GUIs.

## What Changes

**Teleport requests (`/tpa`-style)**
- Add `/tpa <player>`, `/tpahere <player>`, `/tpaccept [player]`, `/tpdeny [player]`, `/tpcancel`, and `/tptoggle` (persisted per-player opt-out).
- Send clickable Adventure `[Accept]` / `[Deny]` buttons (and requester `[Cancel]`) with detailed hover text, built safely in Java around `MessageService` labels so `MessageService`'s MiniMessage whitelist remains locked down.
- Enforce 1 active outgoing request per requester, 60-second expiry, explicit disambiguation (clickable buttons, no blind newest-wins) when multiple requests target the same player, a 30-second per-pair post-deny cooldown, optional PvP combat-tag guard, and live-anchor arrival safety checks through `TeleportService` where rules apply to the mover, not the anchor.
- Defer persistent per-player `/tpblock` lists out of v1; `/tptoggle` plus post-deny cooldown cover anti-spam without offline-UUID management.

**Bed / respawn integration**
- Listen to Paper's `PlayerSetSpawnEvent` (`Cause.BED`, plus optional `Cause.RESPAWN_ANCHOR`) to create or overwrite the player's `bed` home automatically while preserving custom display metadata.
- Exempt the bed home from `marp.homelimit.<n>` by default (`is_bed` column), ignore vanilla respawn invalidation after capture by default (`require-valid-bed: false`, with an opt-in strict mode), support disabled worlds, and provide both a config switch and per-player `/bedtoggle` (`/marp bed`) + GUI nav-row toggle.
- Render bed homes in `/homes` with a matching bed icon and lore marker.

**Favorites**
- Add unified home + warp starring persisted in SQLite (`favorites` table keyed by `target_kind` and immutable `target_id`), bounded by `marp.favoritelimit.<n>` / `marp.favoritelimit.unlimited`.
- Pin favorites to the top of `/homes` and `/warps`, add a non-conflicting Star Mode toggle in row 5 plus a favorite slot in `EntryEditMenu`, and add a unified `/favorites` (`/fav`) chest GUI (`FavoritesMenu`) with `All | Homes | Warps` filtering.
- Update `/home` with no arguments to try `default-name`, then top favorite home, then single-home fallback, then open `/homes`. Clean up favorites transactionally when homes or warps are deleted.

**Help command (`/marp`)**
- Update `/marp` (with no arguments) and `/marp help [page]` to display a permission-aware and feature-flag-aware command list grouped by category (`Homes`, `Warps`, `Teleporting`, `General & Admin`).
- Paginate with clickable `[<< Previous]` / `[Next >>]` controls and clickable command entries (run vs suggest in chat) with rich hover tooltips.

**BREAKING:** `/marp` with no arguments now opens the help directory instead of printing the admin usage line.

## Capabilities

### New Capabilities

- `marp/teleport-requests`: Player-to-player `/tpa` and `/tpahere` requests, expiry and ambiguity resolution, clickable chat prompts, `/tptoggle`, and live-anchor teleport pipeline integration.
- `marp/bed-homes`: Automatic bed/respawn home capture, limit exemption, vanilla respawn policy, per-player opt-out, and `/homes` GUI presentation.
- `marp/favorites`: Unified home and warp starring, `marp.favoritelimit.<n>` caps, pinned sorting, `/favorites` GUI, GUI star controls, `/home` fallback integration, and transactional cleanup.
- `marp/help`: Permission-aware, feature-aware, category-grouped, paginated `/marp` and `/marp help` command directory with clickable actions and hover details.

### Modified Capabilities

<!-- None. No archived main specs exist under openspec/specs/ yet; integration points with existing Marp services, schema, and menus are captured in design.md and the new capability specs. -->

## Impact

- **Database:** Schema migration `V2FeaturesSchema` (`marp_schema.version = 2`) adding `homes.is_bed`, `player_settings`, and `favorites` with `SqlDialect` SQLite/MySQL parity.
- **Commands & Permissions:** Updates `plugin.yml` with `tpa`, `tpahere`, `tpaccept`, `tpdeny`, `tpcancel`, `tptoggle`, `bedtoggle`, `favorites`, updated `/marp` usage, and static permission nodes under `marp.use` / `marp.*`.
- **Configuration & Messages:** Updates `config.yml`, `messages.yml`, `MarpConfig`, and `Messages.java` (including exposing `UNKNOWN_PLAYER`).
- **Core Classes:** Extends `TeleportReason`, `TeleportSession`, `TeleportService`, `WarmupSampler`, `Home`, `HomeService`, `SqlHomeRepository`, `SqlWarpRepository`, `MarpMenuHolder`, `HomeListMenu`, `WarpListMenu`, `EntryEditMenu`, `GuiItemFactory`, `MenuManager`, `MarpCommand`, `CompletionSupport`, `PlayerConnectionListener`, `DeathListener`, and `Marp.java`.
- **Dependencies:** None added; pure Paper 1.21.10 / Java 21 + SQLite JDBC + JUnit 5.
