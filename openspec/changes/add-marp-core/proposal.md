# Proposal

## Why

Marp is a skeleton: `Marp.java` is empty, `plugin.yml` declares no commands, there is no database code, and `src/test` does not exist. `Marp/DESIGN.md` specifies the full behaviour — commands, permissions, schema, GUI slot maps, teleport pipeline — but none of it is executable. Survival servers need homes and warps that do not lose player data, do not stall the server tick, and do not drop players into lava.

This change turns that contract into a working v0.1 plugin.

## What Changes

**Build and packaging**

- Add SQLite JDBC as a bundled implementation dependency (Shadow) and add a JUnit 5 test source set. The distributed artifact becomes the `-all` jar.
- Declare commands and static permissions in `plugin.yml`; keep the existing `${version}` expansion.

**Configuration and messages**

- Ship `config.yml` and `messages.yml` with versioned merge-on-load, MiniMessage rendering through a whitelisted resolver set, and `/marp reload` for live changes.
- Every player-facing string lives in `messages.yml`; placeholders use `{name}` syntax.

**Persistence**

- SQLite storage on a dedicated database executor, WAL journaling, ordered migrations, `rev`-based optimistic locking, and a shutdown that checkpoints without blocking the server.
- No JDBC call, and no Bukkit object, ever crosses the main-thread boundary.

**Player features**

- Homes: `/sethome [name]`, `/home [name]`, `/delhome [name]`, `/homes`; configurable default name; case-insensitive identifiers; limits resolved from `marp.homelimit.<n>` with `marp.homelimit.unlimited`.
- Warps: `/setwarp <name>`, `/warp [name]`, `/delwarp <name>`, `/warps`; admin-managed, server-wide; optional `marp.warp.access.<name>` gating.
- `/back`: death points and Marp teleport origins, newest-first resolution, not consumed on use, with a last-ground fallback when a void or lava death is unreachable.

**Teleport pipeline**

- Warmup countdown with sampled cancellation (movement, hostile damage, command, external teleport, quit, death), per-action cooldowns with bypass permissions, collision-box safety evaluation, bounded safe-spot search, and async chunk loading before `teleportAsync`.

**GUI**

- Holder-identified chest menus: paginated home and warp lists, a documented click matrix (left teleport, right edit, shift-click delete), a timed delete confirmation, and an entry edit menu for icon, display name, and lore via chat input.

**Admin**

- `/marp reload`, `/marp cancel`, `/marp info`.

**Out of scope for this change (v0.2+)**

- MySQL storage and cross-server cache invalidation.
- Stored per-warp permission override, `/marp purge`, Dialog/anvil text input, `marp admin homes` view.
- Public plugin API, PlaceholderAPI expansion, Folia support.

**BREAKING:** none — this is the first functional release of a new plugin. The build output name changes to `Marp-<version>-all.jar`.

## Capabilities

### New Capabilities

- `marp/storage`: durable, non-blocking persistence for homes, warps, and back state, including schema versioning, optimistic locking, and shutdown guarantees.
- `marp/homes`: creating, overwriting, deleting, and listing a player's homes, with identifier rules, per-player limits, and editable display metadata.
- `marp/warps`: server-wide admin-managed warp creation, deletion, lookup, and access filtering.
- `marp/teleport`: the shared teleport pipeline — validation, warmup, cancellation, cooldowns, safety checks, safe-spot search, and async world/chunk handling.
- `marp/back`: recording and resolving a player's last death or pre-teleport location, including the unreachable-target fallback.
- `marp/gui`: the chest-inventory interface — menu identity, slot layouts, click handling, pagination, delete confirmation, and text/icon input with formatting limits.
- `marp/administration`: operator-facing configuration and lifecycle controls — config/messages reload, feature switches, warmup cancellation, storage readiness reporting, and the permission-node conventions.

### Modified Capabilities

None. No specs exist for this plugin yet; this change establishes them.

## Impact

- **Code:** `Marp/build.gradle.kts`, `Marp/src/main/resources/plugin.yml`, new `config.yml` and `messages.yml`, and new packages under `me.roviczzz.marp` (config, command, model, home, warp, back, teleport, storage, gui, input, listener, util).
- **Dependencies:** `org.xerial:sqlite-jdbc` (shaded at runtime), JUnit 5 (test only). No GUI, config, or command framework.
- **Data:** new runtime file `plugins/Marp/marp.db` plus its WAL sidecars.
- **Other plugins:** none. LetItRoll source is untouched; the two Gradle builds remain independent.
- **Target:** Paper 1.21.10 / Java 21, as pinned in the existing build. Icon storage follows the current `DESIGN.md` §5.1 decision (serialized `ItemStack` bytes with a 16 KiB cap); the curated-icon alternative remains an open design question and is not part of this scope.
