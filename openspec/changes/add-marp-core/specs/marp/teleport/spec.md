# Spec Delta

## Purpose

Moves a player to a destination after a configurable delay, only when the destination is physically safe, and without blocking the server while worlds and chunks load.

## ADDED Requirements

### Requirement: Teleports pass through a warmup

The system SHALL delay a teleport by a configured number of seconds per action type, show the remaining time to the player, and skip the warmup when the player holds the warmup bypass permission or the configured duration is zero.

#### Scenario: Countdown shown

- **WHEN** a player starts a teleport with a warmup above zero
- **THEN** the player sees a countdown before arriving

#### Scenario: Bypass

- **WHEN** a player with the warmup bypass permission starts a teleport
- **THEN** the player arrives without a countdown

#### Scenario: Zero warmup

- **WHEN** the configured warmup for that action is zero
- **THEN** the teleport proceeds immediately

### Requirement: Warmup is cancelled by defined events

The system SHALL cancel a pending warmup when the player moves beyond a small tolerance, takes damage that is not ongoing environmental state, runs another command, is teleported by something else, quits, or dies. It SHALL keep one pending teleport per player.

#### Scenario: Movement cancels

- **WHEN** a player moves more than the configured tolerance during the warmup
- **THEN** the teleport is cancelled and the player is told movement cancelled it

#### Scenario: Hostile damage cancels

- **WHEN** a player is hit or takes fall, fire, lava, void, or magic damage during the warmup
- **THEN** the teleport is cancelled and the player is told damage cancelled it

#### Scenario: Debuff damage does not cancel

- **WHEN** the player takes poison, wither, fire-tick, drowning, starvation, contact, or suffocation damage during the warmup
- **THEN** the warmup continues

#### Scenario: Another command cancels

- **WHEN** a player runs a command that is not explicitly allowed during the warmup
- **THEN** the teleport is cancelled

#### Scenario: External teleport cancels

- **WHEN** something other than this pending teleport moves the player
- **THEN** the teleport is cancelled and the player is told they were teleported elsewhere

#### Scenario: New request replaces the old one

- **WHEN** a player starts a second teleport while one is pending
- **THEN** the first is cancelled, only the second completes, and no duplicate cancellation message is sent

### Requirement: Teleports enforce per-action cooldowns

The system SHALL refuse a teleport while that action's cooldown is active, SHALL start the cooldown only after a successful arrival, and SHALL allow the cooldown bypass permission to ignore both existing and new cooldowns.

#### Scenario: Cooldown blocks

- **WHEN** a player uses `/home` twice within the home cooldown
- **THEN** the second request is refused with the remaining seconds

#### Scenario: Cooldown starts on arrival

- **WHEN** a teleport is cancelled or fails
- **THEN** no cooldown is started

#### Scenario: Bypass

- **WHEN** a player with the cooldown bypass permission teleports repeatedly
- **THEN** no cooldown is applied

#### Scenario: Independent cooldowns

- **WHEN** a player is on a home cooldown and uses `/back`
- **THEN** the back cooldown is evaluated separately

### Requirement: Destinations are validated against collision geometry

The system SHALL evaluate a destination by testing the player-sized collision box against the collision shapes of surrounding blocks, and SHALL reject destinations inside solid geometry, inside configured hazards, outside the world border, in the void, or above an unsafe drop.

#### Scenario: Slab floor is accepted

- **WHEN** the destination is a position standing on a slab, stair, or snow layer
- **THEN** the destination is accepted rather than reported as suffocation

#### Scenario: Suffocation is rejected

- **WHEN** the player's box would intersect a solid block
- **THEN** the destination is reported unsafe with a suffocation reason

#### Scenario: Hazard is rejected

- **WHEN** the feet or head space contains a configured hazard
- **THEN** the destination is reported unsafe with that hazard as the reason

#### Scenario: Unsafe drop is rejected

- **WHEN** no floor exists within the configured fall distance below the destination
- **THEN** the destination is reported unsafe with a fall or void reason

#### Scenario: Border is respected

- **WHEN** the destination is outside the world border
- **THEN** the destination is refused rather than moved inside

### Requirement: A partial floor adjusts the arrival height

The system SHALL place the player on top of a partial floor within the configured fall distance instead of refusing the teleport or leaving them floating.

#### Scenario: Slightly above a slab

- **WHEN** a home was saved just above a slab or stair
- **THEN** the player arrives on the surface of that block

### Requirement: Unsafe destinations follow the configured mode

The system SHALL refuse an unsafe destination in BLOCK mode, warn and continue in WARN mode, and in SEARCH mode look for a nearby safe position within a bounded search before refusing.

#### Scenario: Search finds a spot

- **WHEN** the destination is unsafe and a safe position exists within the search radius
- **THEN** the player is moved to a safe position and told the destination was adjusted

#### Scenario: Search finds nothing

- **WHEN** the destination is unsafe and no safe position exists within the search radius
- **THEN** the player is told no safe spot was found and no teleport happens

#### Scenario: Warning mode

- **WHEN** the mode is WARN and the destination is unsafe
- **THEN** the player is warned about the hazard and the teleport continues

#### Scenario: Bypass

- **WHEN** a player with the safety bypass permission teleports
- **THEN** the destination is not validated

### Requirement: Worlds and chunks are handled asynchronously

The system SHALL NOT load a world implicitly for a teleport, SHALL load the destination chunk asynchronously before reading blocks, and SHALL fail cleanly if the world or chunk is unavailable in time.

#### Scenario: Unloaded world

- **WHEN** the destination world is not loaded
- **THEN** the player is told the world is unavailable and the record is kept

#### Scenario: Chunk load timeout

- **WHEN** the destination chunk does not load within the timeout
- **THEN** the teleport fails with a message and the session is cleared

#### Scenario: Player leaves during the load

- **WHEN** the player disconnects before the chunk finishes loading
- **THEN** the teleport is abandoned without errors or messages

### Requirement: Failed teleports leave the player unchanged

The system SHALL NOT move, damage, change inventory, or start a cooldown for the player when a teleport fails, and SHALL NOT record a back origin for a teleport that did not happen.

#### Scenario: Teleport rejected by the server

- **WHEN** the platform refuses the teleport
- **THEN** the player stays where they were, no cooldown starts, and no back origin is written

#### Scenario: Player preparation is restored safely

- **WHEN** a teleport proceeds while the player is in a vehicle or has an inventory open
- **THEN** the vehicle is exited or inventory closed according to configuration before arrival, without dropping items
