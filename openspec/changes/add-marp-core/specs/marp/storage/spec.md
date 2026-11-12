# Spec Delta

## Purpose

Keeps every home, warp, and back location durable across restarts, and keeps all database work off the server's main thread.

## ADDED Requirements

### Requirement: Records persist across restarts

The system SHALL store homes, warps, and back locations durably, keyed by player UUID and world name, so they survive normal shutdowns and crashes.

#### Scenario: Home survives a restart

- **WHEN** a player creates a home and the server is restarted
- **THEN** the home resolves to the same world and coordinates afterwards

#### Scenario: Crash keeps the last committed write

- **WHEN** the server stops abruptly after a successful write
- **THEN** the last committed record is intact on the next start

#### Scenario: Offline players keep their data

- **WHEN** a player disconnects
- **THEN** their homes, warps they created, and back entries are unchanged

### Requirement: Database work never blocks the tick

The system SHALL perform every database operation asynchronously and apply the result to gameplay only after returning to the main thread.

#### Scenario: Command answers immediately

- **WHEN** a player runs a command that needs a database read
- **THEN** the command returns without waiting for the query

#### Scenario: Result is applied on the main thread

- **WHEN** an asynchronous query completes with a home record
- **THEN** the teleport or menu built from it is created on the main thread

### Requirement: Schema upgrades run automatically

The system SHALL version its schema, apply pending migrations in order during startup, and refuse to start against a database written by a newer plugin version.

#### Scenario: Fresh database

- **WHEN** no database file exists
- **THEN** the current schema is created and marked at the current version

#### Scenario: Older database

- **WHEN** the database is at an older schema version
- **THEN** pending migrations run before commands are served

#### Scenario: Newer database

- **WHEN** the database schema version is newer than the plugin supports
- **THEN** the plugin refuses to start and logs the reason instead of writing to it

### Requirement: Concurrent edits cannot silently overwrite

The system SHALL detect a stale edit with a per-record revision and report a conflict rather than overwriting a newer value.

#### Scenario: Second editor loses

- **WHEN** two sessions edit the same entry and the second saves after the first
- **THEN** the second save is rejected as a conflict and reports the entry changed

#### Scenario: Same-millisecond writes are still detected

- **WHEN** two edits arrive close enough that their timestamps match
- **THEN** the revision check still rejects the stale write

### Requirement: Writes are atomic and shutdown is bounded

The system SHALL commit each mutation as a single transaction and SHALL finish or abandon pending work within a bounded time when the plugin disables.

#### Scenario: Interrupted mutation

- **WHEN** a write is interrupted mid-transaction
- **THEN** the database contains either the previous value or the new one, never a partial row

#### Scenario: Shutdown does not hang

- **WHEN** the plugin disables with pending writes
- **THEN** it waits a bounded time, checkpoints, and logs a warning instead of hanging the server shutdown

### Requirement: Storage readiness is visible to commands

The system SHALL report storage lifecycle state so commands can fail clearly while the database is starting, and SHALL disable the plugin if initialisation fails.

#### Scenario: Command during startup

- **WHEN** a player runs a home command before storage is ready
- **THEN** the command replies that storage is starting and changes nothing

#### Scenario: Initialisation failure

- **WHEN** the database cannot be opened or migrated
- **THEN** the plugin logs the cause and disables instead of serving partial state
