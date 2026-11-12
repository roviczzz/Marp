# Tasks

## 1. Manifest, configuration, and messages

- [x] 1.1 Update `Marp/src/main/resources/plugin.yml` with `tpa`, `tpahere`, `tpaccept`, `tpdeny`, `tpcancel`, `tptoggle`, `bedtoggle`, `favorites` (aliases `[fav, favs]`), updated `marp` usage (`/marp [help|cancel|bed|reload|info]`), and static permission nodes (`marp.tpa`, `marp.tpahere`, `marp.tpaccept`, `marp.tpdeny`, `marp.tpcancel`, `marp.tptoggle`, `marp.bedhome`, `marp.bedhome.toggle`, `marp.favorites`, `marp.help` under `marp.use`, plus `marp.favoritelimit.unlimited` under `marp.*`, omitting numeric `marp.favoritelimit.<n>` nodes); verify `.\gradlew.bat processResources` succeeds and `build/resources/main/plugin.yml` contains all commands and permissions.
- [x] 1.2 Update `Marp/src/main/resources/config.yml` and `MarpConfig.java` with `tpa.*`, `bed-home.*`, `favorites.*`, `help.*`, `teleport.warmup.tpa`, and `teleport.cooldown.tpa` accessors plus `HomeNoArgPriority` enum validation; verify `MarpConfigTest` and `ConfigResourcesTest` pass with fallback behavior on invalid config values.
- [x] 1.3 Add `tpa.*`, `bed.*`, `favorites.*`, `help.*`, and GUI badge/toggle keys to `Marp/src/main/resources/messages.yml` and matching constants (plus `UNKNOWN_PLAYER`) to `Messages.java`; verify `.\gradlew.bat test --tests ConfigResourcesTest` asserts every key in `Messages` resolves.

## 2. Database schema v2, models, and repositories

- [x] 2.1 Add `V2FeaturesSchema` (`homes.is_bed` column, `player_settings` table, `favorites` table, and `idx_favorites_target`), register it in `MigrationRunner.standard()`, and add `upsertPlayerSettingsSql()` to `SqlDialect`; verify `SqlStorageTest` covers fresh v2 schema creation, v1-to-v2 migration preserving existing rows, and schema version `3` refusal.
- [x] 2.2 Update `Home` record (`boolean isBed`) and `SqlHomeRepository` (`mapRow`, `insert` preserving `is_bed` on create without clobbering on update, `countManualByPlayer` excluding `is_bed = 1`, transactional `favorites` cleanup in `delete`), plus transactional `favorites` cleanup in `SqlWarpRepository.delete`; verify `ModelTest` and `SqlStorageTest` pass.
- [x] 2.3 Implement `PlayerSettings`, `PlayerSettingsRepository` / `SqlPlayerSettingsRepository`, `PlayerSettingsService` (join-loaded in-memory cache + async writes), `FavoriteEntry`, `FavoriteKind`, `FavoriteRepository` / `SqlFavoriteRepository` (`findByPlayer`, `add`, `remove`, `countByPlayer`, `deleteByTarget`), and `FavoriteCache`; verify repository persistence and ordering tests pass in `SqlStorageTest`.

## 3. Teleport requests (`/tpa`-style)

- [x] 3.1 Extend `TeleportReason` with `TPA` and `TeleportSession` with optional `UUID anchorPlayer`, then update `TeleportService` and `TeleportPlatform` so arrival re-resolves the live anchor player, cancels if anchor is offline/dead or crosses a disallowed world, and validates arrival safety while warmup/cooldown/cancellation/`/back` bind strictly to the mover; verify unit tests in `TeleportPipelineTest` cover both `/tpa` and `/tpahere` mover-vs-anchor semantics.
- [x] 3.2 Implement pure `TpaService` (1 active outgoing request per requester, up to `max-pending-incoming` incoming per target, timeout expiry sweep + lazy checks, explicit disambiguation when `> 1` pending, transient-failure preservation, per-pair `deny-cooldown-seconds`, `/tptoggle` rejection) and `CombatTracker` (`TeleportGuard`); verify unit tests in `TpaServiceTest` cover expiry, ambiguity, self-requests, toggles, deny cooldowns, and combat tagging.
- [x] 3.3 Implement `TpaPresenter` (Java Adventure `ClickEvent`/`HoverEvent` builders around `MessageService` strings), `TpaCommand` (handling `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`, `/tptoggle` + `CompletionSupport` visibility-filtered online targets and pending requesters), `CombatListener`, and anchor/peer disconnect/death/world-change hooks across `PlayerConnectionListener`, `DeathListener`, `WarmupSampler`, and `Marp.java`; verify `CommandAndAdminTest`, `CompletionSupportTest`, and `TpaPresenterTest` pass.

