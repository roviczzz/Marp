# Marp

A homes, warps, favorites, `/back`, and TPA teleportation plugin for **Paper 1.21.10**, built with async SQLite storage, GUI-first menus, safe-location checks, and MiniMessage formatting.

---

## Features

- **Homes & Auto-Bed Home**: Set, overwrite, delete, and browse personal homes (`/sethome`, `/home`, `/delhome`, `/homes`) with permission-based limits (`marp.homelimit.<n>`) and automatic bed/respawn anchor home capture (`/bedtoggle`).
- **Server Warps**: Admin-managed public or restricted warps (`/setwarp`, `/warp`, `/delwarp`, `/warps`) with optional per-warp permissions and hidden unauthorized entries.
- **Favorites**: Star homes and warps in `/favorites` (`/fav`, `/favs`) with All/Homes/Warps filtering, Star Mode, and configurable per-rank limits (`marp.favoritelimit.<n>`).
- **TPA Requests**: Send and manage player teleport requests (`/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`, `/tptoggle`) with request timeouts, deny cooldowns, and optional combat blocking.
- **`/back` Return**: Return to your newest death or previous teleport point, with automatic fallback to your last recorded safe ground position if the death spot is unreachable (such as void or lava).
- **Safe Teleport Pipeline**: Configurable per-action warmups (cancelled on movement, non-ignored damage, commands, or teleports), cooldowns, and collision-box safety evaluation (`SEARCH`, `BLOCK`, or `WARN`).
- **GUI-First Management**: Paginated chest menus for homes, warps, and favorites with left-click teleport, right-click editing (MiniMessage display name, lore, and held-item icon), and shift-click delete confirmation.
- **Async SQLite Storage**: Non-blocking JDBC storage (`plugins/Marp/marp.db` in WAL mode) so database reads and writes stay off the server tick thread.

---

## Commands

| Command | Aliases | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/sethome [name]` | — | Set or update a home at your location (defaults to `home`). | `marp.sethome` |
| `/home [name]` | — | Teleport to a home, or open `/homes` if no default exists. | `marp.home` |
| `/delhome [name]` | — | Delete a home (defaults to `home`). | `marp.delhome` |
| `/homes` | — | Open the paginated homes GUI. | `marp.homes` |
| `/bedtoggle [on\|off]` | — | Toggle automatic bed home creation. | `marp.bedhome.toggle` |
| `/setwarp <name>` | — | Create or move a server warp at your location. | `marp.admin.setwarp` |
| `/warp [name]` | — | Teleport to a warp, or open `/warps` when omitted. | `marp.warp` |
| `/delwarp <name>` | — | Delete a server warp (console allowed). | `marp.admin.delwarp` |
| `/warps` | — | Open the paginated warps GUI. | `marp.warps` |
| `/favorites` | `/fav`, `/favs` | Open the starred favorites GUI. | `marp.favorites` |
| `/back` | — | Teleport to your last death or teleport location. | `marp.back` |
| `/tpa <player>` | — | Request to teleport to another player. | `marp.tpa` |
| `/tpahere <player>` | — | Request another player to teleport to you. | `marp.tpahere` |
| `/tpaccept [player]` | — | Accept a pending teleport request. | `marp.tpaccept` |
| `/tpdeny [player]` | — | Decline a pending teleport request. | `marp.tpdeny` |
| `/tpcancel` | — | Cancel your outgoing teleport request. | `marp.tpcancel` |
| `/tptoggle [on\|off]` | — | Toggle receiving teleport requests. | `marp.tptoggle` |
| `/marp [help\|cancel\|bed\|reload\|info]` | `/marpadmin` | View help, cancel warmup, toggle bed home, reload config, or inspect status. | `marp.help` / varies |

---

## Permissions

All player commands are granted by default via `marp.use` (default: `true`).

### Limits & Dynamic Nodes

- `marp.homelimit.<n>` — Sets a player's home limit to the highest matching `n` (scanned from `homes.max-limit-node-scan` down to `1`, fallback `homes.default-limit`). Do not grant `marp.homelimit.*`.
- `marp.homelimit.unlimited` — Removes the home limit (default: `op`).
- `marp.favoritelimit.<n>` — Sets a player's favorite limit to the highest matching `n` (capped by `favorites.absolute-max`).
- `marp.favoritelimit.unlimited` — Removes the favorite limit (default: `op`).
- `marp.warp.access.<name>` — Required to use a warp when `warps.per-warp-permissions: true` (use `marp.warp.access.*` for all warps).

### Admin & Bypass

| Permission | Default | Grants |
| :--- | :--- | :--- |
| `marp.admin.*` | `op` | All admin permissions (`setwarp`, `delwarp`, `editwarp`, `reload`, `info`, `purge`, `homes.other`). |
| `marp.bypass.cooldown` | `op` | Ignore teleport cooldowns. |
| `marp.bypass.warmup` | `op` | Skip teleport warmup countdowns. |
| `marp.bypass.safety` | `op` | Skip destination safety checks. |
| `marp.bypass.*` | `op` | All bypass permissions. |
| `marp.*` | `op` | Parent for `marp.use`, `marp.admin.*`, `marp.bypass.*`, and unlimited limits. |

---

## Configuration

On first startup, Marp generates:

- `plugins/Marp/config.yml` — Feature toggles (`homes`, `warps`, `back`, `tpa`, `bed-home`, `favorites`), warmup/cooldown durations, safety search behavior, GUI limits, and SQLite settings.
- `plugins/Marp/messages.yml` — All player-facing text using [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags and `{placeholder}` variables.
- `plugins/Marp/marp.db` — SQLite database storing homes, warps, favorites, `/back` locations, and player settings.

Run `/marp reload` to reload `config.yml` and `messages.yml` (storage type changes require a server restart).

---

## Requirements & Building

- **Target Server**: Paper 1.21.10 (`io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT`)
- **Java**: Java 21+

Use `.\gradlew.bat` on Windows or `./gradlew` on Linux/macOS:

```bash
# Compile source code
./gradlew compileJava

# Run unit tests
./gradlew test

# Build distributable shaded jar (outputs to build/libs/Marp-1.0-SNAPSHOT-all.jar)
./gradlew build

# Launch local Paper test server
./gradlew runServer

# Run automated live server self-test
./gradlew runServer -PmarpSelfTest=true
```
