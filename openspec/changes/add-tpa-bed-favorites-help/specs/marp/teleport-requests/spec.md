# Spec Delta

## Purpose

Allows players to send, accept, deny, and cancel player-to-player teleport requests safely using Marp's teleport pipeline, with clickable chat prompts and request blocking.

## ADDED Requirements

### Requirement: Players can send teleport requests in both directions

The system SHALL let a player send a teleport request either to travel to another online player (`/tpa <player>`) or to invite that player to travel to them (`/tpahere <player>`), rejecting self-requests and unknown or hidden targets.

#### Scenario: Send `/tpa` request

- **WHEN** Alice runs `/tpa Bob` while both are online and Bob can accept requests
- **THEN** a pending request from Alice to Bob is created and both players are notified

#### Scenario: Send `/tpahere` request

- **WHEN** Alice runs `/tpahere Bob` while both are online and Bob can accept requests
- **THEN** a pending request inviting Bob to teleport to Alice is created and both players are notified

#### Scenario: Self-request is rejected

- **WHEN** Alice runs `/tpa Alice` or `/tpahere Alice`
- **THEN** the command is rejected and no request is created

#### Scenario: Offline or vanished target is rejected

- **WHEN** Alice runs `/tpa Missing` or targets a player hidden from her visibility
- **THEN** the target is reported as unknown and no request is created

### Requirement: Incoming requests show clickable accept and deny buttons

The system SHALL send incoming teleport requests with clickable accept and deny chat actions targeting that specific requester, along with hover text showing direction, destination world, and remaining expiry time.

#### Scenario: Request prompt includes interactive buttons

- **WHEN** Bob receives a `/tpa` or `/tpahere` request from Alice
- **THEN** the message contains clickable accept and deny controls bound to Alice with hover details showing the direction, world, and timeout

### Requirement: Requests obey expiry, one-outgoing-request, and disambiguation rules

The system SHALL expire requests after a configured timeout, allow at most one pending outgoing request per requester, allow multiple incoming requests per target up to a cap, and refuse ambiguous bare `/tpaccept` or `/tpdeny` commands by listing clickable options.

#### Scenario: Request expires after timeout

- **WHEN** a pending request is not accepted, denied, or cancelled before the configured timeout elapses
- **THEN** the request is removed and both players are notified that it expired

#### Scenario: Second outgoing request is blocked

- **WHEN** Alice already has a pending outgoing request and runs `/tpa Charlie`
- **THEN** the second request is rejected until she cancels or the first request resolves

#### Scenario: Multiple incoming requests require disambiguation

- **WHEN** Bob has pending requests from both Alice and Charlie and runs `/tpaccept` with no player name
- **THEN** neither request is accepted and Bob receives a list of pending requests with clickable accept and deny controls

#### Scenario: Explicit player resolves ambiguity

- **WHEN** Bob has pending requests from Alice and Charlie and runs `/tpaccept Alice`
- **THEN** Alice's request is accepted and Charlie's remains pending

#### Scenario: Requester cancels outgoing request

- **WHEN** Alice runs `/tpcancel` while her request to Bob is pending
- **THEN** the request is removed and both players are notified

### Requirement: Teleport pipeline rules apply to the mover, not the anchor

The system SHALL apply warmup, movement/damage/command cancellation, teleport cooldown, safety checks, and `/back` origin recording exclusively to the player who moves, while keeping the anchor player free to act.

#### Scenario: `/tpa` applies pipeline rules to requester

- **WHEN** Bob accepts `/tpa` from Alice
- **THEN** Alice undergoes warmup, safety validation, cooldown, and `/back` recording while Bob is not subjected to warmup or cooldown

#### Scenario: `/tpahere` applies pipeline rules to accepter

- **WHEN** Bob accepts `/tpahere` from Alice
- **THEN** Bob undergoes warmup, safety validation, cooldown, and `/back` recording while Alice is not subjected to warmup or cooldown

#### Scenario: Arrival uses live anchor location and safety check

- **WHEN** the anchor player walks to a nearby safe spot during the mover's warmup
- **THEN** arrival resolves the anchor's current location and validates destination safety before teleporting the mover

### Requirement: Players can toggle incoming requests and deny cooldown prevents spam

The system SHALL provide a persistent per-player `/tptoggle` command that blocks incoming requests when enabled, and SHALL enforce a configurable per-pair cooldown after a request is denied.

#### Scenario: Toggled-off target blocks requests

- **WHEN** Bob enables `/tptoggle` and Alice runs `/tpa Bob`
- **THEN** Alice's request is rejected and Bob receives no notification

#### Scenario: Toggle survives restart

- **WHEN** Bob turns `/tptoggle` on and the server restarts
- **THEN** incoming requests to Bob remain blocked until he toggles it off

#### Scenario: Deny triggers per-pair cooldown

- **WHEN** Bob denies a request from Alice and Alice immediately sends `/tpa Bob` again
- **THEN** the new request is refused for the remaining deny cooldown

### Requirement: Edge cases cancel or reject requests cleanly

The system SHALL enforce cross-world restrictions, optional combat-tag guards, and immediate cancellation if either player disconnects, dies, or changes to a disallowed world while a request or warmup is active.

#### Scenario: Peer disconnects while request is pending

- **WHEN** either player quits while a request is pending
- **THEN** the request is cleared and the online peer is notified

#### Scenario: Anchor disconnects or dies during warmup

- **WHEN** the anchor player quits or dies during the mover's warmup
- **THEN** the mover's warmup is cancelled with a clear message and no cooldown starts

#### Scenario: Cross-world restriction enforced

- **WHEN** cross-world TPA is disabled and the two players are in different worlds at send, accept, or arrival
- **THEN** the request or warmup is rejected with the cross-world disabled message

#### Scenario: Combat-tagged player blocked when enabled

- **WHEN** combat tagging is configured above zero and a tagged mover tries to send or accept a request
- **THEN** the request is refused without starting a warmup
