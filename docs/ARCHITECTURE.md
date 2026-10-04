# Architecture

## Model: pods-as-cattle

Every Bedwars match runs in **its own ephemeral pod**. There are no static arenas,
no multi-arena-per-machine, no BungeeCord. When a game ends, the pod is deleted —
that deletion *is* the world reset.

```
player/party ──► Velocity ──► controller /lobby/queue
                                    │
                                    ▼
                          GameServerSet scales 0→1   (KEDA / controller pre-warm)
                                    │
                                    ▼
                    Paper pod boots, pulls Slime template from S3
                                    │
                         POST /pods/ready ──► controller
                                    │
                          DispatchResult(podAddress) ──► Velocity routes players
                                    │
                              game runs isolated
                                    │
                       POST /pods/ended ──► controller ──► pod deleted
```

## Pod lifecycle

Mirrors OpenKruise `GameServerSet` state (`dev.bedwars.api.dto.PodPhase`):

`PENDING → READY → ALLOCATED → DRAINING → TERMINATING → DESTROYED`

- **PENDING** — scheduled, Paper booting, template downloading.
- **READY** — plugin booted and reported `POST /pods/ready`; pod is in the
  controller's `ReadyPodRegistry`, eligible for allocation.
- **ALLOCATED** — a queue request was satisfied; the pod is removed from the ready
  pool (never reused) and players are routed in.
- **DRAINING** — SIGTERM received; plugin reports `POST /pods/draining`, finishes the
  current game if possible, reports final results, then exits within the hard
  `terminationGracePeriodSeconds`.
- **TERMINATING/DESTROYED** — K8s removes the pod.

## Manager-centric design

The match is the aggregate. `Game` owns everything that must not leak between
matches: teams, beds, generators, player sessions, kill attribution. A single
`GameManager` is the registry and ownership resolver.

**Listeners are thin.** Every Bukkit handler in `BedwarsRecoded-Spigot` does exactly
one thing first: resolve the owning `Game` via `GameManager.byPlayer(uuid)`, then
delegate. No gameplay state is held on a listener. This is what makes the code
testable and prevents cross-match leakage.

## Module boundaries

```
API  ◄── Core  ◄── Spigot
 │        ▲
 │        └── Controller
 └────────── Velocity
```

- **API** depends on nothing platform-specific (only Gson for the wire format).
- **Core** depends on API only, and is **Bukkit-free** — so it is unit-tested with
  plain JUnit. This boundary is the reason MockBukkit is unnecessary (and unusable
  against Paper 26.x anyway).
- **Spigot / Velocity / Controller** are adapters: they translate platform events to
  domain calls and domain events back to platform actions.

## Controller protocol

All coordination goes through the controller; pods never talk to each other.

**Pod → controller** (HTTP POST, fire-and-forget, virtual threads):

| Endpoint | Purpose |
|---|---|
| `/pods/ready` | template loaded, accepting players |
| `/pods/started` | match began |
| `/pods/ended` | structured `GameResult` JSON (winner, duration, stat deltas, bed-break times) |
| `/pods/heartbeat` | TPS, player count, phase |
| `/pods/draining` | SIGTERM drain in progress |

**Lobby/Velocity → controller**:

| Endpoint | Purpose |
|---|---|
| `POST /lobby/queue` | request a slot; returns `DispatchResult` (pod address + members) or a retry hint |
| `GET /queue/depth` | per-group queue depth (for lobby NPC displays) |
| `GET /healthz` | liveness |

### Queue semantics

- **Capacity-aware dispatch** — a request only succeeds if a pod is already READY.
- **Party cohesion** — a party is a single queue entry dispatched atomically to one
  pod, or not at all. Parties are never split.
- **Priority** — higher `priority` first, then FIFO by request time.
- **Anti-thundering-herd** — when no capacity is available, the client is told to
  retry after an exponential-backoff-plus-jitter delay (`QueueManager.backoffMillis`).
- **Pre-warm** — when the queue cannot be satisfied, the controller asks the
  GameServerSet for one more replica so a pod is booting before the next request.

## Scaling model

- **KEDA** watches queue depth (Prometheus `bedwars_queue_depth`) and adjusts
  GameServerSet replicas horizontally, including scale-from-zero.
- **Karpenter** provisions nodes when pod demand exceeds capacity.
- 50 concurrent games = 50 pods across N nodes. No manual server configuration.

## Persistence

- **MySQL cluster** is the only store of player stats (constraint #4). Pods hold no
  durable player state.
- Schema is versioned in `schema_version` and migrated forward on startup by
  `SchemaMigrator` (idempotent, ordered, transactional, logged). See `MIGRATIONS.md`.
- HikariCP pools, one per component, sized per config.
- Writes are **additive** (`SET col = col + ?`) keyed by UUID, so concurrent pods and
  proxies cannot clobber each other.

## Ranking

`EloCalculator` implements a documented team-aware Elo (see the class Javadoc):
mean team rating vs mean opponent rating, `expected = 1/(1+10^((opp-ours)/400))`,
`delta = round(K·(result − expected))`, clamped to `[0, 5000]`. Calculations run off
the main thread; leaderboards are served from a periodically refreshed cache
(`LeaderboardCache`), never per request.

## Language features used

Applied where they genuinely improve the code, not for their own sake:

- **Records** — all DTOs, `Vec3`, `ArenaGroup`, migrations, heartbeats.
- **Pattern matching for switch** — `DomainEventBridge` (exhaustive over the sealed
  `GameEvent`), `GeneratorTier.next`, `TeamColor.legacyCode`.
- **Sealed interfaces** — `GameEvent`, so dispatchers are exhaustively checked.
- **Sequenced collections** — `List.getFirst()`/`getLast()` where order matters.
- **Virtual threads** — every I/O path (`StatsRepository`, `LeaderboardCache`,
  `HttpPodReporter`, controller HTTP executor, `LocalTemplateSource`).

Not used deliberately: **StructuredTaskScope** and **scoped values** are still
preview in the target JDK and require `--enable-preview` at both compile and runtime,
which is unsuitable for a server plugin; plain virtual-thread executors are used
instead. **Compact source files** are a script convenience and have no place in a
library.