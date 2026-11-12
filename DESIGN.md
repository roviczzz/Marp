# Marp — Design Document

**Status:** v0.1 implemented  
**Target:** Paper 1.21.10 (Java 21)  
**Scope:** homes, warps, `/back`, GUI-first management, configurable messages  
**Non-goal for v0.1:** Spigot support, MySQL, cross-server sync, Folia

This document is the implementation contract. Each numbered section is self-contained so it can be handed to a coding agent independently.

---

## Contents

1. [Decisions at a glance](#1-decisions-at-a-glance)
2. [Architecture](#2-architecture)
3. [Command reference](#3-command-reference)
4. [Permissions](#4-permissions)
5. [Data model and schema](#5-data-model-and-schema)
6. [config.yml](#6-configyml)
7. [messages.yml](#7-messagesyml)
8. [GUI design](#8-gui-design)
9. [Teleport pipeline](#9-teleport-pipeline)
10. [Edge cases and failure modes](#10-edge-cases-and-failure-modes)
11. [Roadmap](#11-roadmap)
12. [Build and packaging](#12-build-and-packaging)
13. [Spigot compatibility notes](#13-spigot-compatibility-notes)
14. [Open questions](#14-open-questions)

---

## 1. Decisions at a glance

| Area | Decision | Why |
|---|---|---|
| Storage | SQLite via JDBC, single writer thread, WAL mode | Zero external services, safe concurrency, easy backup |
| MySQL | Repository + dialect seam from day one, implementation in v0.3 | Avoids a rewrite; no MySQL code until needed |
| GUI identity | Custom `InventoryHolder`, never inventory title | Titles are display data and can be spoofed/renamed |
| Home/warp identifiers | Lowercase, case-insensitive uniqueness (`name_key`), stable after creation | Commands stay predictable; display name is separate |
| Home limits | Check concrete `marp.homelimit.<n>` nodes from high to low, fallback config | Works with LuckPerms wildcards; avoids unreliable permission enumeration; keeps the `marp.homes.*` wildcard safe |
| Warmup | 3s default; one sampled task per session (no `PlayerMoveEvent`); cancels on movement, damaging hit, command, teleport, quit, death; ongoing tick damage is ignored | Prevents combat/escape abuse without being punitive or broken under poison/wither |
| Safety | `SEARCH` by default: 3-block spiral search, then block if nothing safe. Tests the player's collision box against block collision shapes | Better UX than hard-blocking, still safe; slabs, stairs, and snow are not false positives |
| `/back` | Newest of death point or pre-Marp-teleport location, not consumed | Matches player expectation; avoids ping-pong |
| Icon storage | Serialized `ItemStack` BLOB + material fallback, 16 KiB cap (a curated spec is proposed in §14 #22) | Full item fidelity without unbounded database rows |
| Text input | Chat prompt in v0.1; Paper Dialog API/anvil in v0.2 | Stable API now, nicer UX later |
| Threading | All JDBC on a dedicated executor; Bukkit only on main thread | Server tick safety |
| Dependencies | Paper API, SQLite JDBC, JUnit 5 (test only) | Lightweight, no GUI/config/command framework |

**Trade-off summary:** we accept a small amount of duplicate validation (commands and GUI) in exchange for clear layers. Commands are the source of truth for permissions and limits; GUI calls the same service methods, so rules cannot diverge.

---

## 2. Architecture

### 2.1 Package map

```
me.roviczzz.marp
├── Marp.java                     plugin lifecycle, wiring only
├── config/
│   ├── MarpConfig.java           typed accessors over config.yml
│   ├── MessageService.java       messages.yml, MiniMessage, placeholders
│   └── ConfigLoader.java         load/merge/version config files
├── command/
│   ├── PlayerCommand.java        shared permission/player-only helpers
│   ├── HomeCommand.java          /home
│   ├── SetHomeCommand.java       /sethome
│   ├── DelHomeCommand.java       /delhome
│   ├── HomesCommand.java         /homes
│   ├── WarpCommand.java          /warp
│   ├── SetWarpCommand.java       /setwarp
│   ├── DelWarpCommand.java       /delwarp
│   ├── WarpsCommand.java         /warps
│   ├── BackCommand.java          /back
│   └── MarpCommand.java          /marp reload|cancel|info|purge
├── model/
│   ├── LocationSnapshot.java     pure record: world, x, y, z, yaw, pitch
│   ├── Home.java                 immutable record
│   ├── Warp.java                 immutable record
│   ├── BackEntry.java            immutable record + BackKind
│   ├── IconSpec.java             material + optional serialized item
│   └── Page.java                 pure pagination value object
├── home/
│   ├── HomeService.java          create/update/delete/list, limit enforcement
│   ├── HomeLimitResolver.java    permission-node resolution (pure)
│   ├── HomeNameValidator.java    identifier validation (pure)
│   └── HomeCache.java            per-player name cache for tab completion
├── warp/
│   ├── WarpService.java          admin CRUD, permission filtering
│   ├── WarpNameValidator.java
│   └── WarpCache.java            in-memory snapshot for GUI/tab completion
├── back/
│   ├── BackService.java          record/resolve/consume back state
│   └── BackResolver.java         newest/death/teleport choice (pure)
├── teleport/
│   ├── TeleportService.java      orchestrates the whole pipeline
│   ├── TeleportRequest.java      UUID + target + reason + flags
│   ├── TeleportReason.java       HOME, WARP, BACK
│   ├── TeleportSession.java      in-flight warmup/cancel state
│   ├── CooldownService.java      per-player/per-command cooldowns
│   ├── SafetyService.java        Bukkit adapter around SafetyEvaluator
│   ├── SafetyEvaluator.java      pure rules over a BlockView
│   ├── SafeSpotFinder.java       spiral search over a BlockView
│   └── BlockView.java            testable seam for block reads
├── storage/
│   ├── Storage.java              lifecycle + repositories
│   ├── SqlStorage.java           JDBC implementation
│   ├── SqlDialect.java           SQLite/MySQL differences
│   ├── DbExecutor.java           single-thread executor + main-thread hop
│   ├── StorageException.java
│   ├── migration/
│   │   ├── Migration.java
│   │   └── V1InitialSchema.java
│   └── repo/
│       ├── HomeRepository.java
│       ├── WarpRepository.java
│       ├── BackRepository.java
│       └── PlayerRepository.java (optional metadata)
├── gui/
│   ├── Menu.java                 base menu contract
│   ├── MarpMenuHolder.java       identity + menu type + page + owner
│   ├── MenuManager.java          open/close/refresh, click routing
│   ├── MenuListener.java         InventoryClick/Drag/Close
│   ├── HomeListMenu.java
│   ├── WarpListMenu.java
│   ├── EntryEditMenu.java        display name, lore, icon
│   ├── DeleteConfirmMenu.java
│   ├── GuiItemFactory.java       icon/lore/filler/button rendering
│   └── Pagination.java           pure page math
├── input/
│   ├── TextInputService.java     interface
│   ├── ChatTextInput.java        v0.1 default
│   ├── DialogTextInput.java      v0.2 Paper Dialog API
│   ├── AnvilTextInput.java       v0.2 fallback
│   └── PendingInput.java         one input session per player
├── listener/
│   ├── PlayerConnectionListener.java  join cache load, quit cleanup
│   ├── WarmupListener.java            move/damage/command/teleport cancel
│   ├── DeathListener.java             record death point
│   ├── ExternalTeleportListener.java  optional /back recording
│   └── InputListener.java             chat input capture
└── util/
    ├── MiniMessageSanitizer.java      parse + strip interactive tags
    ├── LocationCodec.java             Location <-> LocationSnapshot
    ├── Scheduler.java                 run-on-main helper
    └── Text.java                      truncation, name keys, safe values
```

### 2.2 Responsibilities and boundaries

| Layer | May depend on | Must not do |
|---|---|---|
| Commands | services, `MessageService`, config | SQL, inventory building, teleport mechanics |
| Services | repositories, config, models | Blocking JDBC, direct GUI rendering |
| Repositories | JDBC, models | Bukkit `Player`/`World` objects, permissions |
| GUI | services, `MessageService`, main thread | SQL, teleport warmup logic |
| Teleport | services, safety, scheduler | SQL writes on main thread, holding `Player` in async callbacks |
| Listeners | services, scheduler | Business rules that belong in services |

`Marp.java` wires everything in `onEnable`, registers listeners, and shuts down cleanly. No feature logic lives there.

### 2.3 Runtime wiring

`onEnable()` order:

1. `saveDefaultConfig()`; load `config.yml` and `messages.yml` synchronously (small files, startup only).
2. Create `DbExecutor` and `SqlStorage`; call `storage.init()` asynchronously. Expose `storage.ready()` as a `CompletableFuture<Void>`.
3. Construct repositories, services, caches, `MenuManager`, `TextInputService`, and `TeleportService`.
4. Register listeners and commands. Commands are registered immediately; while storage is not ready they answer with `general.storage-not-ready`.
5. When storage is ready: load the warp cache, log the database path/type, and mark the plugin ready.
6. If initialization fails: log the full cause and disable the plugin. Do not run in a half-working state that could lose data.

`onDisable()` order:

1. Cancel warmup tasks and pending text inputs; close open Marp menus.
2. Shut down `DbExecutor` with `shutdown()` and a bounded `awaitTermination` (5s). Log a warning on timeout.
3. Close the JDBC connection; for SQLite run `PRAGMA wal_checkpoint(TRUNCATE)`.
4. Never hang server shutdown on a stuck database.

### 2.4 Threading model (non-negotiable)

- **Main thread:** all Bukkit reads/writes, inventory work, scheduling, teleports, block reads, permission checks, and player messages.
- **DB thread (`Marp-DB`):** every JDBC call, including connection opening, schema migration, queries, transactions, and WAL checkpoint.
- **Async chain:** repository methods return `CompletableFuture<T>`. Callbacks that need Bukkit use `Scheduler.runOnMain(...)`; `Bukkit.isPrimaryThread()` is checked in the helper.
- **Never** call `future.join()`, `future.get()`, `DriverManager.getConnection`, or `PreparedStatement.execute*` on the main thread.
- **Do not pass `Player`, `World`, `Location`, or `ItemStack` into DB tasks.** Pass UUIDs, `LocationSnapshot`, and already-serialized bytes. Re-resolve the player on the main thread.
- **Chunk work:** `world.getChunkAtAsync(cx, cz)` first; only after it completes may the main thread read blocks or call `teleportAsync`. Reading a block in an unloaded chunk can synchronously load a chunk and stall the tick.
- **One in-flight teleport per player.** A new request replaces the old session and cancels its countdown. Store sessions in a `ConcurrentHashMap<UUID, TeleportSession>`.
- **Async chat** (`AsyncChatEvent`) is off the main thread; cancel the event, copy the plain text, then schedule validation/application on main.

### 2.5 Dependency choices

- `io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT` — `compileOnly` (provided by the server).
- `org.xerial:sqlite-jdbc:3.54.1.0` — bundled at runtime (see §12).
- JUnit Jupiter + platform launcher — test only.
- No HikariCP, no config library, no command framework, no GUI library, no Adventure dependency (Paper bundles Adventure/MiniMessage). This keeps the jar small and the behavior explicit.

## 3. Command reference

### 3.1 Common conventions

- All commands are player-only except `/marp reload`, `/marp info`, `/marp purge`, and `/delwarp` (console may delete by name).
- Command names are lowercase; identifiers are normalized to lowercase before storage and lookup.
- `[name]` is optional only where stated. Omitting it uses `homes.default-name` (default `home`) for homes.
- Permission checks happen in code, not only through `plugin.yml`, so every denial can use `messages.yml`. The trade-off is that commands may appear in client command lists even when denied; tab completion filters them.
- Every command returns `true` from `onCommand` and never blocks. Async work chains into the teleport pipeline.
- `{prefix}` is not added automatically; message templates include it so it can be omitted per key.

### 3.2 Command table

| Command | Usage | Arguments | Permission | Default | Aliases | Tab completion | Example |
|---|---|---|---|---|---|---|---|
| `/sethome` | `/sethome [name]` | optional identifier | `marp.sethome` | true | — | own home names (to overwrite) | `/sethome base` |
| `/home` | `/home [name]` | optional identifier | `marp.home` | true | — | own home names | `/home base` |
| `/delhome` | `/delhome [name]` | optional identifier | `marp.delhome` | true | — | own home names | `/delhome base` |
| `/homes` | `/homes` | none | `marp.homes` | true | — | none | `/homes` |
| `/setwarp` | `/setwarp <name>` | required identifier | `marp.admin.setwarp` | op | — | none | `/setwarp spawn` |
| `/warp` | `/warp [name]` | optional identifier | `marp.warp` | true | — | visible warp names | `/warp spawn` |
| `/delwarp` | `/delwarp <name>` | required identifier | `marp.admin.delwarp` | op | — | all warp names | `/delwarp spawn` |
| `/warps` | `/warps` | none | `marp.warps` | true | — | none | `/warps` |
| `/back` | `/back` | none | `marp.back` | true | — | none | `/back` |
| `/tpa` | `/tpa <player>` | target player | `marp.tpa` | true | — | visible players | `/tpa Steve` |
| `/tpahere` | `/tpahere <player>` | target player | `marp.tpahere` | true | — | visible players | `/tpahere Steve` |
| `/tpaccept` | `/tpaccept [player]` | optional requester | `marp.tpaccept` | true | — | pending requesters | `/tpaccept Steve` |
| `/tpdeny` | `/tpdeny [player]` | optional requester | `marp.tpdeny` | true | — | pending requesters | `/tpdeny Steve` |
| `/tpcancel` | `/tpcancel` | none | `marp.tpcancel` | true | — | none | `/tpcancel` |
| `/tptoggle` | `/tptoggle [on\|off]` | optional toggle state | `marp.tptoggle` | true | — | `on`, `off` | `/tptoggle on` |
| `/bedtoggle` | `/bedtoggle [on\|off]` | optional toggle state | `marp.bedhome.toggle` | true | — | `on`, `off` | `/bedtoggle on` |
| `/favorites` | `/favorites` | none | `marp.favorites` | true | `/fav`, `/favs` | none | `/favorites` |
| `/marp` | `/marp [help\|cancel\|bed\|reload\|info] [page]` | subcommand | varies | `marp.help` | `/marpadmin` | subcommands, page numbers | `/marp help 1` |

### 3.3 Command behavior details

#### `/sethome [name]`

- No name: use `homes.default-name` (`home`). If that name already exists, update its location and report `home.updated`.
- With a name: validate against `homes.name-pattern` (default `^[a-z0-9_-]{1,32}$`). Reject empty, too long, spaces, and `.`/`..`.
- Existing name: update location, keep icon/display name/lore, update `updated_at`. This is an intentional overwrite, not a limit increase.
- New name: count existing homes. If `count >= limit`, deny with `home.limit-reached` and show the limit. If the player has `marp.homelimit.unlimited`, allow.
- Save the player's exact location (feet), yaw, and pitch. If the block under the feet is unsafe that is allowed — a home is a saved point, safety is checked on teleport, not on save.
- World must be loaded (it is, because the player is in it). Store the world name.
- If storage is not ready, fail with `general.storage-not-ready`; do not queue the command.
- Update `HomeCache` after a successful DB commit.

#### `/home [name]`

- No name: try `homes.default-name`. If absent and the player has exactly one home, use it. Otherwise open the `/homes` GUI and send `home.default-missing` (GUI-first fallback).
- With a name: resolve case-insensitively. Missing -> `home.not-found` with a hint to use `/homes`.
- Then enter the shared teleport pipeline (§9) with reason `HOME`.
- The home location is fetched asynchronously; do not trust a GUI snapshot for the actual coordinates.

#### `/delhome [name]`

- No name: use the default home. Missing -> `home.not-found`.
- Delete by ID and owner UUID; never by name alone, to avoid deleting another player's home if data is corrupted.
- No confirmation for the command (it is explicit). The GUI uses shift-click + confirmation because misclicks are easy.
- After deletion, update `HomeCache`; if the deleted home was the default, `/home` with no args falls back to the single-home rule.

#### `/homes`

- Requires `marp.homes`; opens `HomeListMenu`.
- Fetches the player's homes asynchronously, then opens on the main thread.
- If the player has no homes, still open the menu with an empty-state item and a hint to run `/sethome`.
- The menu header shows `count / limit`, or `count / ∞` for unlimited.

#### `/setwarp <name>`

- Player-only: warps are created at the player's current location.
- Admin-managed; requires `marp.admin.setwarp`.
- Validate the name. If it exists, update the location and `updated_at` but preserve icon, display name, lore, permission, and creator (`warps.overwrite-preserves-metadata: true`). Report `warp.updated`.
- New warp: store creator UUID, timestamps, default icon (`ENDER_PEARL` or configured), and `permission = NULL` (public).
- Update `WarpCache` only after commit.

#### `/warp [name]`

- No name: open `/warps` (GUI-first). This avoids the ambiguity of a "default warp".
- With a name: resolve from `WarpCache`; if missing, `warp.not-found`.
- If `warps.per-warp-permissions: true` and the warp has a non-null `permission`, require that node. Otherwise require `marp.warp.access.<nameKey>`. If denied, hide existence with `warp.not-found` when `warps.hide-unauthorized: true`, otherwise `warp.permission-denied`.
- Then enter the teleport pipeline with reason `WARP`.

#### `/delwarp <name>`

- Admin command, console-allowed. Resolve case-insensitively; missing -> `warp.not-found`.
- Delete by ID; update `WarpCache`; close any open warp menus on the next refresh (menus re-fetch on click).

#### `/warps`

- Requires `marp.warps`; opens `WarpListMenu`.
- Shows only warps the player may use. Admins with `marp.admin.setwarp` see all entries, including restricted ones (with a lock in the lore). This is a deliberate admin-visibility exception.
- Empty state: `warp.none`.

#### `/back`

- Requires `marp.back`; requires a stored death or teleport entry.
- Resolve the target per §9.8 (newest by default), then enter the pipeline with reason `BACK`.
- `/back` itself must **not** update the back state; otherwise repeated `/back` would ping-pong between two points.
- If the target equals the player's current location (within 1 block), send `back.already-there` and do not start a warmup.
- Cooldown and warmup use the `back` keys.

#### `/marp <subcommand>`

| Subcommand | Permission | Behavior |
|---|---|---|
| `/marp reload` | `marp.admin.reload` | Reload `config.yml` and `messages.yml`; cancel all warmups; close Marp menus; refresh warp cache. Do not reconnect the database. |
| `/marp cancel` | `marp.cancel` | Cancel the caller's warmup; safe to run when none exists. |
| `/marp info` | `marp.admin.info` | Show version, storage type/path, home/warp counts, and cache status. |
| `/marp purge <homes\|warps\|back> [world]` | `marp.admin.purge` | v0.2. Remove records whose world folder no longer exists (§10). Never deletes records for merely unloaded worlds. |

### 3.4 Tab completion rules

- Completion runs on the main thread and must not touch the database. Use `HomeCache` / `WarpCache` snapshots.
- `/home`, `/delhome`: suggest the player's home identifiers, filtered by the typed prefix, sorted case-insensitively, max 50.
- `/sethome`: suggest existing names only to make overwrite discoverable (same filter).
- `/warp`: suggest visible warp identifiers; filter by per-warp permission; never leak hidden warps.
- `/delwarp`: suggest all warp identifiers (admin context).
- `/marp`: suggest `reload`, `cancel`, `info`, `purge` according to permission.
- `/homes`, `/warps`, `/back`: return an empty list.
- If a cache is cold (player just joined), return an empty list and trigger an async load; do not block.

### 3.5 plugin.yml command registration

List every command in `plugin.yml` with `description` and `usage`. Do **not** set `permission:` there if you want denial messages from `messages.yml`; enforce in code. If you prefer Bukkit's built-in denial, set the permission and accept its fixed message — pick one and apply it consistently.

```yaml
commands:
  sethome:
    description: Set a home at your current location
    usage: /sethome [name]
  home:
    description: Teleport to one of your homes
    usage: /home [name]
  delhome:
    description: Delete one of your homes
    usage: /delhome [name]
  homes:
    description: Open the homes menu
    usage: /homes
  setwarp:
    description: Create or move a server warp
    usage: /setwarp <name>
  warp:
    description: Teleport to a warp
    usage: /warp [name]
  delwarp:
    description: Delete a server warp
    usage: /delwarp <name>
  warps:
    description: Open the warps menu
    usage: /warps
  back:
    description: Return to your last death or teleport point
    usage: /back
  marp:
    description: Marp administration
    usage: /marp <reload|cancel|info|purge>
    aliases: [marpadmin]
```

## 4. Permissions

### 4.1 Permission table

| Node | Default | Grants |
|---|---|---|
| `marp.use` | true | Parent for all player-facing commands; no behavior by itself |
| `marp.home` | true | `/home`, teleport to own homes |
| `marp.sethome` | true | `/sethome` |
| `marp.delhome` | true | `/delhome`, delete own home via GUI |
| `marp.homes` | true | `/homes` GUI, edit own home display name/lore/icon |
| `marp.warp` | true | `/warp`, teleport to permitted warps |
| `marp.warps` | true | `/warps` GUI |
| `marp.back` | true | `/back` |
| `marp.cancel` | true | `/marp cancel` |
| `marp.tpa` | true | `/tpa <player>` |
| `marp.tpahere` | true | `/tpahere <player>` |
| `marp.tpaccept` | true | `/tpaccept` |
| `marp.tpdeny` | true | `/tpdeny` |
| `marp.tpcancel` | true | `/tpcancel` |
| `marp.tptoggle` | true | `/tptoggle` |
| `marp.bedhome` | true | Auto-bed home capture |
| `marp.bedhome.toggle` | true | `/bedtoggle` and `/marp bed` |
| `marp.favorites` | true | `/favorites` and starring |
| `marp.help` | true | `/marp` and `/marp help` |
| `marp.favoritelimit.<n>` | false | Favorite limit = highest matching `n` |
| `marp.favoritelimit.unlimited` | op | No favorite limit (capped by absolute-max) |
| `marp.homelimit.<n>` | false | Home limit = highest matching `n` (e.g. `marp.homelimit.5`) |
| `marp.homelimit.unlimited` | op | No home limit |
| `marp.warp.access.<name>` | false | Dynamic per-warp access when `warps.per-warp-permissions: true`; not listed in `plugin.yml` |
| `marp.warp.access.*` | false | Wildcard for all dynamic per-warp nodes (LuckPerms/Bukkit check-time wildcard) |
| `marp.admin.setwarp` | op | `/setwarp`, edit any warp in the GUI |
| `marp.admin.delwarp` | op | `/delwarp`, delete any warp |
| `marp.admin.editwarp` | op | Edit warp display name/lore/icon without moving it (v0.2) |
| `marp.admin.reload` | op | `/marp reload` |
| `marp.admin.info` | op | `/marp info` |
| `marp.admin.purge` | op | `/marp purge` (v0.2) |
| `marp.admin.homes.other` | op | View another player's homes (v0.2, optional) |
| `marp.bypass.cooldown` | op | Ignore teleport cooldowns |
| `marp.bypass.warmup` | op | Skip warmup countdown |
| `marp.bypass.safety` | op | Skip safe-location checks |
| `marp.*` | op | Parent: `marp.use`, `marp.admin.*`, `marp.bypass.*`, `marp.homelimit.unlimited` |
| `marp.admin.*` | op | Parent for admin nodes |
| `marp.bypass.*` | op | Parent for bypass nodes |

**Namespace rule:** limit nodes and per-warp nodes live in their own subtrees (`marp.homelimit.*`, `marp.warp.access.*`), never as children of the command node. This matters because LuckPerms expands a granted wildcard against every descendant, so `marp.homes.*` used to grant both the GUI and a 100-home limit, and `marp.warp.*` granted warp access without granting `/warp` itself. With the split namespaces:

- `marp.homes.*` means "the homes GUI and editing" and nothing else.
- `marp.homelimit.*` is a wildcard that would resolve to `max-limit-node-scan`; do not grant it. Use `marp.homelimit.unlimited` for "no limit".
- `marp.warp.*` means "the warp command and GUI"; all-warp access is `marp.warp.access.*`.

Do not register numeric or per-warp nodes in `plugin.yml`; document the convention instead. Registering a numeric parent makes Bukkit's own wildcard handling grant it implicitly.

### 4.2 Home limit resolution

Algorithm (pure, testable):

1. If `player.hasPermission("marp.homelimit.unlimited")` -> unlimited (`Integer.MAX_VALUE`).
2. For `n = homes.max-limit-node-scan` down to `1`: if `player.hasPermission("marp.homelimit." + n)` -> return `n`.
3. Otherwise return `homes.default-limit` (default `3`).

Why check concrete nodes instead of iterating `getEffectivePermissions()`:

- LuckPerms and Bukkit evaluate wildcards at check time, so `hasPermission("marp.homelimit.5")` correctly sees a granted `marp.homelimit.*` or `*`.
- `getEffectivePermissions()` only contains known/attached permissions; numeric nodes are rarely registered, so enumeration is unreliable.
- The scan is bounded (100 checks by default) and cheap enough to run on command use. Do not cache limits across permission changes; recompute per command and when a menu opens.
- If a server needs `marp.homelimit.500`, raise `max-limit-node-scan`; otherwise the node is invisible to the resolver.

The resolved limit is a *policy* value, not a data guarantee: it bounds new creations only. Existing rows above the limit stay.

Limit semantics:

- Limit `0` means the player cannot create new homes; existing homes are kept.
- Lowering a limit after homes exist keeps existing homes but blocks new ones. This is intentional: never delete player data because a rank changed.
- Over-limit players see `count / limit` in red in the GUI and can delete homes to get back under the limit.
- `sethome` on an existing name is always allowed, even at the limit, because it updates rather than creates.

### 4.3 Example LuckPerms grants

```
/lp group default permission set marp.homelimit.3 true
/lp group vip permission set marp.homelimit.10 true
/lp group vip permission set marp.warp.access.spawn true
/lp group admin permission set marp.* true
/lp user Steve permission set marp.bypass.cooldown true
```

## 5. Data model and schema

### 5.1 Model objects

All models are immutable records and contain no Bukkit types except `LocationSnapshot` (a pure record). Conversions happen at the service boundary.

#### `LocationSnapshot`

```
String world      // Bukkit World#getName, not a UUID (worlds have no stable UUID)
double x, y, z
float  yaw, pitch
```

- `toLocation()` runs on the main thread and returns `null` if `Bukkit.getWorld(world)` is null. Never auto-load a world for a teleport; report `general.world-missing`.
- Store coordinates exactly as the player stood. Do not round; the safety check reads collision shapes at the block coordinates, and `center-on-block` can normalize X/Z at teleport time.

#### `Home`

```
long id
UUID owner
String name          // normalized identifier, stable after creation
String nameKey       // lower-case name, unique per owner
LocationSnapshot location
IconSpec icon
String displayName   // MiniMessage raw, nullable -> falls back to name
List<String> lore    // 0..4 lines, MiniMessage raw
long rev             // optimistic lock, incremented on every update
long createdAt       // epoch millis
long updatedAt       // epoch millis, display only
```

#### `Warp`

```
long id
String name          // normalized identifier, stable after creation
String nameKey       // lower-case, globally unique
LocationSnapshot location
IconSpec icon
String displayName
List<String> lore
String permission    // nullable; when set and per-warp permissions enabled
UUID createdBy       // nullable (console/legacy)
long rev             // optimistic lock, incremented on every update
long createdAt
long updatedAt       // display only
```

#### `IconSpec`

```
String material      // Bukkit Material name, always present as fallback
byte[] itemBytes     // nullable Paper ItemStack#serializeAsBytes, max 16 KiB
```

The material is the durable fallback. If item bytes are missing, corrupt, too large, or from an incompatible Minecraft version, render the material instead. Never let an icon failure break a menu.

#### `BackEntry`

```
UUID player
BackKind kind        // DEATH or TELEPORT
LocationSnapshot location
String cause         // "DEATH", "LAVA", "PLAYER:Steve", "MARPTELEPORT", ...
long createdAt
```

Two rows may exist per player (one per kind). `/back` picks one by policy (§9.8). Keeping both is what allows "last death" and "last teleport" to coexist without a stack of history.

### 5.2 SQLite schema (v1)

Database file: `plugins/Marp/marp.db`. Open with `jdbc:sqlite:<absolute path>`. Apply:

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS marp_schema (
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS homes (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid    TEXT    NOT NULL,
    name           TEXT    NOT NULL,
    name_key       TEXT    NOT NULL,
    world          TEXT    NOT NULL,
    x              REAL    NOT NULL,
    y              REAL    NOT NULL,
    z              REAL    NOT NULL,
    yaw            REAL    NOT NULL,
    pitch          REAL    NOT NULL,
    icon_material  TEXT    NOT NULL,
    icon_item      BLOB,
    display_name   TEXT,
    lore           TEXT,
    rev            INTEGER NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    UNIQUE (player_uuid, name_key)
);

-- The UNIQUE constraint already provides the (player_uuid, name_key) index used
-- for player lookups. Do not add a second index on the same columns.

CREATE TABLE IF NOT EXISTS warps (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    name_key       TEXT    NOT NULL UNIQUE,
    world          TEXT    NOT NULL,
    x              REAL    NOT NULL,
    y              REAL    NOT NULL,
    z              REAL    NOT NULL,
    yaw            REAL    NOT NULL,
    pitch          REAL    NOT NULL,
    icon_material  TEXT    NOT NULL,
    icon_item      BLOB,
    display_name   TEXT,
    lore           TEXT,
    permission     TEXT,
    created_by     TEXT,
    rev            INTEGER NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS back_locations (
    player_uuid  TEXT    NOT NULL,
    kind         TEXT    NOT NULL,   -- DEATH | TELEPORT
    world        TEXT    NOT NULL,
    x            REAL    NOT NULL,
    y            REAL    NOT NULL,
    z            REAL    NOT NULL,
    yaw          REAL    NOT NULL,
    pitch        REAL    NOT NULL,
    cause        TEXT,
    created_at   INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, kind)
);
```

Notes:

- `lore` is stored as newline-separated text; normalize CRLF to `\n` before writing.
- `name_key` is the lower-case identifier. `name` preserves the normalized display spelling but identifiers are lower-case anyway; keeping both makes future case-preserving renames possible without a migration.
- UUIDs are canonical strings (`UUID#toString`) for portability. MySQL may use `CHAR(36)`; do not use SQLite-specific UUID functions.
- Timestamps are epoch millis, stored as `INTEGER`/`BIGINT`; never use database-local time.
- `icon_item` is a BLOB. SQLite handles it natively; MySQL uses `BLOB` (max 64 KiB, above the 16 KiB app cap).
- `rev` is the optimistic lock (§5.4). `updated_at` is display data and must never be used as a concurrency check: two writes landing in the same millisecond would both match the condition and one edit would vanish.
- `back_locations` has no `rev`. A back entry is a whole-row upsert where last-write-wins is the correct semantic.
- No foreign keys and no profile table in v1: homes and warps must survive for offline players and after a world is removed. Ownership is `player_uuid` alone. If an admin listing ever needs last-seen names, add `player_profiles` in its own migration; no v0.1 feature depends on it.

### 5.3 Repository contract

```java
interface HomeRepository {
    CompletableFuture<List<Home>> findByPlayer(UUID player);
    CompletableFuture<Optional<Home>> findByPlayerAndName(UUID player, String nameKey);
    CompletableFuture<Optional<Home>> findById(long id);
    CompletableFuture<Integer> countByPlayer(UUID player);
    CompletableFuture<Home> insert(Home home);
    CompletableFuture<Boolean> update(Home home, long expectedRev);
    CompletableFuture<Boolean> delete(long id, UUID owner);
}
```

`WarpRepository` adds `findAll()`, `findByKey()`, `findPage(offset, limit)`, `count()`, `insert`, `update`, `delete`. `BackRepository` has `find(UUID, BackKind)`, `findAll(UUID)`, `upsert(BackEntry)`, `delete(UUID, BackKind)`.

Repository methods never accept `Player`. Services do permission/limit checks; repositories do not.

### 5.4 Upserts and optimistic locking

- **SQLite:** `INSERT ... ON CONFLICT (player_uuid, name_key) DO UPDATE SET ...`.
- **MySQL:** `INSERT ... ON DUPLICATE KEY UPDATE ...`.
- Put this difference behind `SqlDialect.upsertHome()` / `upsertWarp()` / `upsertBack()`.
- GUI edits use optimistic locking on the revision counter: `UPDATE ... SET rev = rev + 1 WHERE id = ? AND rev = ?`. Zero rows means someone changed or deleted the row. Respond with `general.conflict`, re-fetch, and refresh the menu. Never use `updated_at` for this: with millisecond timestamps, two edits in the same tick can both match and the second silently overwrites the first.
- Deletion re-checks ownership as well as ID (`WHERE id = ? AND player_uuid = ?`), and the menu re-validates that the row still has the expected `name_key` before acting. `AUTOINCREMENT` guarantees IDs are never recycled, so a stale menu row cannot resolve to a different newly created entry.
- Commands (`sethome`, `setwarp`) may last-write-wins on coordinates because they are explicit location updates; name uniqueness is still enforced by the database, and a `rev` bump still happens.
- A unique-constraint violation during creation (race between two requests) maps to the normal "already exists/updated" path, not a storage error.

### 5.5 Migrations

- `marp_schema.version` starts at `1`.
- `Migration` list is ordered; each migration runs in its own transaction on the DB thread.
- On startup, read the version, apply all newer migrations, then write the new version.
- If the database version is newer than the plugin, refuse to start (downgrade risk) with a clear log message.
- `config.yml` and `messages.yml` also carry `config-version` / `messages-version`; missing keys are merged from bundled defaults without overwriting user values.

### 5.6 MySQL path (v0.3)

Do not implement in v0.1, but keep these seams:

- `storage.type: sqlite | mysql`; MySQL config block already present in `config.yml`.
- `SqlDialect` owns DDL types, upsert syntax, and `LIMIT/OFFSET` (same syntax, verify).
- MySQL uses a small HikariCP pool (or equivalent) instead of the single SQLite connection; `DbExecutor` remains the abstraction, but MySQL operations may run on a pool-backed executor.
- Cross-server cache invalidation is a separate v0.3 problem: either poll `updated_at` every N seconds or use plugin messaging. Do not pretend SQLite's in-memory cache is multi-server safe.
- A `marp export/import` command can move SQLite rows to MySQL later; no schema rewrite is needed.

## 6. config.yml

Defaults below are deliberately conservative for a survival server. Every value is read through `MarpConfig`; no magic numbers in code.

```yaml
# Marp configuration. Reload with /marp reload.
# Changing storage settings requires a full server restart.
config-version: 1

storage:
  # sqlite (default) or mysql (v0.3)
  type: sqlite
  sqlite:
    file: marp.db
    busy-timeout-ms: 5000
    wal: true
  mysql:
    host: localhost
    port: 3306
    database: marp
    username: marp
    password: ''
    table-prefix: ''
    pool-size: 6
    use-ssl: true

homes:
  enabled: true
  default-name: home
  default-limit: 3
  # Highest marp.homelimit.<n> checked; raise if you grant larger numbers.
  max-limit-node-scan: 100
  name-pattern: '^[a-z0-9_-]{1,32}$'
  case-insensitive: true
  # Identifier names that cannot be used (case-insensitive). Empty by default.
  reserved-names: []
  # Homes are global by default; allow teleporting across worlds.
  cross-world: true

warps:
  enabled: true
  name-pattern: '^[a-z0-9_-]{1,32}$'
  case-insensitive: true
  # When true, warps require marp.warp.access.<name>, or the warp's stored node.
  per-warp-permissions: false
  # /setwarp on an existing name moves it but keeps icon/name/lore.
  overwrite-preserves-metadata: true
  # Denied players see "warp not found" instead of "no permission".
  hide-unauthorized: true

teleport:
  # One repeating task per tick interval samples every online player's position.
  # It drives warmup cancellation, the countdown, and the /back last-ground record.
  sample-interval-ticks: 4
  warmup:
    home: 3
    warp: 3
    back: 3
    cancel-on-move: true
    # 3D distance in blocks; yaw/pitch changes are ignored.
    movement-tolerance: 0.5
    cancel-on-damage: true
    cancel-on-command: true
    # Commands that do NOT cancel warmup (without the leading slash).
    allowed-commands: []
    cancel-on-teleport: true
    cancel-on-quit: true
    cancel-on-death: true
    # Damage causes that do NOT cancel the countdown. These are ongoing states,
    # not a player choosing to fight or flee; without this, a poisoned player
    # could never teleport. All other causes (ENTITY_ATTACK, PROJECTILE, FALL,
    # LAVA, VOID, MAGIC, ...) cancel.
    ignored-damage-causes:
      - POISON
      - WITHER
      - FIRE_TICK
      - STARVATION
      - DROWNING
      - CONTACT
      - SUFFOCATION
    countdown-actionbar: true
    countdown-sound: true
    title-on-start: false
  cooldown:
    home: 5
    warp: 10
    back: 30
    # v0.1 keeps cooldowns in memory; they reset on restart.
    persist: false
  safety:
    enabled: true
    # BLOCK: deny. SEARCH: find a nearby safe spot, else deny. WARN: teleport anyway.
    mode: SEARCH
    search-radius: 3
    search-vertical: 3
    max-fall-distance: 3
    allow-water: true
    check-world-border: true
    # Center X/Z on the block to avoid standing on an edge.
    center-on-block: true
    hazards:
      - LAVA
      - FIRE
      - SOUL_FIRE
      - MAGMA_BLOCK
      - CAMPFIRE
      - SOUL_CAMPFIRE
      - POWDER_SNOW
      - CACTUS
      - SWEET_BERRY_BUSH
      - WITHER_ROSE
  dismount-vehicle: true
  close-inventory: true
  reset-fall-distance: true

back:
  enabled: true
  record-deaths: true
  record-marp-teleports: true
  # Off by default: external plugin teleports are noisy and can surprise players.
  record-external-teleports: false
  # NEWEST | DEATH | TELEPORT
  prefer: NEWEST
  persist: true
  # false: /back can be used repeatedly (cooldown still applies).
  consume: false
  cross-world: true
  # What to do when the chosen target cannot be reached safely: a void or lava
  # death has no safe spot anywhere near it.
  #   LAST_GROUND: fall back to the player's last recorded on-ground position.
  #   BLOCK:       refuse with teleport.unsafe-no-spot (old behavior).
  unreachable-fallback: LAST_GROUND

gui:
  rows: 6
  filler-material: GRAY_STAINED_GLASS_PANE
  sounds: true
  allow-minimessage: true
  # Applied at parse time through a resolver whitelist, not by editing the
  # component tree afterwards: click, hover, insertion, selector, score, and nbt
  # tags are never resolvable and render as literal text.
  strip-interactive-tags: true
  # Visible (plain-text) limits. MiniMessage tags do not count toward these.
  max-display-name-length: 32
  max-lore-lines: 4
  max-lore-line-length: 64
  # Raw caps, so formatting tags cannot be used to smuggle unbounded text past
  # the visible limits above.
  max-display-name-raw: 128
  max-lore-line-raw: 256
  max-icon-bytes: 16384
  confirmation-seconds: 10
  input-timeout-seconds: 60
```

Behavioral notes:

- `rows` must be `6` for the documented 45-slot content layout. `GuiItemFactory` derives page size as `(rows - 1) * 9` if this is ever changed; nav slots are recomputed, not hardcoded.
- `teleport.warmup.*` values are seconds. `0` disables the countdown for that action; no session task is scheduled for a zero-length warmup.
- `ignored-damage-causes` is parsed against `EntityDamageEvent.DamageCause` by name, case-insensitively. Unknown entries log a warning at load and are skipped; never fail startup over a typo in a damage cause.
- `sample-interval-ticks` drives one global repeating task. Keep it at 4 or lower for warmups under 2s; raising it makes movement cancellation visibly late.
- `back.unreachable-fallback` is `LAST_GROUND` or `BLOCK`, parsed like `back.prefer`. `LAST_GROUND` only applies when the target is unreachable (void/lava, or no safe spot found); it never silently redirects a normal `/back`.
- Bypass permissions override warmup/cooldown/safety independently.
- `safety.mode: WARN` still runs the check so the warning can name the hazard; it then teleports anyway. `marp.bypass.safety` skips the check entirely.
- `back.prefer` is an enum parsed case-insensitively. Invalid values fall back to `NEWEST` with a warning at load.
- Reloading does not reconnect storage. If `storage.type` changed, log "restart required" and keep the old connection.
- `homes.enabled`, `warps.enabled`, and `back.enabled` are feature switches. Disabled features answer with `general.feature-disabled`; their commands are not registered for tab completion. Existing rows are left untouched.
- If `homes.cross-world: false`, a home in another world is denied with `general.cross-world-disabled`; the same flag exists as `back.cross-world`.

## 7. messages.yml

### 7.1 Default layout

All player-facing text lives here. The bundled file is the fallback; missing keys are filled from the jar on load, and the on-disk file is never overwritten wholesale.

```yaml
messages-version: 1
prefix: '<gradient:#00c6ff:#0072ff><bold>Marp</bold></gradient> <dark_gray>»</dark_gray> '

general:
  no-permission: '{prefix}<red>You do not have permission to do that.'
  player-only: '{prefix}<red>This command can only be used by a player.'
  storage-not-ready: '{prefix}<red>Storage is still starting. Try again in a moment.'
  storage-error: '{prefix}<red>A storage error occurred. Please tell an administrator.'
  conflict: '{prefix}<yellow>That entry changed while you were editing it. Reopening the menu.'
  world-missing: '{prefix}<red>The world <white>{world}</white> is not loaded.'
  reloaded: '{prefix}<green>Configuration and messages reloaded.'
  feature-disabled: '{prefix}<red>That feature is disabled on this server.'
  cross-world-disabled: '{prefix}<red>That location is in another world, and cross-world teleporting is disabled.'
  invalid-usage: '{prefix}<red>Usage: <white>{usage}'
  cancelled: '{prefix}<yellow>Cancelled.'
  unknown-player: '{prefix}<red>Unknown player: <white>{player}</white>.'

home:
  set: '{prefix}<green>Home <white>{name}</white> set.'
  updated: '{prefix}<green>Home <white>{name}</white> updated.'
  deleted: '{prefix}<green>Home <white>{name}</white> deleted.'
  not-found: '{prefix}<red>No home named <white>{name}</white>. Use <white>/homes</white> to browse.'
  none: '{prefix}<gray>You have no homes yet. Use <white>/sethome</white>.'
  limit-reached: '{prefix}<red>You have reached your home limit (<white>{limit}</white>). Delete one first.'
  limit-zero: '{prefix}<red>Your rank does not allow homes.'
  default-missing: '{prefix}<yellow>No default home. Opening your homes menu.'
  list-header: '{prefix}<gray>Homes: <white>{count}</white>'
  name-invalid: '{prefix}<red>Names must match <white>{pattern}</white>.'
  name-too-long: '{prefix}<red>That name is too long (max <white>{max}</white>).'
  name-reserved: '{prefix}<red><white>{name}</white> is reserved.'

warp:
  set: '{prefix}<green>Warp <white>{name}</white> created.'
  updated: '{prefix}<green>Warp <white>{name}</white> moved.'
  deleted: '{prefix}<green>Warp <white>{name}</white> deleted.'
  not-found: '{prefix}<red>No warp named <white>{name}</white>.'
  none: '{prefix}<gray>There are no warps yet.'
  permission-denied: '{prefix}<red>You do not have access to that warp.'
  name-invalid: '{prefix}<red>Warp names must match <white>{pattern}</white>.'

back:
  no-location: '{prefix}<red>You have no death or teleport point to return to.'
  already-there: '{prefix}<gray>You are already there.'
  target-death: '{prefix}<gray>Returning to your death point in <white>{world}</white>.'
  target-teleport: '{prefix}<gray>Returning to your previous location in <white>{world}</white>.'
  fallback-last-ground: '{prefix}<gray>Your death point cannot be reached safely. Returning to your last safe ground.'
  fallback-none: '{prefix}<red>Your death point cannot be reached safely, and no safe ground was recorded.'

teleport:
  warmup: '{prefix}<gray>Teleporting in <white>{seconds}s</white>. Do not move.'
  warmup-actionbar: '<gray>Teleporting in <white>{seconds}s</white>'
  warmup-title: '<green>Teleporting'
  moved: '{prefix}<red>Teleport cancelled: you moved.'
  damaged: '{prefix}<red>Teleport cancelled: you took damage.'
  command: '{prefix}<red>Teleport cancelled: you used a command.'
  teleported-elsewhere: '{prefix}<red>Teleport cancelled: you were teleported.'
  unsafe: '{prefix}<red>That location is unsafe (<white>{reason}</white>).'
  unsafe-no-spot: '{prefix}<red>No safe spot was found nearby.'
  adjusted: '{prefix}<yellow>Moved to a nearby safe spot.'
  world-border: '{prefix}<red>That location is outside the world border.'
  failed: '{prefix}<red>Teleport failed. The world may have been unloaded.'
  teleported: '{prefix}<green>Teleported to <white>{name}</white>.'
  cooldown: '{prefix}<red>Wait <white>{seconds}s</white> before using that again.'
  same-world: '{prefix}<gray>Teleporting...'

gui:
  title-homes: '<dark_aqua>Your Homes</dark_aqua>'
  title-warps: '<dark_aqua>Warps</dark_aqua>'
  page-info: '<gray>Page <white>{page}</white>/<white>{pages}</white> <dark_gray>•</dark_gray> <white>{count}</white> entries'
  prev-page: '<yellow>Previous page'
  next-page: '<yellow>Next page'
  close: '<red>Close'
  set-home-here: '<green>Set home here'
  no-homes: '<gray>No homes yet'
  no-warps: '<gray>No warps yet'
  loading: '<gray>Loading...'
  home-lore-world: '<gray>World: <white>{world}</white>'
  home-lore-coords: '<gray>Location: <white>{x}, {y}, {z}</white>'
  home-lore-created: '<gray>Created: <white>{date}</white>'
  actions: '<yellow>Left-click</yellow> <gray>teleport • <yellow>Right-click</yellow> <gray>edit • <yellow>Shift-click</yellow> <gray>delete'
  warp-locked: '<red>Locked'
  warp-help: '<gray>Warps are managed by staff.'
  edit-name: '<yellow>Display name'
  edit-name-lore: '<gray>Current: <white>{name}</white>'
  edit-lore: '<yellow>Description'
  edit-lore-lore: '<gray>Click to edit up to {lines} lines'
  edit-icon: '<yellow>Icon'
  edit-icon-lore: '<gray>Hold an item and click here'
  edit-teleport: '<green>Teleport'
  edit-back: '<yellow>Back'
  edit-delete: '<red>Delete'
  confirm-title: '<dark_red>Delete {name}?'
  confirm-yes: '<green>Confirm delete'
  confirm-no: '<red>Cancel'
  confirm-lore: '<gray>This cannot be undone.'
  deleted: '<green>Deleted <white>{name}</white>.'
  icon-set: '<green>Icon updated.'
  icon-invalid: '<red>That item cannot be used as an icon.'
  icon-too-large: '<red>That item carries too much data to store.'
  input-name-prompt: '{prefix}<gray>Type a display name in chat, or <white>cancel</white>.'
  input-lore-prompt: '{prefix}<gray>Type up to <white>{lines}</white> lines separated by <white>|</white>, or <white>cancel</white>.'
  input-cancelled: '{prefix}<yellow>Input cancelled.'
  input-timeout: '{prefix}<yellow>Input timed out.'
  input-invalid: '{prefix}<red>Invalid input: <white>{reason}</white>.'

admin:
  info: '{prefix}<gray>Marp <white>{version}</white> • storage <white>{storage}</white> • homes <white>{homes}</white> • warps <white>{warps}</white>'
  purge-start: '{prefix}<gray>Scanning {type} for missing worlds...'
  purge-done: '{prefix}<green>Removed <white>{count}</white> invalid {type} entries.'
  purge-none: '{prefix}<gray>No invalid {type} entries found.'
  purge-confirm: '{prefix}<red>This deletes data. Re-run with <white>--confirm</white>.'
```

### 7.2 Formatting and placeholder rules

- **Placeholders use `{name}`, not `<name>`.** `<...>` is MiniMessage syntax; `{...}` is replaced before parsing. This is the single easiest mistake to make in this plugin.
- Values are inserted as `Component`s (or escaped text) by `MessageService`; do not concatenate raw user input into a MiniMessage string. Home/warp identifiers are regex-safe, but world names and player names are not.
- `{prefix}` is a normal placeholder so any message can opt out of the prefix. Reload replaces it globally.
- Display names and lore are intentionally parsed as MiniMessage when `gui.allow-minimessage: true`. Everything else is escaped text.
- Legacy `&` color codes are **not** translated. Use MiniMessage (`<red>`, `<gradient:...>`). This avoids two competing color systems.
- Parse errors: log the key and the raw text once, send a plain-text fallback, and keep the previous parsed value on reload. Never crash a command because a config string is malformed.
- All message keys shown above are required by v0.1; new keys should be added to the bundled file first, then merged on load.
- Date formatting is controlled by `MessageService` (default `yyyy-MM-dd HH:mm`), using the server timezone; store timestamps in UTC epoch millis.

## 8. GUI design

### 8.1 Menu framework

- Every Marp inventory is created with `Bukkit.createInventory(holder, size, title)` where `holder` is a `MarpMenuHolder`.
- `MarpMenuHolder` contains: `MenuType` (`HOME_LIST`, `WARP_LIST`, `ENTRY_EDIT`, `DELETE_CONFIRM`), owner UUID, page, and the entry ID being edited/deleted. It never contains mutable domain data.
- `MenuListener` handles `InventoryClickEvent`, `InventoryDragEvent`, and `InventoryCloseEvent`:
  - Identify menus by `event.getInventory().getHolder() instanceof MarpMenuHolder`, never by title.
  - Cancel every click and drag inside a Marp menu. Marp menus are display/action surfaces, not storage.
  - If the click is in the player's own inventory while a Marp menu is open, cancel it too (prevents shift-click item movement/dupes).
  - Ignore clicks from any player other than the holder owner; close the menu.
  - Ignore `NUMBER_KEY`, `SWAP_OFFHAND`, `DOUBLE_CLICK`, `DROP`, and `CONTROL_DROP`.
  - Debounce actions per player (~200 ms) so double-clicks cannot start two teleports.
- `MenuManager` owns opening/closing/refreshing. Menus are rebuilt on the main thread from immutable snapshots; no menu holds a live `Home`/`Warp` after an async refresh.

### 8.2 Homes list (54 slots, 6 rows)

```
       0   1   2   3   4   5   6   7   8
     ┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
  0  │ H │ H │ H │ H │ H │ H │ H │ H │ H │   slots 0-44: entries
  1  │ H │ H │ H │ H │ H │ H │ H │ H │ H │   45 entries per page
  2  │ H │ H │ H │ H │ H │ H │ H │ H │ H │
  3  │ H │ H │ H │ H │ H │ H │ H │ H │ H │
  4  │ H │ H │ H │ H │ H │ H │ H │ H │ H │
  5  │ ◀ │ f │ f │ + │ ? │ x │ f │ f │ ▶ │
     └───┴───┴───┴───┴───┴───┴───┴───┴───┘
       45  46  47  48  49  50  51  52  53
```

| Slot | Item | Action |
|---|---|---|
| 0–44 | Home entry | Left: teleport. Right: edit. Shift-left/shift-right: delete confirmation. |
| 45 | Arrow (`ARROW`) | Previous page; disabled (gray pane/barrier) on page 1 |
| 46–47 | Filler | None |
| 48 | Name tag (`NAME_TAG`) | `Set home here` — opens name input; disabled if at limit or no `marp.sethome` |
| 49 | Book (`WRITABLE_BOOK`) | Page info: page, pages, count/limit |
| 50 | Barrier (`BARRIER`) | Close |
| 51–52 | Filler | None |
| 53 | Arrow (`ARROW`) | Next page; disabled on last page |

- If there are zero homes, slot 22 shows a `CHEST`/`GRASS_BLOCK` empty-state item with `gui.no-homes`, and slot 48 remains available.
- The header is not a separate item; the page-info item carries `count / limit` and turns red when over the limit.
- The "Set home here" button is optional for v0.1 but is cheap and makes the menu feel complete; it opens a chat prompt for the identifier and then calls `HomeService.create`.

### 8.3 Warps list (54 slots, 6 rows)

Same geometry as homes, with warp-specific nav:

| Slot | Item | Action |
|---|---|---|
| 0–44 | Warp entry | Left: teleport. Right: edit (admins only; everyone else gets a no-op/message). Shift-click: delete confirmation (admins only). |
| 45 | Arrow | Previous page |
| 46–47 | Filler | None |
| 48 | Compass (`COMPASS`) | `gui.warp-help`: explains that warps are admin-managed and how to request one (configurable text) |
| 49 | Book | Page info |
| 50 | Barrier | Close |
| 51–52 | Filler | None |
| 53 | Arrow | Next page |

- Restricted warps are hidden from non-admins when `hide-unauthorized: true`; admins see them with `gui.warp-locked` in the lore.
- Warps are cached, so opening the menu is instant; pagination is in memory.

### 8.4 Entry edit menu (27 slots, 3 rows)

Opened by right-clicking a home or warp. The identifier is **not** editable; only the display name, lore, and icon are. This keeps `/home <name>` stable and avoids a rename cascade.

```
       0   1   2   3   4   5   6   7   8
     ┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
  0  │ f │ f │ f │ f │ f │ f │ f │ f │ f │
  1  │ f │ N │ f │ L │ f │ I │ f │ T │ f │
  2  │ f │ f │ f │ f │ B │ f │ f │ f │ D │
     └───┴───┴───┴───┴───┴───┴───┴───┴───┘
        9  10  11  12  13  14  15  16  17
```

| Slot | Item | Action |
|---|---|---|
| 10 | `NAME_TAG` | Edit display name (`gui.edit-name`) |
| 12 | `WRITABLE_BOOK` | Edit lore (`gui.edit-lore`) |
| 14 | Current icon (or `ITEM_FRAME`) | **Click while holding an item** to copy that item as the icon. Holding air -> prompt. No item movement, no dupes. |
| 16 | `ENDER_PEARL` | Teleport to the entry (runs the full pipeline) |
| 22 | `ARROW` | Back to the list, same page |
| 26 | `BARRIER` | Delete -> confirmation |
| other | Filler | None |

- Clicking the icon slot with an item copies `item.asOne()` (amount 1), serializes it, enforces `max-icon-bytes`, and stores material + bytes. The player's item is untouched.
- Reject `AIR`, items whose `Material` is not an item (`isItem() == false`), and serialized data over the cap. A rejected icon leaves the old icon intact.
- Display-name/lore edits apply immediately after input validation; there is no "unsaved draft" state. This avoids the "closed the menu and lost changes" class of bugs.
- Warp edits require `marp.admin.setwarp` or `marp.admin.editwarp`; home edits require ownership and `marp.homes`.

### 8.5 Delete confirmation (27 slots, 3 rows)

```
       0   1   2   3   4   5   6   7   8
     ┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
  0  │ f │ f │ f │ f │ f │ f │ f │ f │ f │
  1  │ f │ C │ f │ E │ f │ Y │ f │ f │ f │
  2  │ f │ f │ f │ f │ f │ f │ f │ f │ f │
     └───┴───┴───┴───┴───┴───┴───┴───┴───┘
        9  10  11  12  13  14  15  16  17
```

| Slot | Item | Action |
|---|---|---|
| 11 | Red wool / `RED_CONCRETE` | Cancel, return to the list |
| 13 | Entry preview | None (shows the exact entry) |
| 15 | Lime wool / `LIME_CONCRETE` | Confirm delete |
| other | Filler | None |

- The confirmation expires after `gui.confirmation-seconds` (10s); the menu closes and the entry is untouched.
- Closing the inventory, pressing Escape, or clicking outside cancels.
- After deletion, reopen the list on the same page; if the page is now empty and page > 1, go back one page.

### 8.6 Entry item format

`GuiItemFactory` renders each entry:

```
Material: icon.material (fallback GRASS_BLOCK/ENDER_PEARL); amount 1
Name:     display_name if present, else identifier
Lore:
  {gui.home-lore-world}       -> World: world
  {gui.home-lore-coords}      -> Location: x, y, z (rounded to 1 decimal)
  {gui.home-lore-created}     -> Created: date
  (blank)
  {gui.actions}               -> Left/Right/Shift hints
Item flags: HIDE_ATTRIBUTES, HIDE_ENCHANTS if a glow is used
```

- Formatting comes from `messages.yml` templates, not hardcoded strings. The entry name is parsed as MiniMessage only when it came from a display name; identifiers are escaped text.
- Coordinates are display-only and rounded; actual teleport uses the full-precision stored values.
- If a world is missing, replace the coords line with `general.world-missing` and render the entry with a warning; left-click is disabled with `teleport.failed` (do not delete automatically).
- Icons are never allowed to carry a click action that runs commands; all interaction is handled by the menu listener on the slot, not by item NBT.
- If the serialized icon fails to deserialize, log once per entry and fall back to `icon.material`. A corrupt icon must not break pagination or the rest of the menu.

### 8.7 Click matrix

| Surface | Left | Right | Shift + Left | Shift + Right | Middle / number / drop |
|---|---|---|---|---|---|
| Home entry | Teleport | Edit | Delete confirm | Delete confirm | Cancelled |
| Warp entry | Teleport | Edit (admin) | Delete confirm (admin) | Delete confirm (admin) | Cancelled |
| Prev/next | Page change | Page change | Cancelled | Cancelled | Cancelled |
| Set home here | Name input | Name input | Cancelled | Cancelled | Cancelled |
| Close | Close | Close | Cancelled | Cancelled | Cancelled |
| Edit: name/lore | Input | Input | Cancelled | Cancelled | Cancelled |
| Edit: icon | Set from hand | Set from hand | Cancelled | Cancelled | Cancelled |
| Edit: teleport | Pipeline | Pipeline | Cancelled | Cancelled | Cancelled |
| Edit: back | Back | Back | Cancelled | Cancelled | Cancelled |
| Edit/delete | Confirm menu | Confirm menu | Cancelled | Cancelled | Cancelled |
| Confirm yes/no | Action | Action | Cancelled | Cancelled | Cancelled |

Shift checks run before left/right checks because Bukkit's `ClickType.SHIFT_LEFT.isLeftClick()` is true.

### 8.8 Pagination logic

Pure `Pagination` functions, unit-tested without Bukkit:

```
pageSize = 45
pages    = max(1, ceil(total / pageSize))
page     = clamp(page, 0, pages - 1)
start    = page * pageSize
end      = min(start + pageSize, total)
entries  = list.subList(start, end)
```

- Homes: load all homes for the player (bounded by the limit, or by existing over-limit data) and paginate in memory.
- Warps: cache all warps at startup and after mutations; paginate in memory. If the cache ever becomes huge (thousands), switch `WarpRepository` to `LIMIT/OFFSET`; the menu contract does not change.
- Sort by `nameKey` ascending, then `id` for stability. Do not sort by `updatedAt`; entries jumping around after an edit is confusing.
- Preserve the current page across edit/refresh. After delete, clamp the page; if the page is empty and > 0, decrement once.
- Prev/next buttons are disabled visually (gray pane) when unavailable; clicking them is a no-op, not an error.
- Page info shows `page/pages` and total count; homes also show `count/limit`.

### 8.9 Text input and icon input

#### v0.1: chat input (default)

- Clicking display name or lore stores a `PendingInput` keyed by player UUID: `{entryId, type, expiresAt, page}`.
- Prompt via `messages.yml`; the next chat message is consumed.
- `InputListener` handles `AsyncChatEvent` at `EventPriority.LOWEST`:
  - If a pending input exists, `event.setCancelled(true)`.
  - Serialize the message to plain text **on the async thread** (no Bukkit calls), then schedule the main thread.
  - `cancel` (case-insensitive) aborts. Timeout is `gui.input-timeout-seconds` (60s).
- Name: 1 line, max `gui.max-display-name-length` (32) visible chars and `gui.max-display-name-raw` (128) raw chars. MiniMessage allowed if configured. Empty -> invalid.
- Lore: split on `|` into lines; trim each; max `gui.max-lore-lines` (4); each line max `gui.max-lore-line-length` (64) visible and `gui.max-lore-line-raw` (256) raw. Empty input clears the lore.
- Applying the edit: validate -> async `update` with `expectedRev` -> on success refresh the edit menu; on conflict show `general.conflict` and refresh.
- Chat input is stable on Paper and Spigot-like servers, and needs no GUI library. It is the v0.1 trade-off; v0.2 adds a better widget.

#### v0.2: Dialog API, anvil fallback

- Paper 1.21.10 has the Dialog API (experimental). Use it for name/lore when available; keep chat input as the fallback.
- If Dialog is unavailable or disabled by config, use an anvil inventory for the name:
  - `InventoryType.ANVIL`, custom holder, current name in slot 0.
  - `PrepareAnvilEvent` sets the result item to the typed name.
  - Capture only when slot 2 is clicked; cancel the click, close, restore/clear the anvil slots, and apply.
  - Anvil input is brittle across versions (result item formatting, shift-click, client quirks). It must never lose the player's items: this design does not put real items into the anvil; slot 0 is a display copy.
- Keep a single `TextInputService` interface so `EntryEditMenu` does not care which implementation is active.

#### Icon input

- The icon is set by clicking the icon slot while holding an item, not by dragging. This avoids item loss/dupe paths entirely.
- Copy `heldItem.asOne()`, serialize with `ItemStack#serializeAsBytes()` on the main thread, check the byte cap, store material + bytes.
- If serialization throws, keep the old icon and report `gui.icon-invalid`.
- If the item exceeds the cap, store only the material and report `gui.icon-too-large`; this keeps a usable icon without bloating the database. (Alternative: reject entirely; choose one and document. Recommended: material-only fallback with a warning.)

### 8.10 MiniMessage and content limits

- Display names and lore accept MiniMessage when `gui.allow-minimessage: true`. Parse with a shared `MiniMessage` instance built once at load; do not create a new one per item.
- **Sanitize at parse time, not after.** `selector`, `score`, `nbt`, `click`, `hover`, and `insertion` are *tag resolvers*: they produce content while parsing, so "stripping interactive tags after parsing" cannot undo them and a `<selector:@a>` in a home name would resolve to real player data. Build the serializer with a resolver whitelist (colors, decorations, gradients, `translatable` if desired) and a catch-all resolver that leaves every other tag as literal text. Post-parse, additionally clear any click/hover events on the resulting tree as defense in depth.
- Enforce limits before saving, not only when rendering. Two numbers per field, because they measure different things:

```
                     visible (plain text)      raw (source string)
name                  32 chars                128 chars
lore line             64 chars                256 chars
lore lines             4                        4
icon                   -                    16384 bytes
```

  A gradient tag is longer than 32 characters on its own, so a raw-only cap would reject every coloured name; a visible-only cap would let a player store megabytes of nested tags. Both apply. Rejection is a message (`gui.input-invalid` with the limit), never silent truncation.
- Newlines in lore are normalized to `\n`; GUI lore is a `List<Component>`, one component per line. A literal `|` is the input separator (§8.9) and is not available inside lore text.
- Formatting in identifiers is not allowed (regex is ASCII-safe by default). Display names are where colors belong.
- Interactive tags in GUI lore are pointless (no click target) and can mislead players, so the whitelist denies them by default.
- A parse failure on a stored value (malformed tag, removed tag type) falls back to the plain-text identifier for that entry and logs once per entry. It must never break the menu or the pagination.

## 9. Teleport pipeline

### 9.1 State machine

```
IDLE
  └─ request (command/GUI)
      ├─ permission/argument check fail ──────────────► IDLE (message)
      ├─ storage fetch fail/missing ───────────────────► IDLE (message)
      ├─ cooldown active ──────────────────────────────► IDLE (message)
      ├─ preflight safety fails (BLOCK / no spot) ─────► IDLE (message; /back may fall back)
      └─ warmup > 0 and no bypass
            ├─ warmup countdown ── cancel condition ───► IDLE (cancel message)
            └─ countdown complete
                  └─ arrival
                      ├─ chunk load timeout/fail ─────► IDLE (failure message)
                      ├─ final safety check fails ─────► IDLE (message)
                      ├─ record /back (if applicable)
                      ├─ teleportAsync
                      │    ├─ false/exception ────────► IDLE (failure message)
                      │    └─ true ───────────────────► IDLE (success, cooldown starts)
                      └─ (no warmup path enters here)
```

Only one session per player. Starting a new request cancels and replaces the previous one; the old session must not send a "cancelled" message after the new one starts (use a session token).

### 9.2 Step-by-step: `/home base`

1. **Command thread (main).** Validate `marp.home`; player is online; storage is ready.
2. **Resolve target (async).** `HomeRepository.findByPlayerAndName(uuid, "base")`. Return immutable `LocationSnapshot`; never return a `Location` from the DB thread.
3. **Main: validate target.** World is loaded (`Bukkit.getWorld(name) != null`); target within world border if configured; not the player's current location (within 1 block). If `homes.cross-world: false` and the target world differs from the player's world, deny with `general.cross-world-disabled`. Missing world -> `general.world-missing`; do not delete the home.
4. **Main: cooldown.** Check `CooldownService.remaining(uuid, HOME)`. Bypass `marp.bypass.cooldown`. If active, message with seconds; stop.
5. **Main: session.** Create/replace `TeleportSession`; close open Marp menu if configured; cancel any existing countdown.
6. **Preflight safety.** Ensure the target chunk is loaded async, then check on main. Apply `center-on-block` to X/Z if configured:
   - SAFE -> continue.
   - ADJUSTED -> replace target with the safe spot; message `teleport.adjusted` (unless silent).
   - UNSAFE -> BLOCK message; stop.
   - `WARN` -> keep target and warn.
   - `marp.bypass.safety` -> skip.
7. **Warmup.** If `warmup.home > 0` and no `marp.bypass.warmup`, register the session with the shared sampler (§9.3). The sampler decrements once per second, refreshes the action bar and countdown sound, and cancels on movement. On 0: proceed to arrival. If warmup is 0: proceed immediately.
8. **Arrival: re-check safety.** The chunk may have changed or unloaded during warmup. Re-run step 6 against the same target (or the adjusted spot). Do not skip the final check.
9. **Main: prepare player.** Dismount vehicle if configured; close inventory if configured; reset fall distance if configured; clear action bar; mark session as arriving (so its own teleport event is not treated as external).
10. **Main: record back.** If recording is enabled and the reason is not `BACK`, snapshot the player's **pre-teleport** location and `BackService.record(TELEPORT)` (async write; in-memory update immediately). Do this only after the teleport is known to proceed, but before it, so the snapshot is accurate. If `teleportAsync` fails, roll back/ignore the back update (see step 12).
11. **Teleport.** `player.teleportAsync(target).whenComplete(...)`:
    - On success (`true`): message `teleport.teleported`, play sound/title, start cooldown for `HOME`, update session to IDLE.
    - On `false`/exception: message `teleport.failed`, do not start cooldown, do not commit the back update (or restore the previous entry), log the cause.
12. **Cleanup in `finally`:** remove the session, clear action bar, clear the arriving flag. Never leave a player permanently "in teleport".

`/warp` and `/back` are identical except for the target lookup and `TeleportReason` (which selects warmup/cooldown config). `/back` additionally skips recording itself.

### 9.3 Warmup cancel matrix

| Event | Default | Config | Notes |
|---|---|---|---|
| Move beyond tolerance | Cancel | `cancel-on-move` | Sampled, not event-driven: 3D distance from the session origin, yaw/pitch ignored, tolerance 0.5 blocks |
| Damage (hit, fall, lava, void, magic, projectile) | Cancel | `cancel-on-damage` | Any `EntityDamageEvent` whose cause is not in `ignored-damage-causes` |
| Ongoing tick damage (poison, wither, fire tick, drowning, starvation, contact, suffocation) | Ignore | `ignored-damage-causes` | These are states the player is already in, not a choice to fight or flee. Cancelling on them makes every command unusable while debuffed |
| Command | Cancel | `cancel-on-command` | `PlayerCommandPreprocessEvent`; allowlist by command name without slash |
| External teleport | Cancel | `cancel-on-teleport` | `PlayerTeleportEvent`, except the session's own arrival |
| Quit | Cancel | `cancel-on-quit` | Player is leaving; no message |
| Death | Cancel | `cancel-on-death` | Death point is recorded separately |
| World change | Cancel | covered by teleport | A world change is a teleport |
| Vehicle enter/exit | Movement likely cancels | — | If stationary in a vehicle, teleport dismounts before arrival |
| Inventory open/close | Do not cancel | — | Menus are closed on teleport start |
| Chat message | Do not cancel | — | Only commands cancel; chat is harmless |
| `marp cancel` | Cancel | — | Explicit |
| Server reload/shutdown | Cancel | — | Plugin lifecycle |

- Cancel message: send exactly one of `teleport.moved`, `teleport.damaged`, `teleport.command`, `teleport.teleported-elsewhere`. A session token prevents duplicate/out-of-order messages.
- **Movement is sampled, not listened to.** One global repeating task every `sample-interval-ticks` (4) iterates online players and compares `location` against the session's origin using squared 3D distance. This removes the hottest listener in the plugin (`PlayerMoveEvent` fires per tick per moving player), keeps one code path for countdown and cancellation, and the same sample maintains the last-ground record used by `/back`. Cost is unchanged: no per-player tasks, no per-move garbage.
- Trade-off: cancellation can lag by up to 4 ticks (200 ms), and a player who moves out and back within that window is not caught. Neither matters on a survival server; a per-move-event listener costs a measurable amount of tick time on a populated one. `PlayerTeleportEvent` stays an event, because a teleport is not a position sample.
- Do not cancel on small client position corrections while standing still. 0.5 blocks is forgiving; if players complain, raise to 0.75.
- Damage cancellation runs on the main thread from `EntityDamageEvent` and is checked against the parsed `ignored-damage-causes` set before cancelling.
- `/marp reload` cancels all warmups intentionally: the new config may change the rules mid-countdown.

### 9.4 Cooldowns

- Keys: `HOME`, `WARP`, `BACK`, matching `teleport.cooldown.*`.
- Check before warmup; start on **successful arrival**, not on command use. This means a failed/cancelled teleport does not punish the player.
- Bypass: `marp.bypass.cooldown`; bypass ignores both existing cooldowns and new cooldown starts.
- Message: `teleport.cooldown` with remaining seconds, rounded up.
- Store in a `ConcurrentHashMap<CooldownKey, Long>`; no DB in v0.1. `persist: false` by default; if persistence is enabled later, add a `player_cooldowns` table rather than overloading an existing table.
- Successful teleport also resets any stale session; a new command during cooldown does not start a warmup.
- `/back` cooldown is separate so a death does not block homes unexpectedly.

### 9.5 Safe-location rules

The checker runs on the main thread against loaded chunks. Block coordinates are how a location is stored, but they are the wrong unit for "is this space free": players occupy a collision box, and slabs, stairs, snow layers, carpets, trapdoors, and soul sand all have partial collision shapes. A material test ("is the feet block solid?") therefore reports a home standing on a slab floor as suffocation, which is the most common false positive this class of plugin ships.

`SafetyEvaluator` is pure and takes a `BlockView` that exposes collision geometry:

```
BlockView {
  List<BoundingBox> collisionBoxes(int x, int y, int z)   // empty = passable
  Material materialAt(int x, int y, int z)
  int minHeight(), maxHeight()
  boolean isInsideBorder(double x, double z)
}
```

`BoundingBox` is `org.bukkit.util.BoundingBox`: pure math, no server state, so the evaluator stays unit-testable without MockBukkit. The Bukkit adapter fills it from `block.getCollisionShape()`.

The player box at a candidate `(x, y, z)`, with `center-on-block` applied to X/Z:

```
     y+2  +----------+
          |          |    head cell
     y+1  |   box    |
          |          |    feet cell
     y    +----------+

  width 0.6  -> x-0.3 .. x+0.3   (z likewise)
  height 1.8 -> y      .. y+1.8
```

Checks, in order:

1. **World border:** outside -> `border`.
2. **Void:** `y < minHeight` -> unsafe. No floor search below the world.
3. **Suffocation:** the player box must not intersect a collision box in the cells `y..y+2`. A box whose top is exactly `y` is a floor, not suffocation.
4. **Hazard blocks:** configured hazards at `y` and `y+1` -> unsafe (`lava`, `fire`, ...). Also reject a **floor** made of a hazard (magma, campfire): standing there is legal but damaging, so it is not a valid destination.
5. **Floor:** the highest collision-box top at or below `y`, scanning down at most `max-fall-distance`. None -> unsafe (`void` or `fall`).
   - Top within 0.01..`max-fall-distance` below `y` -> `ADJUSTED` with `y` set to the box top. Drop the player onto the slab/stair/snow instead of refusing.
   - Water counts as a floor only when `allow-water` is true.
6. **Water:** if the player box is in water and `allow-water: true`, require non-water above the head so the player can surface; otherwise unsafe (`water`).
7. **Adjacent hazards:** a 4-neighbour check at feet level for lava/fire/cactus -> unsafe. Cheap guard against being shoved into damage on arrival.

Safety result: `SAFE`, `ADJUSTED(location, reason)`, or `UNSAFE(reason)`. `ADJUSTED` may change `y` as well as X/Z. Reasons are message arguments (`lava`, `void`, `suffocation`, `fire`, `fall`, `water`, `border`), never English strings.

### 9.6 Safe-spot search

- Triggered when the destination is `UNSAFE` and mode is `SEARCH` (default). `SEARCH` returns an `ADJUSTED` result or `UNSAFE` if nothing is found.
- Load the target chunk plus a 3×3 chunk area when `search-radius > 0` (async); chunk loading is the only async part.
- Search order (deterministic):
  1. Same Y, radius 1..R in a spiral (N, E, S, W, diagonals).
  2. Y+1, then Y-1, then Y+2, Y-2, up to `search-vertical`.
  3. Prefer the smallest 3D distance; tie-break by lower Y.
- Candidate must pass the same evaluator used for the preflight check (no box intersection, a floor within `max-fall-distance`, no hazard, inside the border). Center X/Z; a candidate whose floor is a partial block is accepted at the box top rather than rejected.
- Candidates are evaluated by the pure evaluator, so the search ordering and the accept/reject rules are covered by the same unit tests as the single-point check.
- Cap candidates (e.g. 200) to bound work. If none found: `teleport.unsafe-no-spot` and stop (in `SEARCH`); in `WARN`, teleport to the original target with a warning; in `BLOCK`, stop.
- Never search across unloaded chunks synchronously. If the async chunk load times out (5s), fail with `teleport.failed` or `teleport.unsafe-no-spot`; do not block the tick.
- The adjusted location is the teleport target; `/back` records the player's original location before the teleport, not the adjusted spot.

### 9.7 Async, worlds, and chunks

- **World lookup:** `Bukkit.getWorld(name)` returns only loaded worlds. Do not auto-load a world for a teleport: it can stall and is surprising. Report `general.world-missing`; an admin can load the world.
- **Chunk load:** `world.getChunkAtAsync(cx, cz)` returns a future. Wait off the main thread, then hop to main for block reads. Do not call `getBlockAt` on an unloaded chunk.
- **Teleport:** use `player.teleportAsync(target)` (Paper) for cross-world and chunk-loading safety. If you use sync `teleport`, ensure the chunk is already loaded; the async path is preferred.
- **Timeouts:** Java `CompletableFuture.orTimeout(5, TimeUnit.SECONDS)`; handle `CompletionException`/`TimeoutException` and clean up the session. Do not leave a future callback that later opens a menu for a player who logged out.
- **Player identity:** re-resolve `Bukkit.getPlayer(uuid)` on the main thread at every step; if offline/dead, abort silently/log at debug. Never hold a `Player` reference in an async callback.
- **Ordering:** DB fetch -> main validation -> chunk load -> main safety -> teleport -> main success handling. No step may call a blocking DB method.
- **Back recording race:** record the pre-teleport snapshot in memory immediately; persist asynchronously. If the teleport fails, restore the previous in-memory entry (or mark the write as conditional on success). The simplest correct approach: perform the DB write only from the success callback, but keep the snapshot in the session until then.
- **Last-ground record:** the sampler from §9.3 also stores one `LocationSnapshot` per online player while `isOnGround()` is true and the feet are not in a hazard. It is memory-only in v0.1, cleared on quit, and used exclusively by the `/back` unreachable-target fallback (§9.8). A player who joins and immediately dies in the void has no record yet, which is why the fallback has a "none" case.

### 9.8 `/back` recording and resolution

**What counts as "last location" by default:**

- **Death point:** recorded on `PlayerDeathEvent` from `event.getEntity().getLocation()`. This is the location where the player died, not the respawn point.
- **Marp teleport origin:** the player's location immediately before a successful `/home` or `/warp` teleport. `/back` itself is excluded to prevent ping-pong.
- **External teleports:** **not** recorded by default. `back.record-external-teleports: true` records the pre-teleport location from `PlayerTeleportEvent` for causes other than the Marp session (marked with a flag), excluding `TeleportCause.UNKNOWN` if it proves noisy.
- **World changes:** a home/warp/back teleport records the origin even across worlds; `/back` can return across worlds if `back.cross-world: true` and the world is loaded.
- **Login/logout:** joining is not a teleport origin. Quitting does not overwrite the last point.

**Resolution:**

```
if only DEATH exists -> DEATH
if only TELEPORT exists -> TELEPORT
if both:
  prefer NEWEST  -> newer created_at; tie -> DEATH
  prefer DEATH   -> DEATH
  prefer TELEPORT-> TELEPORT
```

**Unreachable targets (void and lava deaths):**

1. Resolve the target as above.
2. Run the safety pipeline from §9.5–9.6.
3. If the result is `UNSAFE` and no safe spot is found:
   - `back.unreachable-fallback: LAST_GROUND` -> use the last-ground snapshot (§9.7) and send `back.fallback-last-ground`.
   - `BLOCK` -> `teleport.unsafe-no-spot`, no session.
4. No last-ground record (fresh join, or the player never stood on safe ground) -> `back.fallback-none`, no session.
5. The fallback applies **only** to unreachable targets. A reachable `/back` is never redirected, and `/home` / `/warp` never use it.

This matters on skyblock and floating-island servers, where a void death is the most common death and the old behavior (refuse) left the player with no route back to their dropped items.

- `consume: false` default: `/back` does not delete the entry, so a player can return again after a cooldown. `consume: true` deletes the chosen entry after a successful arrival (config option; recommended default is false because players expect `/back` to keep working).
- `/back` after death returns the death point even if the player has since used a home, as long as the death is newer (default `NEWEST`). This is the expected survival behavior.
- `/back` after a home teleport returns the pre-home location, which is useful for exploring. If the death is older, the home origin wins under `NEWEST`.
- If the target equals the current location, do not start a warmup; message `back.already-there`.
- If the chosen target's world is missing, fail with `general.world-missing`; do not silently fall back to the other entry (that would be surprising). Optionally offer a future `/back death|teleport` selector.
- Back entries persist across restarts (`back.persist: true`). Load lazily on `/back` if not in the in-memory cache; on DB failure, fall back to memory and log.
- If the death point is in lava or the void, the safety pipeline applies (`SEARCH` can find a nearby ledge). If nothing is safe, use the unreachable-target fallback above rather than a bare refusal.

### 9.9 Failure handling summary

| Failure | Player sees | State |
|---|---|---|
| Storage not ready | `general.storage-not-ready` | No session |
| Home/warp missing | `home.not-found` / `warp.not-found` | No session |
| World unloaded/deleted | `general.world-missing` | No session |
| Cooldown | `teleport.cooldown` | No session |
| Unsafe, no spot | `teleport.unsafe-no-spot` | No session |
| `/back` target unreachable (void/lava death) | `back.fallback-last-ground`, else `back.fallback-none` | Redirected to last ground, or no session |
| Warmup cancelled | Specific cancel message | Session cleared |
| Chunk timeout | `teleport.failed` | Session cleared |
| `teleportAsync` false | `teleport.failed` | No cooldown, back rolled back |
| Player quits mid-warmup | None | Session cleared |
| Player dies mid-warmup | Cancel message if still online | Session cleared; death recorded |
| Plugin reload | `general.reloaded` (for reloader) | All sessions cleared |
| DB write fails after arrival | Success message, error logged | In-memory back state still usable |

## 10. Edge cases and failure modes

| # | Case | Behavior |
|---|---|---|
| 1 | **Deleted world** | `Bukkit.getWorld(name) == null`. Fail with `general.world-missing`; keep the record; show a warning in the GUI and disable teleport. `/marp purge` may remove only records whose world **folder** is missing, never merely unloaded worlds. |
| 2 | **Unloaded world** | Same message, but do not purge. If the world folder still exists, an admin can load it; auto-loading worlds is out of scope. |
| 3 | **Renamed world** | Treated as deleted/unloaded. Store world names, not UUIDs (worlds have no stable UUID). A future bulk-rewrite command can remap names; do not guess. |
| 4 | **Duplicate home name** | Case-insensitive uniqueness (`UNIQUE(player_uuid, name_key)`). `/sethome` on an existing name updates it; concurrent inserts catch the constraint and map to "updated"/`home.updated`. |
| 5 | **Duplicate warp name** | Global uniqueness. `/setwarp` on an existing name moves it and preserves metadata; a race is caught and reported as updated. |
| 6 | **Offline player** | Homes/warps persist. No GUI or teleport for offline players. Admin purge/deletion by UUID or name works; `/back` requires online. |
| 7 | **Home limit lowered** | Existing homes are kept; new homes denied; GUI shows `count/limit` in red; player can delete to get under. Never auto-delete. |
| 8 | **Home limit raised / permission changed** | Recomputed on the next command/menu open; no restart or cache flush needed. |
| 9 | **Limit node `marp.homelimit.0`** | Treated as limit 0: no new homes, existing retained. |
| 10 | **Limit above `max-limit-node-scan`** | Not seen. Document the cap in `config.yml`; admins must raise it. Do not try to infer arbitrary numeric nodes. |
| 11 | **Over-limit player opens GUI** | Menu still opens; entries are viewable/deletable; the set-home button is disabled with `home.limit-reached`. |
| 12 | **Plugin reload** | Close menus, cancel warmups, reload config/messages, refresh warp cache. Do not reconnect the DB; if `storage.type` changed, log "restart required". Cooldowns remain in memory. |
| 13 | **Server restart** | Homes/warps/back persist; cooldowns reset (`persist: false`). SQLite WAL recovers uncommitted transactions; committed writes survive. |
| 14 | **Server crash mid-write** | Each mutation is a single transaction; WAL recovery keeps the last committed state. An uncommitted write is lost, never half-applied. |
| 15 | **Concurrent edits (two sessions)** | GUI updates use `WHERE id=? AND rev=?`; zero rows -> `general.conflict`, re-fetch, refresh. Commands use last-write-wins for locations; name uniqueness still enforced. |
| 16 | **Entry deleted while menu open** | Click re-fetches by ID; if missing, `home.not-found`/`warp.not-found`, refresh the list, do not act on stale coordinates. |
| 17 | **Entry renamed while menu open** | Identifiers are immutable in this design, so this cannot happen through the GUI. If a future rename exists, act by ID and refresh. |
| 18 | **Player quits during warmup** | Cancel silently; remove session; do not message a disconnected player. |
| 19 | **Player dies during warmup** | Cancel; record death point; death message still applies. |
| 20 | **Player teleports during warmup** | Cancel with `teleport.teleported-elsewhere` unless it is this session's own arrival. |
| 21 | **Player switches world during warmup** | A teleport event covers it; cancel. If a plugin moves the player without a teleport event, the arrival world check catches it. |
| 22 | **Player is riding a vehicle** | Dismount before teleport if configured; otherwise the vehicle may be left behind or the teleport may fail. |
| 23 | **Player is gliding/falling** | Teleport is allowed; movement during warmup cancels. On arrival, reset fall distance if `teleport.reset-fall-distance: true` to avoid instant fall damage. |
| 24 | **Inventory open during teleport** | Close Marp menus on start; close any inventory if `close-inventory: true`. Avoid item-loss edge cases from closing while a cursor holds an item. |
| 25 | **Cursor item when menu closes** | Do not accept items into Marp menus, so there is nothing to return. If the player was holding an item on the cursor, Bukkit's close behavior returns/drops it; log nothing. |
| 26 | **Icon item invalid/removed** | `Material.matchMaterial` fails or `isItem()` is false -> fallback material. Old icon stays if setting a new one fails. |
| 27 | **Icon item too large/corrupt** | Enforce 16 KiB; fallback to material-only with a warning; corrupt bytes -> material fallback and log once. Never break the menu. |
| 28 | **MiniMessage parse error** | Log once per key, send a plain-text version, keep the previous parsed template on reload. Do not abort the command. |
| 29 | **Lore/name too long** | Reject at input time with `gui.input-invalid`/limit message; never silently truncate. Rendering also clamps defensively. |
| 30 | **`&` color codes** | Treated literally. Only MiniMessage is supported; document this in `messages.yml`. |
| 31 | **Reserved names** | Identifier regex allows `list`, `help`, `set`, etc. `/home list` is a valid home if the player made one. No reserved words by default; configurable `homes.reserved-names: []` if needed. |
| 32 | **Unicode/space in identifiers** | Rejected by the default ASCII regex. Display names can contain Unicode/spaces/colors. |
| 33 | **`/home` with no default and multiple homes** | Open `/homes` and send `home.default-missing`; do not guess. |
| 34 | **`/home` with no default and one home** | Teleport to that one home (convenience), no GUI. |
| 35 | **Warp permission denied** | Hidden as "not found" by default; admins see all warps. |
| 36 | **Per-warp permission removed while GUI open** | Recheck on click; if denied, message and refresh. |
| 37 | **World border** | Check target against `WorldBorder#isInside`; block outside. Do not clamp silently. |
| 38 | **Same-location teleport** | No warmup; `back.already-there` or a generic "already there" message; optionally still play a sound. |
| 39 | **Back target unsafe** | Run the safety pipeline; `SEARCH` may move the player; if none, block. |
| 40 | **Back target world deleted** | Fail; do not fall back to the other back entry silently. |
| 41 | **No back entry** | `back.no-location`; no session. |
| 42 | **`/back` loop** | `/back` does not update back state; repeated use stays at the same target (subject to cooldown). |
| 43 | **External teleport recorded twice** | Only record if the event is not the Marp session (flag) and not a duplicate cause; keep one `TELEPORT` row (upsert), not a history. |
| 44 | **Database unavailable** | Commands fail with `general.storage-error`; plugin stays up but cannot serve homes/warps. If init fails, disable the plugin instead. |
| 45 | **DB executor saturated** | Commands queue futures; no main-thread blocking. If the queue grows, log a warning; do not spawn unbounded threads. |
| 46 | **Plugin unload/reload via PlugMan** | Unsupported; state may leak (listeners/tasks). Document "full restart required" for plugin updates. |
| 47 | **MySQL/cross-server** | Not in v0.1. Warp cache is per-server; do not assume invalidation. v0.3 adds polling or messaging. |
| 48 | **World deleted while menu open** | Entry renders a warning; click fails with `general.world-missing`; list remains usable. |
| 49 | **Void or lava death, then `/back`** | Death point is unreachable; `back.unreachable-fallback` redirects to the last-ground record (§9.8). No record -> `back.fallback-none`. Never fabricates a location. |
| 50 | **Warmup while poisoned or on fire** | Tick damage causes in `ignored-damage-causes` do not cancel; a real hit does. Without this, a debuffed player can never teleport. |
| 51 | **Home saved on a slab, stair, snow layer, or carpet** | Collision-box check accepts it. A block-material check would call this suffocation; that class of false positive must be covered by a unit test. |
| 52 | **Home saved slightly above a partial floor** | Safety returns `ADJUSTED` and drops the player onto the collision top rather than refusing or leaving them floating. |
| 53 | **Home/warp saved mid-air over a hazard** | Full safety pipeline applies; `SEARCH` finds a ledge or the teleport is refused. Saving is never blocked (a home is a point, not a guarantee). |

## 11. Roadmap

### v0.1 — MVP (playable on a survival server)

**Scope**

- Gradle/build: SQLite dependency, Shadow (or Paper `libraries`), JUnit 5, test task.
- Config/messages loader with `config-version` merge and MiniMessage rendering.
- SQLite storage, `marp_schema`, migrations, repositories, single DB executor, WAL, shutdown checkpoint.
- Homes: `/sethome`, `/home`, `/delhome`, `/homes`; default name; limits; case-insensitive names; overwrite semantics.
- Warps: `/setwarp`, `/warp`, `/delwarp`, `/warps`; admin permission model; public warps; per-warp access enforced as `marp.warp.access.<name>` when the flag is on.
- Limits: `marp.homelimit.<n>` highest-wins, `marp.homelimit.unlimited`, config fallback.
- Teleport: warmup, movement/damage/command/teleport/quit/death cancellation, ignored tick-damage causes, cooldowns, bypass permissions.
- Safety: collision-box checks for lava, void, suffocation, fire, fall, water, border; `BLOCK` and `SEARCH`; spiral search with the same evaluator.
- `/back`: death + Marp teleport origin; newest resolution; persistence.
- GUI: homes/warps lists, click-to-teleport, pagination, close, delete confirmation, empty states, and the entry edit menu (icon from the held item, display name, lore) driven by chat input.
- Text input: `TextInputService` with the chat implementation only. The interface exists so the Dialog/anvil implementations in v0.2 need no menu changes.
- Commands: tab completion from caches; admin `/marp reload|cancel|info`.
- Messages: every player-facing string configurable.
- Tests: limits, pagination, name validation, cooldown clock, safety evaluator via `BlockView` (suffocation, slab/stair floor, fall adjustment, hazard floor, border, water), `/back` resolution including the unreachable fallback, and revision-based conflict detection.
- Manual smoke test on `runServer`: create/delete/teleport/restart/reload.

**Definition of done**

- No JDBC on the main thread (verify with a debug guard that throws if `Bukkit.isPrimaryThread()`).
- Survives restart: homes, warps, back; cooldowns reset as documented.
- Deleted/unloaded world produces a clean error, not an exception.
- A home saved on a slab, stair, or snow layer teleports without a false "unsafe location" error.
- A void death can be recovered with `/back` through the last-ground fallback.
- Two players editing/teleporting concurrently cannot corrupt rows (unique constraints + revision-based updates).
- `/marp reload` leaves the server usable and closes stale menus.

### v0.2 — GUI polish and admin tooling

**Depends on:** v0.1 menu framework, `TextInputService`, icon serialization, optimistic locking.

- Edit display name/lore/icon from the GUI using the Paper Dialog API (anvil fallback behind the same `TextInputService`).
- Per-warp permission override: let an admin store an explicit node on a warp instead of the derived `marp.warp.access.<name>`; add `marp.admin.editwarp`.
- `/marp purge <homes|warps|back>` with missing-world-folder detection and `--confirm`.
- `/marp homes <player>` admin view (read-only or delete), optional.
- Safety refinements: candidate scoring, a configurable hazard list, and a debug overlay that prints the evaluated boxes for a location.
- Cooldown persistence option (`player_cooldowns` table).
- GUI sort/search (name filter via chat input) if warp counts grow.
- Sounds/titles polish, page memory, set-home button.
- Name reserved list if servers want it.

### v0.3 — Scale and integrations

**Depends on:** v0.1 repository/dialect seams; v0.2 cache/invalidation work.

- MySQL storage + HikariCP; cross-server warp cache invalidation (polling or plugin messaging).
- SQLite -> MySQL export/import command.
- Cross-server `/back`/home visibility if desired (careful: per-server teleport rules differ).
- Public API: `MarpTeleportEvent` (pre/post, cancellable), read-only service access for other plugins.
- Optional PlaceholderAPI expansion (homes used/limit, warp count) as a soft dependency.
- Folia support only if there is demand; it requires region-aware scheduling and is a separate architecture pass, not a toggle.
- Rename identifier command/GUI (updates `name_key`, checks uniqueness) if players ask; not assumed.

### Dependency graph

```
v0.1 storage + services + pipeline
   ├── v0.2 Dialog input (needs the TextInputService seam from v0.1)
   ├── v0.2 purge (needs world-folder check + repositories)
   ├── v0.2 warp permission override (needs warp cache + repository update)
   └── v0.3 MySQL (needs SqlDialect + repository contracts)
          └── v0.3 cross-server invalidation
```

---

## 12. Build and packaging

### Packaging configuration (v0.1)

`Marp/build.gradle.kts` includes `java-library`, `com.gradleup.shadow` (`9.6.1`), `xyz.jpenilla.run-paper` (`3.1.0`), Paper API `compileOnly`, shaded `org.xerial:sqlite-jdbc:3.54.1.0`, JUnit 5 test dependencies, `runServer`, and `${version}` resource expansion.

### Required changes for v0.1

```kotlin
plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")

    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    shadowJar {
        // Keep the default "-all" classifier or set "" and disable the plain jar.
        // Do not relocate org.sqlite: SQLite JDBC loads native resources by path.
        mergeServiceFiles()
    }
    build { dependsOn(shadowJar) }
    test { useJUnitPlatform() }
    // existing runServer/processResources stay as-is
}
```

- **Why Shadow:** SQLite JDBC is an `implementation` dependency; without bundling it, the plugin compiles but throws `NoClassDefFoundError: org/sqlite/JDBC` at runtime. Shadow is build-time only and does not add a runtime framework.
- **Why not relocate `org.sqlite`:** the driver extracts native libraries from `org/sqlite/native/...`; relocation can break those resource paths. Paper isolates plugin classloaders, so a bundled driver is unlikely to conflict. Test on `runServer` after packaging.
- **Alternative (Paper-only, smaller build):** add `libraries: [org.xerial:sqlite-jdbc:3.54.1.0]` to `plugin.yml` and skip Shadow. Paper downloads it at startup. This breaks on Spigot and requires network access on first run; choose it only if offline installs are not a requirement. Do not do both (duplicate driver).
- `run-paper` uses the `shadowJar` output automatically when the task exists; verify with `./gradlew runServer` and confirm the plugin enables. Output is `Marp/build/libs/Marp-1.0-SNAPSHOT-all.jar` by default.
- `processResources` token replacement must keep working: `${version}` in `plugin.yml` resolves from `gradle.properties`. Do not hardcode `1.0-SNAPSHOT`.

### `plugin.yml` additions

- Add `commands` (§3.5).
- Add `permissions` for static nodes only (§4.1). Do not try to enumerate `marp.homelimit.<n>` or `marp.warp.access.<name>`.
- Keep `api-version: '1.21.10'` and `load: POSTWORLD`.
- Add `description`, `authors`, and `folia-supported: false` for clarity.
- If using Paper `libraries`, add the SQLite coordinate there instead of Shadow.

### Resource layout

```
src/main/resources/
├── plugin.yml
├── config.yml
└── messages.yml
```

`saveDefaultConfig()` copies `config.yml`; `MessageService` copies `messages.yml` if missing. Both are reloaded by `/marp reload`.

### Test layout

```
src/test/java/me/roviczzz/marp/
├── home/HomeLimitResolverTest.java
├── home/HomeNameValidatorTest.java
├── gui/PaginationTest.java
├── teleport/SafetyEvaluatorTest.java
├── teleport/CooldownServiceTest.java
└── back/BackResolverTest.java
```

No MockBukkit/Mockito. `SafetyEvaluator` depends on `BlockView`, `HomeLimitResolver` on an `IntPredicate`, `CooldownService` on a `LongSupplier` clock. This keeps tests fast and deterministic.

---

## 13. Spigot compatibility notes

The design targets **Paper 1.21.10**. These choices are Paper-specific and should be flagged before any "make it work on Spigot" request:

| Feature used | Why it breaks on Spigot | Spigot alternative |
|---|---|---|
| Adventure `Component`, `MiniMessage` | Paper bundles Adventure; Spigot does not | Add Adventure API + platform loader, or use legacy strings |
| `Player#teleportAsync` | Paper API | Ensure chunk loaded sync, then `Player#teleport` |
| `World#getChunkAtAsync` | Paper API | `World#loadChunk`/`getChunkAt` on main (causes stalls) |
| `ItemStack#serializeAsBytes` / `deserializeBytes` | Paper API | `BukkitObjectOutputStream`/YAML, or store material only |
| `AsyncChatEvent` | Paper event | `AsyncPlayerChatEvent` |
| Paper Dialog API | Paper 1.21.6+ only | Anvil/chat input |
| `ItemMeta#displayName(Component)` / `lore(List<Component>)` | Adventure component methods | Legacy `String` + `ChatColor` |
| `plugin.yml api-version: '1.21.10'` | Spigot is stricter about patch-level API versions | Use `'1.21'` after testing |
| `plugin.yml libraries:` | Paper-only runtime library loader | Shade the driver |
| `run-paper` | Paper dev server only | Manual server setup |
| `Player#showTitle(Title)` / `sendActionBar(Component)` | Adventure methods | Legacy title/action-bar API |

**Recommendation:** keep Marp Paper-only. If Spigot support becomes a hard requirement, isolate these calls behind adapters (`PlatformTeleport`, `PlatformChat`, `PlatformText`) so the domain/services remain unchanged; do not sprinkle version checks through the codebase.

## 14. Open questions

Each item includes a recommended default so implementation can proceed without blocking. Change the recommendation before coding if you disagree.

| # | Question | Recommended default | Why |
|---|---|---|---|
| 1 | What should `/home` with no name do when the default home is missing? | If exactly one home exists, use it; otherwise open `/homes` | Convenience without guessing among many |
| 2 | Does `/back` prefer death or teleport when both exist? | Newest, tie -> death | Matches survival intuition after a recent death |
| 3 | Is `/back` consumed after use? | No, keep it; cooldown prevents spam | Players expect `/back` to remain available |
| 4 | Should external plugin teleports update `/back`? | No by default; config opt-in | Avoids surprising overwrites from other plugins |
| 5 | Which warmup events cancel? | Move > 0.5 blocks, a damaging hit (excluding the ongoing tick causes in `ignored-damage-causes`), any command, external teleport, quit, death | Strong anti-escape, still usable while debuffed |
| 6 | What is the default safety mode? | `SEARCH` with radius 3 and max fall 3, then block | Better UX than hard blocking, still safe |
| 7 | Is the icon a full item or just a material? | A curated item spec; see #22, which supersedes the raw-bytes default | Raw bytes preserve perfect fidelity but pay a size cap and a smuggle surface for it |
| 8 | How are display name and lore edited? | Chat input in v0.1; Dialog API/anvil in v0.2 | Stable release first, nicer widget later |
| 9 | Are identifiers renamable from the GUI? | No in v0.1/v0.2; add a dedicated rename flow in v0.3 | Keeps `/home <name>` stable; avoids breaking tab completion/caches |
| 10 | Warp permission model? | Global `marp.warp` by default; optional per-warp `marp.warp.access.<name>` when enabled | Simple for public warps, flexible for event/rank warps, and keeps `marp.warp.*` meaning "can use warps" |
| 11 | Are homes global or per-world? | Global, cross-world teleport allowed | Most servers expect one home list |
| 12 | Do cooldowns persist across restarts? | No in v0.1; add a persisted option in v0.2 | Keeps the first release simple; cooldowns are short |
| 13 | What happens to homes when a limit is lowered? | Keep existing, block new, show over-limit | Never delete player data for a rank change |
| 14 | Should `/delhome`/`/delwarp` ask for confirmation? | No for commands; yes for GUI shift-click | Commands are explicit; GUI misclicks are not |
| 15 | What happens when `/setwarp` targets an existing warp? | Move it, preserve icon/name/lore/permission | Admins usually want to relocate, not recreate |
| 16 | Should warps be hidden when permission is denied? | Hide as "not found" by default | Avoids leaking restricted areas |
| 17 | Do we support Spigot? | No, Paper-only for v0.1 | The design relies on Paper APIs; adapters can come later |
| 18 | MySQL now or later? | Later (v0.3); keep the dialect seam now | Avoids premature infrastructure |
| 19 | Should `/back` record death points across restarts? | Yes, persist | Players expect it after a crash/restart |
| 20 | What about identifier case and Unicode? | Lowercase ASCII `[a-z0-9_-]{1,32}`, case-insensitive uniqueness | Predictable commands; Unicode belongs in display names |
| 21 | Which Paper line do we build for? | Stay on 1.21.10 / Java 21, the pinned toolchain, unless the target server runs Paper 26.3 | Paper's current line is 26.3 on Java 25; retargeting changes the toolchain, `api-version`, and parts of §13 |
| 22 | Raw serialized icon bytes or a curated icon spec? | Curated: material + custom model data + head profile + glint, with a config escape hatch to raw bytes | ~40 bytes per row, no 16 KiB cap surprises, no smuggled click/hover components, no drift on version upgrade |
| 23 | Should `marp.homelimit.unlimited` be bounded? | Yes: `homes.absolute-max: 1000`, reported as unlimited below that | "Unlimited" is a permission, not a schema guarantee; it must not allow unbounded database growth |
| 24 | Keep the `warps.permission` column? | Drop it in v1 and derive `marp.warp.access.<nameKey>`; add an explicit override column only when an admin UI can write it | Nothing writes it in v0.1, and the derived node covers rank-gated warps |
| 25 | Merge duplicated pure helpers? | One `NameValidator(NameRules)` for homes and warps, one `Pagination` | The two validators are identical today; duplicates drift |
| 26 | What happens to a pending chat input? | Any command cancels it, and `/marp reload` clears all of them, with `gui.input-cancelled` | Otherwise the input silently consumes the player's next message |
| 27 | Validate `gui.rows`? | Yes: 3..6, clamped with a load warning | `rows: 2` yields a negative page size and a broken nav row |
| 28 | Two servers sharing one SQLite file? | Unsupported; one server per file, documented | SQLite locking does not make network shares safe, and the warp cache is per-process |

### Decisions needed before v0.1 coding

Only #1, #2, #5, #6, #10, #21, and #22 affect core control flow or the data model. #21 (Paper line) and #22 (icon model) are the two that change build files and schema, so decide those before the first commit. Everything else can be adjusted behind config or added in v0.2.

---

## Appendix A — v0.1 acceptance checklist

- [x] `/sethome`, `/home`, `/delhome`, `/homes` work for a player with default permissions.
- [x] `/setwarp`, `/warp`, `/delwarp`, `/warps` work for an op; non-op cannot create/delete.
- [x] `marp.homelimit.5` wins over `marp.homelimit.2`; fallback limit applies without a node; `marp.homelimit.unlimited` works.
- [x] `marp.homes.*` grants the GUI and does not raise the home limit.
- [x] Setting a sixth home at limit 5 is denied; updating an existing home at the limit is allowed.
- [x] Warmup cancels on movement, a damaging hit, command, teleport, quit, death; poison and fire-tick damage do not cancel it; countdown is visible.
- [x] Cooldown starts only after arrival; bypass permission works.
- [x] Lava/fire/suffocation/void/fall checks work; `SEARCH` finds a nearby spot or blocks.
- [x] A home saved on a slab, stair, or snow layer is not reported as unsafe; one saved slightly above a partial floor is adjusted onto it.
- [x] `/back` returns the death point, then the pre-home location, and does not ping-pong.
- [x] A void death is recoverable with `/back` through the last-ground fallback; with the fallback set to `BLOCK` it fails with a clear message.
- [x] GUI pagination works with 0, 1, 45, 46, and 100 entries; delete refreshes/clamps the page.
- [x] Two GUIs editing the same entry: the second edit gets `general.conflict` and reopens, never a silent overwrite.
- [x] GUI clicks/drags cannot move items or duplicate them.
- [x] Icons with invalid/oversized/corrupt data fall back without breaking the menu.
- [x] Messages and config reload; a malformed message does not crash a command.
- [x] Restart preserves homes/warps/back; SQLite has no lock errors under two concurrent players.
- [x] No JDBC call on the main thread; plugin disables cleanly with a bounded DB shutdown.
- [x] `runServer` smoke test passes with the shaded jar (or Paper `libraries`).

---

## 15. v0.2 Feature Addendum (Extensions)

### 15.1 Teleport Requests (/tpa)
- Commands: `/tpa <player>`, `/tpahere <player>`, `/tpaccept [player]`, `/tpdeny [player]`, `/tpcancel`, `/tptoggle [on|off]`.
- Clickable Adventure `[Accept]`, `[Deny]`, and `[Cancel]` buttons in chat with detailed hover tooltips (direction, target world, remaining expiry time), built around `MessageService` templates while keeping its MiniMessage tag whitelist strict.
- 1 active outgoing request per requester; 60s timeout expiry; explicit disambiguation listing on bare `/tpaccept` when multiple requests are pending; 30s per-pair deny cooldown.
- Teleport pipeline rules (warmup, movement/damage/command cancellation, cooldown, safety, `/back` origin) bind strictly to the mover, while anchor is re-resolved to their live location on arrival.
- Incoming request blocking via `/tptoggle`, persisted in `player_settings.tpa_disabled`.

### 15.2 Bed / Respawn Integration
- Listens to Paper's `PlayerSetSpawnEvent` (`Cause.BED`, and opt-in `Cause.RESPAWN_ANCHOR`) to automatically create or update the player's `bed` home.
- Preserves custom display metadata on overwrite; exempt from manual home limits by default (`homes.is_bed = 1`); supports disabled worlds and `/bedtoggle` (`/marp bed [on|off]`).
- Default snapshot-home mode allows returning even if the bed is later broken; optional `require-valid-bed: true` validates bed existence after async chunk loading.
- Marked in `/homes` GUI with `RED_BED` default material, `gui.home-lore-bed` badge, and row-5 Auto-Bed toggle button.

### 15.3 Favorites
- Starring homes and warps persisted in SQLite `favorites` table keyed by `(player_uuid, target_kind, target_id)`.
- Bounded by `marp.favoritelimit.<n>` and `marp.favoritelimit.unlimited` (fallback default 9).
- Pinned to the top of `/homes` and `/warps` in insertion order (`sort_order ASC`).
- Dedicated `/favorites` (`/fav`) 54-slot chest menu with `All | Homes | Warps` filter cycling.
- Non-conflicting row-5 Star Mode toggle in lists and slot 4 favorite button in `EntryEditMenu`.
- `/home` with no arguments resolves default home, then top favorite home, then single home, else opens `/homes` (`DEFAULT_FIRST` vs `FAVORITE_FIRST`).
- Deleting a home or warp cleans up favorite rows transactionally.

### 15.4 Help Command (/marp)
- `/marp` (0 args) and `/marp help [page]` render a permission-aware, feature-flag-aware command directory grouped into `Homes`, `Warps`, `Teleporting`, `General & Admin`.
- Paginated at 7 entries per page with clickable `[<< Previous]` and `[Next >>]` footer buttons.
- Clickable rows (run command for safe/GUI actions; suggest command in chat for argument-taking actions) with rich hover details (syntax, description, example, click hint, and admin permission node).

