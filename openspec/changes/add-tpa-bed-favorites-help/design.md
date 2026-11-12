# Design

## Context

Marp v0.1 (`Marp-1.0-SNAPSHOT-all.jar`, Paper 1.21.10, Java 21) ships homes, warps, `/back`, sampled warmups, collision-box safety evaluation, SQLite storage on `Marp-DB` (`DbExecutor`), and `MarpMenuHolder`-backed chest GUIs. See `proposal.md` for motivation and scope.

Key existing invariants in the codebase that constrain this design:

- `MessageService` uses a strict MiniMessage `TagResolver` whitelist (`color`, `decorations`, `gradient`, `rainbow`, `reset`, `newline`, `translatable`, `keybind`, `font`) and omits `click`/`hover` so user-supplied display names and lore cannot smuggle interactive tags.
- `TeleportService` expects `TeleportReason` (`HOME`, `WARP`, `BACK`) and a static `LocationSnapshot` passed at request time, while `WarmupSampler` ticks every `sample-interval-ticks` (4) on the main thread.
- `MenuListener` accepts only `LEFT`, `RIGHT`, `SHIFT_LEFT`, `SHIFT_RIGHT`; `HomeListMenu` and `WarpListMenu` use all four on entry slots (`0..44`) and leave nav slots `+1, +2, +6, +7` in row 5 as filler.
- Paper 1.21.10 deprecates `PlayerSpawnChangeEvent` in favor of `com.destroystokyo.paper.event.player.PlayerSetSpawnEvent`, and `Player#getRespawnLocation()` triggers a synchronous chunk load (`getRespawnLocation(true)`).
- `ConfigResourcesTest` reflectively asserts every constant in `Messages` resolves in `src/main/resources/messages.yml`.

## Goals / Non-Goals

**Goals:**

- Integrate all four features into existing Marp services (`TeleportService`, `HomeService`, `MenuManager`, `SqlStorage`, `MarpCommand`) without bypassing main-thread/DB isolation or testability without MockBukkit.
- Keep pure logic (`TpaRegistry` / expiry / ambiguity rules, `FavoriteLimitResolver`, `HelpCatalog` filtering & pagination, `CombatTracker`) decoupled behind interfaces and covered by pure JUnit 5 tests.
- Keep `SqlDialect` SQLite/MySQL parity intact in `V2FeaturesSchema`.

**Non-Goals:**

- Persistent per-player `/tpblock` block lists in v1 (deferred in favor of `/tptoggle` + per-pair post-deny cooldown).
- External plugin integrations (CombatLogX, PlaceholderAPI, LuckPerms API) — expose a pure `TeleportGuard` seam for future combat hooks only.
- Drag-and-drop inventory reordering inside `FavoritesMenu` (insertion order via `sort_order` is deterministic and sufficient).
- MySQL pool implementation or Folia regionized scheduling.

## Decisions

### D1 — Compose interactive chat components in Java around `MessageService`

Do not add `StandardTags.clickEvent()` or `StandardTags.hoverEvent()` to `MessageService`'s MiniMessage instance. Instead, keep `MessageService` read-only and strict, and add pure Adventure builders (`TpaPresenter`, `HelpPresenter`) that assemble `Component` buttons/rows using `ClickEvent.runCommand`, `ClickEvent.suggestCommand`, and `HoverEvent.showText` around localized strings fetched from `MessageService` (where user/player names are already escaped with `mini.escapeTags`).

*Alternative:* adding a second "trusted" MiniMessage parser in `MessageService`. Rejected: quoting player names inside `<click:run_command:'...'>` is brittle and creates a security boundary footgun if ever called on the wrong template.

### D2 — Extend `TeleportSession` with optional live `anchorPlayer` resolution

Add `TeleportReason.TPA` (using `teleport.warmup.tpa: 3` and `teleport.cooldown.tpa: 15`) and an optional `UUID anchorPlayer` on `TeleportSession` (plus `TeleportService.requestTpaTeleport(...)` and `TeleportPlatform.isPlayerDead/worldName` or `player(anchorUuid)`):

