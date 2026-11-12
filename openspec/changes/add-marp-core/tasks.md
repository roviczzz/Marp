# Tasks

## 1. Build and manifest

- [x] 1.1 Add the Shadow plugin (`com.gradleup.shadow` 9.6.1), `implementation("org.xerial:sqlite-jdbc:3.54.1.0")`, JUnit Jupiter test dependencies, `shadowJar { mergeServiceFiles() }`, and `build { dependsOn(shadowJar) }` to `Marp/build.gradle.kts`; verify `.\gradlew.bat -p Marp build` succeeds and `jar tf Marp/build/libs/Marp-1.0-SNAPSHOT-all.jar` lists `org/sqlite/JDBC.class` and `me/roviczzz/marp/Marp.class`.
- [x] 1.2 Enable the JUnit platform (`tasks.test { useJUnitPlatform() }`) and add one placeholder test under `Marp/src/test/java/me/roviczzz/marp/`; verify `.\gradlew.bat -p Marp test` runs it instead of reporting `NO-SOURCE`.
- [x] 1.3 Expand `plugin.yml` with `description`, `authors`, `folia-supported: false`, every command from DESIGN.md §3.5 (usage, aliases, permission), and the static permission nodes from §4.1 with correct `default: true|op`; verify `.\gradlew.bat -p Marp processResources` and check `Marp/build/resources/main/plugin.yml` has the resolved version, all commands, and no `marp.homelimit.<n>`-style enumerated nodes.
- [x] 1.4 Leave `processResources` untouched and confirm on the built jar that `plugin.yml` contains `Marp-1.0-SNAPSHOT` rather than `${version}`; verify with `jar xf`/`Get-Content` on the packaged resource.

## 2. Configuration and messages

- [x] 2.1 Write `src/main/resources/config.yml` from DESIGN.md §6 with every documented key and default, `config-version: 1`, and the storage block marked restart-only; verify a unit test loads the resource with `YamlConfiguration` and asserts the documented top-level sections (`storage`, `homes`, `warps`, `back`, `teleport`, `gui`) exist, and that the feature switches `homes.enabled`, `warps.enabled`, and `back.enabled` resolve.
- [x] 2.2 Write `src/main/resources/messages.yml` from DESIGN.md §7 with `messages-version: 1`; verify a unit test parses it and asserts every key referenced by `MessageService` constants resolves.
- [x] 2.3 Implement `MarpConfig` typed accessors for every `config.yml` value, including enum parsing with documented fallbacks, `gui.rows` clamped to 3–6, and the home-limit scan ceiling; verify tests cover an unknown enum value (default used) and `rows: 2`/`rows: 9` (clamped).
- [x] 2.4 Implement `ConfigLoader` copy-if-missing and versioned merge-on-load that adds missing keys without overwriting user values, for both config and messages; verify tests in a temp directory cover first run, user-edited value preserved, new key added, and unreadable file logged without throwing.
- [x] 2.5 Implement `MessageService` with `{placeholder}` substitution and MiniMessage rendering through a restricted tag set, plus plain-text fallback for an unparseable template or missing key; verify tests cover a normal placeholder, a formatted value, an unknown key, a broken tag, and an oversized/invalid template.

## 3. Domain models and pure rules

- [x] 3.1 Add the immutable records `LocationSnapshot`, `Home`, `Warp`, `BackEntry`/`BackKind`, `IconSpec`, and `Page` per DESIGN.md §5.1; verify they compile with no Bukkit imports (`grep` the package for `org.bukkit`) and a small test asserts record equality and that `LocationSnapshot` carries yaw/pitch.
- [x] 3.2 Implement one shared `NameRules`/`NameValidator` for homes and warps (lowercase ASCII `[a-z0-9_-]{1,32}`) and use it from both features; verify tests cover mixed case normalisation, spaces, empty input, 33 characters, and non-ASCII rejection.
- [x] 3.3 Implement `Pagination` (page size from `gui.rows`, entry count, clamp, has-next/has-previous, first slot) as a pure class; verify tests cover 0, 1, exactly one page, one more than one page, and "delete the last entry on page 2 returns to page 1".
- [x] 3.4 Implement `HomeLimitResolver` over an `IntPredicate` that scans `marp.homelimit.<n>` from the configured ceiling down, honours `marp.homelimit.unlimited`, and falls back to `homes.default-limit`; verify tests cover highest-wins, no node, unlimited, zero, and a node above the ceiling being ignored.
- [x] 3.5 Implement `BackResolver` as a pure choice over `(death, teleport, preference, now)` with newest-first default, death tie-break, death-preference, single-target, and none; verify tests cover all five cases including equal timestamps.
- [x] 3.6 Implement `CooldownService` over a `LongSupplier` millis clock with independent `HOME`/`WARP`/`BACK` keys, bypass handling, and rounded-up remaining seconds; verify tests cover active cooldown, start-on-arrival only, bypass, and independence between keys.

