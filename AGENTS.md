# Marp — Agent Notes

Marp is a Paper 1.21.10 home/warp plugin. It lives in `Marp/` inside the LetItRoll repo but is an **independent Gradle build** (its own `settings.gradle.kts`). The root `AGENTS.md` and root `openspec/` describe LetItRoll; they do not apply here.

## Where to start

- Read `DESIGN.md` before coding. It is the contract for architecture, commands, permissions, schema, GUI slot maps, and teleport semantics.
- Current state: v0.1 implemented (`Marp.java`, SQLite storage, services, caches, teleport pipeline, GUI menus, commands, listeners, and JUnit 5 test suite).
- Do not edit LetItRoll source to make Marp work. The projects share only the repo and the Gradle wrapper.

## Verified commands

Run from the repo root (`Marp/` has no `gradlew`/wrapper jar):

- `.\gradlew.bat -p Marp compileJava`
- `.\gradlew.bat -p Marp build` -> `Marp/build/libs/Marp-1.0-SNAPSHOT-all.jar` (the Shadow `-all` jar is the default distributable output and includes the SQLite JDBC driver)
- `.\gradlew.bat -p Marp test`
- `.\gradlew.bat -p Marp runServer`

Or from `Marp/`: `..\gradlew.bat <task>`. System `gradle` is not available. Append `--no-configuration-cache` if the Gradle configuration cache breaks.

## Build facts

- Paper API `1.21.10-R0.1-SNAPSHOT` (`compileOnly`), Java 21 toolchain. `plugin.yml` uses `api-version: '1.21.10'` and `load: POSTWORLD`.
- `processResources` expands `${version}` from `gradle.properties`; never hardcode the version in `plugin.yml`.
- `org.xerial:sqlite-jdbc:3.54.1.0` is shaded into `Marp-1.0-SNAPSHOT-all.jar` via `com.gradleup.shadow` (`mergeServiceFiles()`, no relocation of `org.sqlite`). See `DESIGN.md` §12.
- `runServer` is a blocking foreground Paper server; the run directory is `Marp/run` on first launch (EULA pre-accepted). Do not run it foreground in automation (pass `-PmarpSelfTest=true` for an automated self-test run that stops the server on completion).
- Paper-only APIs are deliberate: MiniMessage/Adventure, `teleportAsync`, `getChunkAtAsync`, `ItemStack#serializeAsBytes`, `AsyncChatEvent`, Dialog API. Do not replace them with Spigot equivalents unless asked; §13 lists the breakage.

## Rules that are easy to get wrong

- **No JDBC on the main thread.** Storage returns `CompletableFuture`; callbacks touching `Player`, `World`, blocks, or inventories must hop back to the main thread.
- **MiniMessage placeholders are `{name}`, not `<name>`.** `<...>` is a tag and will be parsed. Escape user values unless they are explicitly formatted display names.
- **Identify Marp GUIs with a custom `InventoryHolder`, never the title.** Cancel all clicks and drags in Marp menus; the menus are not item storage.
- **Home/warp identifiers are case-insensitive** (`name_key` is unique) and stable after creation. Display name/lore/icon are the editable parts.
- **Home limits:** check concrete `marp.homelimit.<n>` nodes from high to low; do not enumerate `getEffectivePermissions()` (unregistered numeric nodes and wildcards are unreliable).
- **`/back` must not record itself**, or repeated use ping-pongs between two points.
- **Test pure logic:** limits, pagination, name validation, cooldowns, back resolution, and safety evaluation via `BlockView`. No MockBukkit/Mockito; Bukkit adapters stay thin.