- **Role separation:** For `/tpa`, Mover = requester, Anchor = target. For `/tpahere`, Mover = target (accepter), Anchor = requester. All warmup, movement/damage/command cancellation, cooldown, safety, and `/back` origin checks bind exclusively to **Mover**.
- **Preflight & live arrival:** Preflight runs safety on Anchor's accept-time location; `executeArrival` re-fetches Anchor on the main thread, cancels cleanly if Anchor is offline/dead or crossed into a disallowed world (`tpa.cross-world: false`), updates `session.target()` to Anchor's live location, and runs the full `evaluateWithFallback` (`SEARCH`/`BLOCK`) before `teleportAsync`.
- **Mid-warmup anchor death/quit/world-change:** `PlayerConnectionListener.onQuit`, `DeathListener.onDeath`, and a `PlayerChangedWorldEvent` listener cancel any active session whose `anchorPlayer` matches the quitter/dead/world-changing player immediately rather than waiting for countdown expiry.

*Alternative:* freezing destination coordinates at `/tpaccept`. Rejected: if Anchor walks 15 blocks during a 3s warmup, Mover arrives at stale air or inside a door.

### D3 — Strict 1-outgoing / explicit-disambiguation TPA state + `/tptoggle` v1 scope

`TpaService` tracks pending requests in memory (`TpaRequest(id, direction, requesterUuid, requesterName, targetUuid, createdAt, expiresAt)`), swept once per second via `WarmupSampler` (plus lazy expiry checks on every command):

- **1 active outgoing request per requester:** prevents broadcast spam and keeps `/tpcancel` zero-argument.
- **Multiple incoming requests per target (cap `10`):** bare `/tpaccept` or `/tpdeny` when `> 1` pending refuses to guess and prints one clickable line per requester; `/tpaccept <player>` and clickable buttons disambiguate explicitly.
- **Transient retry:** if `/tpaccept` fails due to Mover's cooldown, combat tag, or unsafe target, keep the request pending so the player can retry before expiry.
- **`/tptoggle` persisted, `/tpblock` deferred:** persist `tpa_disabled` in `player_settings` so AFK/builder opt-outs survive restarts; mitigate target-specific spam with a 30s in-memory `(requester, target)` post-deny cooldown (`tpa.deny-cooldown-seconds`).
- **Combat guard:** add a small `CombatTracker` (`EntityDamageByEntityEvent` between players including player-shot projectiles) behind a `TeleportGuard` interface, defaulted to `tpa.combat-tag-seconds: 0` (off, matching base Marp) and configurable for PvP servers.

### D4 — Bed home via `PlayerSetSpawnEvent(Cause.BED)` with snapshot-home default

Listen to `com.destroystokyo.paper.event.player.PlayerSetSpawnEvent` (`MONITOR`, `ignoreCancelled = true`) for `Cause.BED` (and `Cause.RESPAWN_ANCHOR` when `bed-home.include-respawn-anchors: true`), excluding `bed-home.disabled-worlds`:

- **Why `PlayerSetSpawnEvent`:** fires day and night when clicking a bed (when `"Respawn point set"` appears), ignores Nether/End bed explosions, and ignores `/spawnpoint` (`Cause.COMMAND`) and spawn resets (`Cause.PLAYER_RESPAWN`).
- **Coordinates & metadata:** store `(bedX + 0.5, bedY + 0.5625, bedZ + 0.5)` with the player's current yaw/pitch, default icon matching the clicked bed block material (or `RESPAWN_ANCHOR`), and `is_bed = true`. Overwrites preserve custom display name, lore, and icon (`SqlDialect.upsertHomeSql`).
- **Limit exemption:** add `homes.is_bed INTEGER NOT NULL DEFAULT 0`. When `bed-home.counts-toward-limit: false` (default), limit checks and the `/homes` header count only `is_bed = 0` homes. When `bed-home.reserve-name: true`, manual `/sethome bed` is rejected unless the player opted out.
- **Respawn state default (`require-valid-bed: false`):** keep `"bed"` usable as a normal home even if broken/obstructed (handled safely by `SafetyService`). When `require-valid-bed: true` is configured, validate `player.getRespawnLocation(true) != null` ONLY **after** `SafetyService` / `world.getChunkAtAsync` finishes loading the target chunk so no sync chunk load occurs on the main thread.

### D5 — Schema `V2FeaturesSchema` (`favorites` + `player_settings` + `homes.is_bed`)

Add migration `V2` in `MigrationRunner.standard()`:

