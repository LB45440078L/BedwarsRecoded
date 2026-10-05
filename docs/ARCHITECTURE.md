# Architecture

## Model: a lobby, dedicated servers, and many matches per server

The network has three kinds of component: a **lobby**, one or more **dedicated game
servers**, and a **Velocity** proxy in front of both. Players never type an IP.

```
Player ──► Velocity ──► Lobby
                          │  /bw join  (matchmaking: queue until a match can start)
                          ▼
                   controller /lobby/queue
                          │  reserve a match slot
                          ▼
        existing server with a free slot? ──yes──► route players there
                          │no
                          ▼
        provision a new server (Docker / Kubernetes) → health check → READY
                          │
                          ▼
        Velocity transfers players to the game server
                          │
                          ▼
        the match runs; a server hosts up to N matches at once
                          │
                          ▼
        match ends → players return to the lobby; idle servers are reclaimed
```

Two topologies are supported by the *same* jar, selected by `arena.games-per-server`:

- **`games-per-server: 1`** (Kubernetes default) — one match per server. This keeps the
  original behaviour: a game server is a disposable pod, and pod destruction is the
  world reset.
- **`games-per-server: N > 1`** — a long-lived dedicated server hosts up to N concurrent
  matches. Each match gets its own world (`GameWorldService`) and its own `Game`; the
  old "one pod = one match" assumption no longer holds. The server reports its free
  **match slots** to the controller, so matchmaking capacity is
  `servers × games-per-server`, never a hard-coded number.

The lobby holds **no game state** — it only queues, matchmakes and asks the controller
for capacity. Gameplay lives entirely on the game servers.

## Server lifecycle

A dedicated server mirrors OpenKruise `GameServerSet` state
(`dev.bedwars.api.dto.PodPhase`) for its own life, independent of the matches it hosts:

`PENDING → READY → ACTIVE → DRAINING → TERMINATING → DESTROYED`

- **PENDING** — scheduled, booting, template downloading.
- **READY** — plugin booted and reported `POST /pods/ready` with its match capacity;
  the server is in the controller's `ServerRegistry`, eligible for allocation.
- **ACTIVE** — one or more match slots are reserved or running on it. A slot is never
  handed out twice: allocation is atomic — the slot is decremented *before* the players
  are routed.
- **DRAINING** — SIGTERM received; the plugin reports `POST /pods/draining`, aborts its
  matches, flushes results, then exits within the hard `terminationGracePeriodSeconds`.
- **TERMINATING/DESTROYED** — K8s removes the pod (or an idle Docker container is
  reclaimed by scale-down).

An individual **match** has its own smaller state machine
(`WAITING → COUNTDOWN → RUNNING → SUDDEN_DEATH → ENDED`), owned by `Game`.

## Manager-centric design

The match is the aggregate. `Game` owns everything that must not leak between
matches: teams, beds, generators, player sessions, kill attribution. A single
`GameManager` is the registry and ownership resolver.

**Listeners are thin.** Every Bukkit handler in `BedwarsRecoded-Spigot` does exactly
one thing first: resolve the owning `Game` via `GameManager.byPlayer(uuid)`, then
delegate. No gameplay state is held on a listener. This is what makes the code
testable and prevents cross-match leakage.

**The host owns many matches.** `GameHost` is the registry a server's entrypoint talks
to: `join` fills the emptiest accepting match, creating a new one only when none can
take the player and the server is still below `games-per-server`; `freeSlots` reports
remaining capacity; `pruneFinished` drops ended matches so their slots and worlds are
reclaimed; and the matches are fully independent of each other. `GameFactory` builds a
match from the arena template. Both classes are Bukkit-free and unit-tested
(`GameHostTest`).

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
| `/pods/ready` | template loaded; reports the server's match capacity |
| `/pods/capacity` | current free match slots (sent when they change) |
| `/pods/started` | match began |
| `/pods/ended` | structured `GameResult` JSON (winner, duration, stat deltas, bed-break times) |
| `/pods/heartbeat` | TPS, player count, phase, uptime (periodic) |
| `/pods/draining` | SIGTERM drain in progress; **removes the pod from the ready pool** |

A server that reports `draining` carries its `podId`, and the controller immediately
drops it from the pool — otherwise a later `/lobby/queue` could be dispatched to a
server that no longer exists. A `/pods/ended` (one match finishing) does **not** remove
the server: it frees that match's slot and the server stays available.

**Lobby/Velocity → controller**:

| Endpoint | Purpose |
|---|---|
| `POST /lobby/queue` | request a slot; returns `DispatchResult` (pod address + members) or a retry hint |
| `GET /queue/depth` | per-group queue depth |
| `GET /lobby/arena-status` | per-group `{freeSlots, queued}` for lobby displays |
| `GET /healthz` | liveness |
| `GET /metrics` | Prometheus exposition KEDA scales on |

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
- Capacity is `servers × games-per-server`. With `games-per-server: 25`, 50 concurrent
  games fit on **2** servers; with the default `1` they need 50. No manual server
  configuration either way.

## Persistence

- **MySQL cluster** is the only store of player stats (constraint #4). Pods hold no
  durable player state.
- Schema is versioned in `schema_version` and migrated forward on startup by
  `SchemaMigrator` (idempotent, ordered, transactional, logged). See `MIGRATIONS.md`.
- HikariCP pools, one per component, sized per config.
- Writes are **additive** (`SET col = col + ?`) keyed by UUID, so concurrent pods and
  proxies cannot clobber each other.

### Match persistence path

`Core` produces a `GameResult`; `MatchResultPersister` turns it into database writes:

1. load current stats for every participant (async, batched);
2. apply each player's additive `PlayerStatDelta`;
3. recompute ELO team-vs-team — mean rating of the winning side against the mean of the
   losing side — and `updateElo` for each player. Draws skip ELO.

It completes as a `CompletableFuture` and never runs on the tick thread. This is the
only path that writes match stats; the plugin calls it exactly once per match (guarded
by a flag, so `onDisable` cannot double-write).

## Observability

- **Heartbeats** (`TpsMeter`) report TPS, player count, phase and uptime every
  `controller.heartbeat-seconds`.
- **Metrics** — `/metrics` exposes `bedwars_queue_depth{group}`, `bedwars_free_slots{group}`
  and `bedwars_servers`, the gauges KEDA scales on.
- **Structured logging** — with `logging.json: true` (`StructuredLog`), gameplay events
  are emitted as one JSON object per line carrying `event` plus `game_id`,
  `player_uuid`, `team_id`, etc., so Loki/ELK can index them without text parsing.
- **Config hot-reload** — `/bw reload` re-reads `config.yml` + `arena.yml` and
  re-applies shop, upgrade tree, start items and countdown **without restarting the
  game**. Arena geometry and `games-per-server` need a server restart; individual
  matches come and go without one.

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