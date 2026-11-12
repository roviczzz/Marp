# Spec Delta

## Purpose

Gives operators control over Marp's configuration, permissions, and lifecycle so the plugin can be tuned on a live server without a restart or data loss.

## ADDED Requirements

### Requirement: Configuration and messages reload without a restart

The system SHALL reload configuration and message files on request, SHALL apply the new values to subsequent commands and menus, and SHALL NOT reconnect or migrate storage during a reload.

#### Scenario: Reload applies new values

- **WHEN** an operator changes a message or a teleport setting and reloads
- **THEN** subsequent commands and menus use the new value

#### Scenario: Reload clears transient state

- **WHEN** a reload happens while warmups are pending or menus are open
- **THEN** pending warmups are cancelled and open Marp menus are closed

#### Scenario: Storage settings changed

- **WHEN** the storage type or database path changed before a reload
- **THEN** the reload keeps the existing connection and logs that a restart is required

#### Scenario: Reload requires permission

- **WHEN** a player without the reload permission runs the reload command
- **THEN** nothing is reloaded

### Requirement: Features can be switched off without losing data

The system SHALL allow homes, warps, and back to be disabled independently, SHALL answer their commands with a disabled-feature message, and SHALL NOT delete stored records when a feature is disabled.

#### Scenario: Disabled feature answers

- **WHEN** a player uses a command for a disabled feature
- **THEN** the player is told the feature is disabled and nothing changes

#### Scenario: Re-enabling restores data

- **WHEN** a feature is disabled and later enabled again
- **THEN** previously stored records are available unchanged

### Requirement: Malformed configuration degrades gracefully

The system SHALL keep serving commands when a message template or configuration value is invalid, using a safe fallback and logging the problem instead of failing the command.

#### Scenario: Broken message template

- **WHEN** a message template cannot be rendered
- **THEN** the player receives plain fallback text and the command still completes

#### Scenario: Invalid enum value

- **WHEN** a configuration value names an unknown option
- **THEN** the documented default is used and a warning is logged at load

### Requirement: Operators can inspect plugin state

The system SHALL provide a status command that reports the plugin version, storage type and location, stored home and warp counts, and cache state to an operator.

#### Scenario: Status output

- **WHEN** an operator runs the status command
- **THEN** the version, storage target, and record counts are shown

#### Scenario: Status is restricted

- **WHEN** a player without the status permission runs it
- **THEN** the request is refused

### Requirement: Permission namespaces are stable and wildcard-safe

The system SHALL grant player commands to everyone by default, grant administrative and bypass actions to operators only, and SHALL keep home-limit and per-warp access nodes in their own namespaces so that ordinary wildcards cannot grant unintended limits.

#### Scenario: Default player access

- **WHEN** a player with no explicit permissions joins
- **THEN** they can use homes, warps, and back within the default home limit

#### Scenario: Home limit wildcard

- **WHEN** a player is granted the homes wildcard only
- **THEN** they gain the homes interface but their home limit is unchanged

#### Scenario: Admin action

- **WHEN** a non-operator tries a warp administration or bypass action
- **THEN** the action is refused

#### Scenario: Numeric limit scan caps

- **WHEN** a player holds a limit node above the configured scan ceiling
- **THEN** that node is not applied and the documented ceiling governs

### Requirement: Command completion never queries the database

The system SHALL answer tab completion from in-memory caches, SHALL filter suggestions by the requesting player's access, and SHALL return an empty list rather than waiting when a cache is cold.

#### Scenario: Home completion

- **WHEN** a player tabs after `/home`
- **THEN** their own home identifiers matching the prefix are offered

#### Scenario: Hidden warps are not suggested

- **WHEN** a player tabs after `/warp` and lacks access to a warp
- **THEN** that warp's identifier is not offered

#### Scenario: Cold cache

- **WHEN** the player's data has not been loaded yet
- **THEN** completion returns no suggestions without blocking the server

### Requirement: Players can cancel their pending teleport

The system SHALL let a player cancel their own pending warmup explicitly, and SHALL treat cancelling when nothing is pending as a no-op rather than an error.

#### Scenario: Cancel pending

- **WHEN** a player runs the cancel command during a warmup
- **THEN** the warmup stops and no teleport occurs

#### Scenario: Cancel with nothing pending

- **WHEN** a player runs the cancel command with no pending teleport
- **THEN** the player is told there is nothing to cancel and no error is raised