## 4. Storage

- [x] 4.1 Implement `DbExecutor` (named single `Marp-DB` thread, `CompletableFuture` submission, bounded shutdown, pending-count reporting), `Scheduler.runOnMain` with an `isPrimaryThread()` check, and `StorageException`; verify a test submits work, asserts the executing thread name, and asserts `shutdown` returns within the bound with a still-running task.
- [x] 4.2 Implement `SqlDialect` and `V1InitialSchema` with the pragmas and DDL from DESIGN.md §5.2 (pragmas, `marp_schema`, `homes`, `warps`, `back_locations`, no extra indexes); verify a test opens a temp-file database, runs the migration, and asserts `journal_mode=wal`, table names, the `UNIQUE` constraint, and `marp_schema.version = 1`.
- [x] 4.3 Implement the migration runner: read the version, apply ordered migrations each in its own transaction, write the new version, and refuse a database newer than the plugin; verify tests cover a fresh file, a v0 file that reaches v1, and a hand-set version `2` producing a refusal without writes.
- [x] 4.4 Implement `HomeRepository` (`findByPlayer`, `findByPlayerAndName`, `findById`, `countByPlayer`, `insert`, `update` with `expectedRev`, `delete(id, owner)`) using `ON CONFLICT DO UPDATE` for upserts; verify tests cover insert, case-insensitive lookup, update with a stale `rev` returning false, delete with the wrong owner returning false, and a duplicate-key upsert preserving icon/display/lore.
- [x] 4.5 Implement `WarpRepository` (`findAll`, `findByKey`, `findPage`, `count`, `insert`, `update`, `delete`); verify tests cover globally unique `name_key`, a racing duplicate mapping to the "exists/updated" path rather than `StorageException`, and paged reads returning stable order.
- [x] 4.6 Implement `BackRepository` (`find`, `findAll`, `upsert`, `delete`) over the two-row `(player_uuid, kind)` primary key; verify tests cover one row per kind, last-write-wins upsert, and deleting only the requested kind.
- [x] 4.7 Implement `SqlStorage`: async `init()` that opens the connection, applies pragmas and migrations, exposes `ready()` as a `CompletableFuture<Void>`, and a shutdown that checkpoints `TRUNCATE` then closes; verify tests cover successful init against a temp path and an unwritable path completing the future exceptionally without leaving a locked file.

## 5. Services and caches

- [x] 5.1 Implement `HomeService` create/update/delete/list with limit enforcement, configurable default name, update-at-limit allowed, metadata patch that preserves untouched fields, and stale-rev conflict results; verify tests with an in-memory fake repository cover a new home at the limit (refused), an existing identifier at the limit (allowed), and a `rev` mismatch producing a conflict.
- [x] 5.2 Implement `HomeCache` (per-player `name_key` list, load on join, update on mutation, clear on quit); verify a test covers load, add, remove, and clear, and that a cold lookup returns empty rather than blocking.
- [x] 5.3 Implement `WarpService` admin CRUD, move-preserves-presentation, access filtering (hide-as-not-found vs explicit denial), and cache refresh after each mutation; verify tests with a fake repository and permission predicate cover public access, granted access, denied-and-hidden, denied-and-visible, and a moved warp keeping its icon/name/lore.
- [x] 5.4 Implement `WarpCache` (full list, load when storage becomes ready, refreshed on mutation) and make the completion source read it only; verify a test asserts a cold cache returns no suggestions and a populated cache returns access-filtered entries.
- [x] 5.5 Implement `BackService` record/resolve with death and teleport origins, exclusion of `BACK` from recording, opt-in consumption, preference handling, and the in-memory last-ground store; verify tests with a fake repository cover death-then-home newest-wins, `/back` not changing the target, and a void target falling back to the recorded ground.

## 6. Teleport pipeline