- `ALTER TABLE homes ADD COLUMN is_bed INTEGER NOT NULL DEFAULT 0;`
- `player_settings (player_uuid PRIMARY KEY, tpa_disabled, bed_home_disabled, updated_at)` with `SqlDialect.upsertPlayerSettingsSql()`.
- `favorites (player_uuid, target_kind, target_id, sort_order, created_at, PRIMARY KEY (player_uuid, target_kind, target_id))` plus `idx_favorites_target (target_kind, target_id)`.
- `SqlHomeRepository.delete` and `SqlWarpRepository.delete` execute `DELETE FROM favorites WHERE target_kind = ? AND target_id = ?` inside the **same** `storage.inTransaction(...)`.
- Because `SqlDialect.upsertHomeSql` and `upsertWarpSql` use `ON CONFLICT DO UPDATE`, `AUTOINCREMENT` IDs never change on coordinate updates, so favorites never break on overwrites or metadata edits.

### D6 — Favorites GUI via row-5 Star Mode + `EntryEditMenu` + `FavoritesMenu`

Preserve `Left = Teleport`, `Right = Edit`, `Shift-click = Delete` on entry slots. Add:

- **Nav row 5 reuse (`HomeListMenu`, `WarpListMenu`, `FavoritesMenu`):**
  - `HomeListMenu`: `+1` Favorites menu shortcut, `+2` Star Mode toggle, `+6` Auto-Bed toggle (`bed_home_disabled`).
  - `WarpListMenu`: `+1` Favorites menu shortcut, `+2` Star Mode toggle.
  - `FavoritesMenu` (`MenuType.FAVORITES`): `+1` Homes shortcut, `+2` Warps shortcut, `+3` Filter cycle (`All -> Homes -> Warps`), `+6` Star Mode toggle.
- **`EntryEditMenu`:** add `SLOT_FAVORITE = 4` (`NETHER_STAR`) for homes and admin warps.
- **Visuals:** starred items render `gui.favorite-badge` / `gui.favorite-lore` and `meta.setEnchantmentGlintOverride(true)` (Paper 1.21 API).
- **Sorting & `/home` fallback:** `HomeListMenu` and `WarpListMenu` pin starred entries first (`sort_order ASC`) before unstarred entries (`nameKey ASC, id ASC`). `HomeService.resolveForTeleport` with no args applies `DEFAULT_FIRST` (`default-name` -> top favorite home -> single-home -> open `/homes`) or `FAVORITE_FIRST`.

### D7 — Declarative `HelpCatalog` for `/marp` and `/marp help`

Extract a pure `HelpCatalog` describing every Marp command (`Category`: `HOMES`, `WARPS`, `TELEPORT`, `GENERAL_ADMIN`, syntax, `messages.yml` description key, example, permission node, feature-enabled predicate, and `ClickMode`: `RUN_COMMAND` vs `SUGGEST_COMMAND`). `MarpCommand` handles `0` args and `help [page]` (permission `marp.help`, console allowed), filters by `sender::hasPermission` and live `MarpConfig`, paginates at `help.entries-per-page` (7) using `Pagination`, and renders clickable category-grouped chat output with `[<< Previous]` / `[Next >>]` footer buttons.

## Risks / Trade-offs

- **[Risk] Anchor moves onto unsafe terrain (cliff/lava) in the last tick of TPA warmup** -> Mitigation: `executeArrival` re-runs full `SafetyService` (`SEARCH`/`BLOCK`) on Anchor's fresh live location right before `teleportAsync`.
- **[Risk] Strict bed validation (`require-valid-bed: true`) could stall the tick if `getRespawnLocation()` loads an unloaded chunk** -> Mitigation: run `world.getChunkAtAsync(cx, cz)` before invoking `getRespawnLocation(true)` on main thread.
- **[Risk] Changing `/marp` with 0 args from usage error to Help menu** -> Mitigation: noted as **BREAKING** in `proposal.md`; subcommands `reload`, `cancel`, `info`, `bed` remain unchanged and invalid subcommands still show usage.
- **[Risk] `PlayerSettingsCache` / `FavoriteCache` cold on join before async DB load finishes** -> Mitigation: consistent with `HomeCache`, return safe defaults until loaded and fall back to async DB query on explicit mutations.

## Migration Plan

1. `ConfigLoader` merges new `tpa`, `bed-home`, `favorites`, `help`, `teleport.warmup.tpa`, `teleport.cooldown.tpa`, and message keys into existing `config.yml` / `messages.yml` without overwriting user edits.
2. `MigrationRunner` applies `V2FeaturesSchema` inside a transaction on `Marp-DB` startup, bumping `marp_schema.version` to `2` (`homes.is_bed` defaults to `0`, existing rows preserved).
3. **Rollback:** If rolling back to v0.1, restore the pre-upgrade `marp.db` backup (v0.1 refuses to open schema version `2` per the downgrade guard).
