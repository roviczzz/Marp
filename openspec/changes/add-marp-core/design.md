# Design

## Context

Marp is an independent Gradle build under `Marp/` with an empty `Marp.java`, a `plugin.yml` that declares no commands, no test source set, and no database code. `Marp/DESIGN.md` already specifies the target behaviour in detail: package map (§2.1), threading model (§2.4), command table (§3), permission resolution (§4), schema (§5.2), `config.yml` (§6), `messages.yml` (§7), GUI slot maps and click matrix (§8), the teleport pipeline (§9), and the build changes (§12). The proposal covers motivation and scope; this document records the cross-cutting technical choices, the parts where `DESIGN.md` leaves room for interpretation, and how the work lands without breaking the surrounding repo.

Constraints that shape the approach:

- Paper 1.21.10 / Java 21, as pinned by the existing build. `Marp/AGENTS.md` and §13 of `DESIGN.md` treat Paper-only APIs (Adventure, `teleportAsync`, `getChunkAtAsync`, `ItemStack#serializeAsBytes`, `AsyncChatEvent`) as deliberate.
- `Marp/` shares no code with LetItRoll. Only the Gradle wrapper is shared.
- Only `paper-api` is currently on the classpath, so the SQLite driver exists in neither the compile nor the runtime classpath today.
- No MockBukkit or Mockito. All tests must run without a Bukkit server.
- The plugin must never issue JDBC on the main thread and must never let a Bukkit object reach a DB task.

This design defers behavioural detail to `DESIGN.md` by section reference instead of copying it. Where this document and `DESIGN.md` disagree, this document wins and `DESIGN.md` is corrected in the same change.

## Goals / Non-Goals

**Goals:**

- Ship a playable v0.1: homes, warps, `/back`, and the chest menus, matching every requirement in `specs/marp/*`.
- Keep the Bukkit surface thin: pure logic (limits, name rules, pagination, safety, back resolution, cooldowns) lives behind interfaces that unit tests can drive.
- Keep dependencies to Paper API, one SQLite driver, and JUnit. No config library, command framework, GUI framework, or pooling library.
- Make every failure mode explicit: storage not ready, missing world, chunk timeout, unsafe destination, stale menu row, storage initialisation failure.

**Non-Goals:**

- MySQL implementation, cross-server cache invalidation, and pool-backed executors (only the dialect seam is kept).
- `/marp purge`, Dialog/anvil input, a stored per-warp permission override, `marp admin homes`, a public API, PlaceholderAPI, Folia, or Spigot.
- Persisting cooldowns or the last-ground record across restarts.
- A curated icon specification; raw serialized bytes with a size cap stay for this change.
- Reorganising `Marp/DESIGN.md`; it remains the long-form contract.

## Decisions

### D1 — Build the contract in `DESIGN.md`, not a second specification

Implementation follows `DESIGN.md` §2–§9, and the OpenSpec specs define the externally observable behaviour. This document adds only decisions that are cross-cutting or where `DESIGN.md` allows more than one reading.

*Alternative:* rewrite the behaviour into the specs and let `DESIGN.md` decay. Rejected: the slot maps, schema DDL, and pipeline ordering are too detailed for spec scenarios, and a second copy would drift.

*Consequence:* tasks reference `DESIGN.md` sections directly (for example "implement §9.5 safety evaluator"), so an implementer does not have to reverse-engineer intent.

### D2 — One dedicated DB thread, not a pool or the async scheduler

`DbExecutor` owns a single-threaded `ExecutorService` named `Marp-DB`, and every JDBC call runs there: connection open, pragmas, migrations, queries, transactions, and the shutdown checkpoint. Repository methods return `CompletableFuture<T>`; the executor completes them, and callbacks that touch Bukkit go through `Scheduler.runOnMain`.

*Why:* SQLite serialises writers anyway, so a pool adds contention without throughput. A named thread makes thread dumps readable and gives shutdown one thing to join. Paper's async scheduler is shared and unbounded, which makes "wait for pending writes, then checkpoint" impossible to bound.

*Alternatives:* HikariCP (adds a dependency for no SQLite benefit; the seam stays for the future MySQL path), synchronous calls with a timeout (still blocks the tick), `CompletableFuture.supplyAsync` on the common pool (no shutdown control, no ordering guarantee).

*Rules:* no `join()`/`get()` on the main thread; no `Player`, `World`, `Location`, or `ItemStack` in a DB task — pass UUIDs, `LocationSnapshot`, and serialized bytes only.

### D3 — Repositories return models, services own rules