- [x] 6.1 Implement `BlockView` and the pure `SafetyEvaluator` with the ordered checks from DESIGN.md §9.5 (border, void, suffocation via collision boxes, hazard floor, partial-floor adjustment, water, adjacent hazards); verify tests with a synthetic `BlockView` cover a slab/stair/snow floor as `SAFE` or `ADJUSTED`, a full block as `UNSAFE(suffocation)`, lava as `UNSAFE(lava)`, no floor as `UNSAFE(fall)`, outside border, and a hazard floor being rejected.
- [x] 6.2 Implement `SafeSpotFinder` with the deterministic spiral order, Y offsets, candidate cap, and partial-floor adjustment from §9.6; verify tests assert the first-found ordering, a same-Y spot preferred over a Y+1 spot, the cap producing `UNSAFE` rather than an unbounded scan, and a found spot passing the same evaluator.
- [x] 6.3 Implement `TeleportSession` and the one-per-player registry with a monotonic token, so a replacing request cancels the previous countdown without sending a second cancellation message; verify a test replaces a session and asserts the old token produces no message and only the new session completes.
- [x] 6.4 Implement `TeleportService` orchestration (resolve → validate → cooldown → session → chunk preload → safety → warmup → final safety → prepare → back snapshot → `teleportAsync` → success/cleanup) with injectable scheduler, teleporter, safety, and clock seams; verify tests cover warmup 0, bypass, cancellations, a failed teleport starting no cooldown and committing no back entry, and cleanup always removing the session.
- [x] 6.5 Implement the global warmup sampler core (countdown, squared-distance tolerance, ignored damage causes, command/teleport/quit/death cancellation, last-ground recording) driven by the configured interval; verify a tick-driven test covers movement beyond 0.5 blocks, a poison tick not cancelling, a hit cancelling, and the last-ground snapshot being updated only while on ground outside hazards.
- [x] 6.6 Wire `WarmupListener` (damage, command preprocess, teleport, quit, death), `DeathListener` (record death at the death location), `ExternalTeleportListener` (opt-in recording, ignoring the session's own arrival), `PlayerConnectionListener` (join cache load, quit cleanup), and the repeating sampler task scheduled in `onEnable`; verify on `runServer` that movement, a hit, `/spawn`, and logout each cancel a pending `/home`, while standing still lets it complete.
- [x] 6.7 Implement the Bukkit `BlockView`/`SafetyService` adapter (`getCollisionShape`, world border, height limits) and the async chunk preload with a 5 s timeout before any block read; verify on `runServer` that a home on a slab arrives on its surface, a home in lava is refused or adjusted per mode, and a home in an unloaded world reports the world-missing message without deleting the record.
- [x] 6.8 Handle `teleportAsync` completion: on `true` send the success message and start the cooldown, on `false`/exception send failure, start no cooldown, and skip the back write, with the offline-player and timeout paths aborting silently; verify with a fake teleporter returning `false` and a test that a player who quits mid-warmup leaves no session and receives no message.

## 7. Commands and completion

- [x] 7.1 Implement the command helpers (player-only, permission, storage-ready gate, feature-enabled gate) and register all commands in `Marp.onEnable`; verify on `runServer` that `/home` before storage is ready answers `general.storage-not-ready`, console `/setwarp` is refused for lack of a location, and non-op `/setwarp` is denied.
- [x] 7.2 Implement `/sethome`, `/home`, `/delhome`, `/homes` with the default-name rules (explicit name, default name, single-home fallback, otherwise open the menu) and case-insensitive lookup; verify on `runServer` that `/sethome Base` then `/home BASE` works, a missing name reports not-found, and `/delhome` of another player's home reports not-found.
- [x] 7.3 Implement `/setwarp`, `/delwarp`, `/warps` including console deletion, create-implies-move, and the access-denied hide/deny messages; verify on `runServer` that an op creates, moves, and deletes a warp, a non-op receives the denial message, and an inaccessible warp is either absent or marked locked in the menu.
- [x] 7.4 Implement `/back` using `BackService` plus the pipeline, including the already-there short-circuit and the no-target message; verify on `runServer` that after a death `/back` returns to the death point, after `/home` it returns to the pre-teleport location, and two consecutive uses do not ping-pong.
- [x] 7.5 Implement tab completion for all commands from caches only, filtered by access and prefix; verify a unit test drives the completion helper with a fake cache (matching, non-matching, hidden warp) and on `runServer` completion still answers while storage is starting.

## 8. GUI

- [x] 8.1 Implement `MarpMenuHolder`, `Menu`, `MenuManager`, and `MenuListener`; cancel clicks, drags, and player-inventory clicks while a Marp menu is open, ignore clicks from a different owner, and suppress a rapid double action; verify on `runServer` that items cannot be removed, shift-clicked, number-swapped, or dragged and that a double-click teleports once.
- [x] 8.2 Implement `GuiItemFactory` for the entry format, fallback material on an unusable icon, page info, disabled navigation buttons at bounds, and the empty-state item; verify on `runServer` that an entry with a broken stored icon still renders, page 1 shows previous disabled, and an empty list shows the empty-state item.
- [x] 8.3 Implement `HomeListMenu` with the §8.2 slot map (45 entries, nav row 45–53), left/right/shift-click mapping, set-home button, page preservation across edits, and the used/limit counter including the over-limit case; verify on `runServer` with 46 homes that page 1 shows a next button, page 2 shows the 46th home, and an edit returns to the same page.
- [x] 8.4 Implement `WarpListMenu` with the §8.3 slot map, access filtering, locked marker for operators, and empty state; verify on `runServer` that a restricted warp is hidden from a normal player and shown as locked to an op.
- [x] 8.5 Implement `EntryEditMenu` with the §8.4 slots: copy the held item into the icon without consuming it, reject an unusable or oversized icon, start name/lore input, and never change the identifier; verify on `runServer` that the held stack count is unchanged after an icon copy and the command name still resolves after a display-name edit.
- [x] 8.6 Implement `DeleteConfirmMenu` with the §8.5 slots, configured expiry closing the menu, close-as-cancel, and confirmed deletion returning to the previous page; verify on `runServer` that cancel leaves the entry, waiting past the timeout leaves the entry, and confirm removes it and reopens the list on the right page.
- [x] 8.7 Implement `TextInputService` with `ChatTextInput` and `PendingInput` (one session per player, `AsyncChatEvent` cancelled and text copied off-thread, validation and application on main, `cancel` aborts); verify on `runServer` that a coloured name under the limits applies, an over-limit name is rejected with the limit named, `cancel` leaves the old value, and a command or `/marp reload` clears a pending input.
- [x] 8.8 Enforce the text and icon limits from §8.10 in the input path: visible length, raw length, lore line count, and the 16 KiB icon cap, rejecting rather than truncating; verify unit tests cover each limit boundary and a stored value that no longer parses falling back to the identifier.

## 9. Administration and lifecycle

- [x] 9.1 Implement `/marp reload`: reload config and messages, cancel pending warmups, close open Marp menus, clear pending inputs, keep the storage connection, and log restart-required when storage settings changed; verify on `runServer` that a changed message appears after reload and an open menu closes.
- [x] 9.2 Implement `/marp cancel` and the no-op case; verify on `runServer` that it stops a pending `/home` and that running it with nothing pending reports "nothing to cancel" without an error.
- [x] 9.3 Implement `/marp info` reporting version, storage type and path, home and warp counts, and cache state behind its permission; verify on `runServer` as op that the output matches the created records and that a non-op is refused.
- [x] 9.4 Implement the independent `features.homes|warps|back` switches: disabled commands answer with the disabled-feature message and mutate nothing, and re-enabling restores the stored records; verify on `runServer` by disabling homes, attempting `/sethome` and `/home`, re-enabling, and confirming existing homes are unchanged.
- [x] 9.5 Implement the `onDisable` ordering from DESIGN.md §2.3: cancel warmups and inputs, close menus, bounded DB executor shutdown, WAL checkpoint, and warning-on-timeout; verify on `runServer` shutdown that the log shows a clean checkpoint and no "thread still running" warning, and that no stacktrace is produced.

## 10. Integration verification

- [x] 10.1 Run `.\gradlew.bat -p Marp clean build` and confirm the test report is non-empty, the `-all` jar contains the SQLite driver and both resource files with the version expanded.
- [x] 10.2 Start `runServer`, confirm the plugin enables, `plugins/Marp/marp.db` plus WAL sidecars are created, `/marp info` reports sqlite, and no stacktrace appears during startup or a first `/sethome`/`/home` cycle.
- [x] 10.3 Run the Appendix A acceptance checklist from `Marp/DESIGN.md` end to end on `runServer` and record any failure as a follow-up task rather than silently adjusting the design.
- [x] 10.4 Reconcile documentation with the shipped behaviour: update `Marp/AGENTS.md`'s build facts (the `-all` jar is now the default output) and correct `Marp/DESIGN.md` wherever implementation deliberately diverged; verify by diffing the sections against the code and confirming no claim still describes Marp as a skeleton.

## Workflow follow-up

- Archive the change after review and after the acceptance checklist passes.
- Verify the archived result and that main specs contain the seven `marp/*` capabilities.