## 4. Bed / respawn integration

- [x] 4.1 Extend `HomeService` with `setBedHome(...)` (exempt from manual home limits by default when `counts-toward-limit: false`, preserving custom display name/lore/icon on overwrite, updating `HomeCache`) and `bed-home.reserve-name` checking in `setHome(...)`; verify unit tests in `HomeServiceTest` cover creating `bed` at manual home limit, overwriting coordinates while keeping metadata, and reserved-name behavior.
- [x] 4.2 Implement `BedHomeListener` (`PlayerSetSpawnEvent` `Cause.BED` and opt-in `Cause.RESPAWN_ANCHOR`, `disabled-worlds` filtering, bed block material matching, `bed_home_disabled` opt-out check), `/bedtoggle` command (and `/marp bed [on|off]`), async-chunk-safe `require-valid-bed` check via `TeleportPlatform`, and bed lore marker + nav-bar toggle in `GuiItemFactory` and `HomeListMenu`; verify unit tests and `.\gradlew.bat compileJava` pass.

## 5. Favorites and GUI integration

- [x] 5.1 Implement `FavoriteLimitResolver` (`marp.favoritelimit.<n>`, `marp.favoritelimit.unlimited`, `favorites.absolute-max`) and `FavoriteService` (`toggle`, `listForPlayer`, pinned sorting helpers), and update `HomeService.resolveForTeleport` for no-arg `/home` resolution (`DEFAULT_FIRST` vs `FAVORITE_FIRST`); verify unit tests in `FavoriteServiceTest` and `HomeServiceTest` pass.
- [x] 5.2 Implement `FavoritesMenu` (`MenuType.FAVORITES`, `All | Homes | Warps` filter, Star Mode, pagination), update `HomeListMenu` and `WarpListMenu` with pinned sorting, row-5 Favorites/Star Mode buttons, and `HomeListMenu` Auto-Bed button, add `SLOT_FAVORITE = 4` to `EntryEditMenu`, update `GuiItemFactory` with star indicator + Paper enchantment glint override, and implement `/favorites` (`/fav`) command + tab completion; verify `GuiEntryFormatTest`, `ListenerAndMenuTest`, and `.\gradlew.bat test` pass.

## 6. Help command (`/marp`)

- [x] 6.1 Implement pure `HelpCatalog` (categorized `HOMES`, `WARPS`, `TELEPORT`, `GENERAL_ADMIN` entries, permission + feature-flag filtering, `RUN_COMMAND` vs `SUGGEST_COMMAND` click modes, `Pagination` slicing) and `HelpPresenter` (clickable rows, rich hover tooltips, clickable `[<< Previous]` / `[Next >>]` footer); verify unit tests in `HelpCatalogTest` and `HelpPresenterTest` cover permission filtering, disabled-feature hiding, category headers, and multi-page navigation.
- [x] 6.2 Update `MarpCommand`, `CompletionSupport.completeMarpSubcommands`, and `Marp.java` so `/marp` (0 args) and `/marp help [page]` render the help directory for both players and console behind `marp.help`, plus `/marp bed` and page-number tab completion; verify `CommandAndAdminTest` and `CompletionSupportTest` pass.

## 7. Integration verification and documentation

- [x] 7.1 Extend `LiveServerSelfTest` to exercise schema v2 migration, TPA requests/buttons/live-anchor/tptoggle, bed-home capture/exemption/strict check, favorites pinning/limit/cleanup/`FavoritesMenu`/`/home` fallback, and `/marp` help output on Paper 1.21.10; verify `.\gradlew.bat compileJava` succeeds.
- [x] 7.2 Run `.\gradlew.bat clean build` and `.\gradlew.bat runServer -PmarpSelfTest=true`; verify all unit tests pass, the `-all` jar builds cleanly, and the Paper server log outputs `MARP_SELFTEST_PASSED` with zero unhandled exceptions.
- [x] 7.3 Update `Marp/DESIGN.md` with the implemented v0.2 extensions (schema v2, commands, permissions, config, messages, GUI row-5 maps, live-anchor TPA pipeline) and run `openspec validate --change "add-tpa-bed-favorites-help" --strict`; verify zero validation errors and clean diff against code.

## Workflow follow-up

- Archive `add-tpa-bed-favorites-help` after implementation and review pass.
- Verify the archived main specs under `Marp/openspec/specs/`.