`HomeRepository`, `WarpRepository`, and `BackRepository` map rows to immutable records and know nothing about permissions, limits, or Bukkit. `HomeService`/`WarpService` enforce permissions, identifier rules, limits, and cache updates. GUI code calls the same service methods as commands.

*Why:* the spec requires GUI and commands to agree on limits, access, and validation. Two independent implementations would drift, and the duplicate-validation trade-off is accepted only because the shared method is the service, not the check.

*Alternative:* fat repositories that also check limits. Rejected: it makes the SQL layer depend on permission state and blocks unit testing.

### D4 — Schema v1 stays as written, including the unused nullable `permission`

Schema v1 is `DESIGN.md` §5.2 verbatim. Deliberate properties:

- `name_key` carries case-insensitive uniqueness (`UNIQUE (player_uuid, name_key)` for homes, `UNIQUE` for warps); `name` keeps the entered spelling for future display use.
- `AUTOINCREMENT` on `homes.id` and `warps.id` so a stale menu row can never resolve to a recycled id.
- `rev INTEGER NOT NULL DEFAULT 0` is the optimistic lock; `updated_at` is display data only.
- No foreign keys and no profile table: records must outlive players and worlds.
- `warps.permission` stays nullable and unused in v0.1. Dropping it would force a migration for a column that becomes useful the moment a stored override is added; writing it is out of scope (`DESIGN.md` §14 #24).

*Alternative:* normalise `back_locations` into a history table. Rejected for v0.1: `/back` needs exactly one death point and one teleport origin per player, and the two-row primary key makes the upsert trivial.

*Migration guard:* a database version newer than the bundled migrations makes the plugin refuse to start rather than downgrade-write.

### D5 — Caches answer completion; the database never does

`WarpCache` holds the full warp list; `HomeCache` holds per-player `name_key` lists. Both are loaded and updated on the main thread from service calls, with the DB write following asynchronously. Tab completion reads only these caches and filters by access.

*Why:* a completion callback cannot wait for SQL without stalling the client or the tick. The spec explicitly allows an empty result for a cold cache.

*Alternative:* synchronous completion queries with a short timeout. Rejected: SQLite latency is usually small but unbounded under a checkpoint, and the main thread must not depend on it.

*Consistency:* commands and clicks always re-read the authoritative row before acting (D6), so a stale cache can only mis-suggest a name, never delete or teleport to the wrong record.

### D6 — GUI identity is a holder; actions revalidate by id, owner, and `rev`

Every Marp menu is a chest inventory created with `MarpMenuHolder` carrying menu type, owner UUID, page, and the entry id for edit/confirm menus. `MenuListener` cancels `InventoryClickEvent`, `InventoryDragEvent`, and clicks in the player's own inventory while a Marp menu is open. Any action that mutates or teleports re-fetches the row, checks owner/access, and compares `rev`; a mismatch reports `general.conflict` and refreshes.

*Why:* titles are display data (`DESIGN.md` §1) and rev-based checks are the only way to prevent a second editor from silently overwriting the first (`specs/marp/storage`, `specs/marp/gui`).

*Alternatives:* title matching (spoofable, breaks on translation), action-buttons-as-items with `InventoryClickEvent#setCurrentItem` suppression only (leaves drag and number-key paths open).

### D7 — The teleport pipeline is one orchestrator with a session token

`TeleportService` owns the whole sequence from `DESIGN.md` §9.2: resolve target → main-thread validation → cooldown → session → chunk preload → safety → warmup → final safety → player preparation → back snapshot → `teleportAsync` → success handling → `finally` cleanup. Each `TeleportSession` carries a monotonically increasing token so a replaced session cannot emit a late "cancelled" or "teleported" message.

*Why:* ordering is the correctness property here. Splitting the pipeline across listeners makes "which message won" and "was the back snapshot taken before arrival" untestable.

*Alternatives:* event-driven choreography across listeners (already rejected by `DESIGN.md` §9.1), or a per-request task chain (duplicates cleanup logic three times).

### D8 — Warmup uses one sampled task, not `PlayerMoveEvent`

A single global task samples every `sample-interval-ticks` (4) for online players: it compares squared 3D distance to each session origin (tolerance 0.5 blocks, yaw/pitch ignored), drives the countdown, and records the last-ground snapshot used by `/back`.

*Why:* per-move listeners are the hottest handler on a populated server; sampling keeps one code path for countdown, movement cancellation, and ground tracking (`DESIGN.md` §9.3).

*Accepted trade-off:* cancellation can lag up to four ticks (200 ms) and a player who leaves and returns inside that window is not caught. Both are invisible in normal play and configurable.

*Alternatives:* `PlayerMoveEvent` (tick cost, three code paths), per-player `BukkitRunnable` (task churn per teleport).

### D9 — Safety is evaluated on collision geometry through a `BlockView`

`SafetyEvaluator` and `SafeSpotFinder` are pure classes over a `BlockView` that exposes `collisionBoxes`, `materialAt`, `minHeight`/`maxHeight`, and `isInsideBorder`. The Bukkit adapter fills it from `getCollisionShape()`. Results are `SAFE`, `ADJUSTED(snapshot, reason)`, or `UNSAFE(reason)` with reason codes, never English text.

*Why:* a material-based "is the feet block solid" test reports every slab, stair, snow layer, carpet, and trapdoor as suffocation — the most common false positive in this class of plugin. `BoundingBox` is pure math and testable without a server.

*Alternatives:* material tables (false positives, needs constant maintenance), NMS bounding boxes (version-locked), "trust the saved location" (drops players into lava).

*Bounds:* spiral search with `search-radius` 3, `search-vertical` 2, a 200-candidate cap, and a 5 s chunk-load timeout. In `WARN` the original target is kept with a warning; in `BLOCK` the request stops.

### D10 — `/back` is two rows plus a pure resolver; last ground is memory-only

`back_locations` holds at most one `DEATH` and one `TELEPORT` row per player. `BackResolver` is a pure function over `(death, teleport, preference, now)`; default is newest-first with a death tie-break. `/back` never writes its own origin, and consumption is opt-in. The last-ground snapshot lives in memory, is written by the sampler, and is cleared on quit.

*Why:* the two-row shape makes "newest of the two" trivial and keeps `/back` from growing into an event log. Excluding `/back` from recording is what prevents the ping-pong the spec forbids.

*Alternatives:* append-only history (unbounded growth, retention policy needed), a single "last location" row (loses the death/teleport distinction the preference setting needs), persisted last-ground (schema work for a fallback that is only meaningful within a session).

### D11 — Config and messages merge by version, render through a whitelist

`ConfigLoader` copies bundled defaults when absent and merges missing keys on load without overwriting user values; each file carries `config-version` / `messages-version`. `MessageService` renders MiniMessage through a restricted resolver set and treats `{placeholder}` as the only substitution syntax; a template or enum failure logs once and falls back to plain text.

*Why:* operators edit these files, and an upgrade must not silently drop a new key or silently overwrite a customised one. Restricting resolvers is the difference between a coloured display name and a player-selector/score read.

*Alternatives:* a config library (Configurate/BoostedYAML — extra dependency for a two-file plugin), raw MiniMessage with no restriction (allows `<selector>`, `<score>`, click and hover injection), truncating oversized input (the spec requires rejection).

*Validation:* `gui.rows` is clamped to 3–6, home-limit nodes are scanned from a configured ceiling downward, and invalid enum values fall back to the documented default with a warning.

### D12 — Package the driver with Shadow; do not relocate `org.sqlite`

`build.gradle.kts` gains `com.gradleup.shadow` 9.6.1 and `implementation("org.xerial:sqlite-jdbc:3.54.1.0")`, the default `-all` classifier stays, `mergeServiceFiles()` is enabled, and `build` depends on `shadowJar`. `org.sqlite` is **not** relocated because the driver resolves native libraries from its own resource paths. `run-paper` picks up the `shadowJar` output.

*Alternatives:* Paper `libraries:` in `plugin.yml` (smaller jar, but Paper-only and needs network on first start), shading without Shadow (manual `Jar` task, no service merging), plain `implementation` (runtime `NoClassDefFoundError`).

*Constraint:* `processResources` must keep expanding `${version}` from `gradle.properties`; no hardcoded version in `plugin.yml`.

### D13 — Lifecycle: init asynchronously, disable on failure, gate commands on readiness

`onEnable` loads config synchronously (small files), starts `storage.init()` asynchronously, constructs services and registers commands immediately, and answers storage-dependent commands with `general.storage-not-ready` until `storage.ready()` completes. Warp cache load and the ready flag run on the main thread. If initialisation fails, the plugin logs the cause and disables instead of serving partial state.

`onDisable` cancels warmups and pending inputs, closes open menus, shuts the DB executor down with a bounded await (5 s), checkpoints WAL in `TRUNCATE` mode, and logs a warning on timeout rather than hanging shutdown.

*Why:* the spec requires both "answer clearly during startup" and "never hang shutdown"; the two are only compatible with a readiness future and a bounded await.

### D14 — Chat input in v0.1, behind an interface

`TextInputService` has one implementation (`ChatTextInput`) that uses `AsyncChatEvent`: cancel the event, copy the plain text off-thread, then validate and apply on the main thread. `PendingInput` holds one session per player; a command, quit, or `/marp reload` cancels it with `gui.input-cancelled`.

*Alternative:* Paper Dialog API or an anvil GUI in v0.1. Deferred because Dialog is 1.21.6+ specific and anvil input is version-sensitive; the interface keeps both available later (`DESIGN.md` §14 #8).

## Risks / Trade-offs

- **Shadow-bundled driver fails to extract natives in a restricted environment** → verify with `runServer` after packaging; `plugin.yml` `libraries:` is the documented fallback, and the two must never be combined. Log the driver version at startup.
- **Raw icon bytes allow oversized or component-smuggled items** → enforce the 16 KiB cap and strip non-visual metadata on write, render a material fallback on any read failure, and never resolve components from stored items.
- **Sampled warmup lets a fast out-and-back move through** → accepted and documented; tolerance is configurable and the countdown is visible, so the player sees the teleport is still pending.
- **The single DB thread becomes a bottleneck** → writes are small and per-action, WAL keeps readers off the writer, and caches absorb completion traffic. If it ever matters, the seam allows a pool for MySQL only.
- **Async ordering bugs (Bukkit touched off-thread)** → `Scheduler.runOnMain` with an `isPrimaryThread()` check, a review checklist item in tasks, and no Bukkit types in repository signatures.
- **Stale menu rows act on deleted or edited entries** → id + owner + `rev` revalidation before every action, `AUTOINCREMENT` to prevent id reuse, and a refresh on conflict.
- **Chunk load or `teleportAsync` hangs** → 5 s timeout, session cleared in `finally`, no cooldown started, back snapshot committed only on success.
- **Back snapshot committed for a failed teleport** → hold the snapshot in the session and persist only from the success callback; roll back the in-memory entry otherwise.
- **Config merge surprises on upgrade** → versioned files, missing keys added from bundled defaults, user values preserved, and the merge logged at startup.
- **Over-limit homes break page math** → keep all records, never delete on rank change, compute page size from `gui.rows`, and show the used/limit counter including the over-limit state.
- **Pure logic is tested, Bukkit adapters are not** → concentrate behaviour in the pure classes; keep adapters (listeners, `GuiItemFactory`, `SafetyService`) thin and treat `runServer` plus Appendix A as their check.
- **Spigot users install the jar and hit missing APIs** → `Marp/AGENTS.md` and `DESIGN.md` §13 already document the breakage; no version checks get sprinkled through the code.

## Migration Plan

Greenfield, so there is no data migration; there is a schema version and an upgrade path to protect later.

1. **First deploy:** build `Marp-1.0-SNAPSHOT-all.jar`, drop it in `plugins/`, start the server. On first enable, `config.yml` and `messages.yml` are written from resources, `plugins/Marp/marp.db` is created, pragmas are applied, migration `V1InitialSchema` runs, and `marp_schema.version = 1`. An operator runs the Appendix A checklist on `runServer` before the jar is handed out.
2. **Upgrades:** bump `config-version`/`messages-version` when defaults change so merge-on-load can add keys; add migrations as new ordered entries and never edit `V1InitialSchema`. A database newer than the plugin is refused with a clear log line.
3. **Backup/restore:** stop the server (or accept a WAL-consistent copy) and copy `marp.db` together with `-wal` and `-shm`. Restoring `marp.db` alone can discard the newest commits.
4. **Rollback:** stop the server, replace the jar with the previous build, start. Schema v1 is not modified by v0.1 code, so a rollback to an earlier v0.1 build is safe; rolling back to a build without storage leaves the database file in place, which is harmless and reused on re-upgrade.
5. **Failed initialisation:** the plugin disables rather than serving partial state. The operator fixes the path/permissions and restarts; no command writes half-migrated data.

## Open Questions

Each of these is tuning or presentation and can be answered during implementation without changing the specs, the approach, or the task breakdown.

- Which material is the icon fallback when `icon_item` is unusable — a fixed default (for example `GRASS_BLOCK`) or a per-menu config key? The spec only requires a usable fallback.
- Should icon writes preserve enchantment glint, or strip all metadata except name-free visual properties? Either satisfies "render the item"; stripping reduces the smuggle surface.
- Should `sample-interval-ticks` stay operator-visible in `config.yml` or become a constant? The default of 4 is already documented.
- Does `/marp info` need database file size in addition to record counts, or is the path enough for support?
