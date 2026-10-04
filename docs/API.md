# Public API

Everything public lives in `BedwarsRecoded-API` (`dev.bedwars.api`). The module is
platform-neutral: no Bukkit, no Velocity, no JDBC.

## DTOs (`dev.bedwars.api.dto`)

| Type | Kind | Notes |
|---|---|---|
| `PlayerStats` | record | Full persisted stat snapshot; `fresh(uuid, name, baselineElo)` factory. |
| `PlayerStatDelta` | record | Per-match additive delta: `uuid`, `username`, counters, `winner`, `experienceGained`. Applied on match end by `MatchResultPersister`. |
| `GameResult` | record | Structured end-of-game payload: winner, duration, `playerDeltas`, `bedBreaks`. |
| `BedBreak` | record | Team, breaker (`null` for system/sudden-death), millis from start. |
| `PartyInfo` | record | Party id, leader, members; `size()`, `contains(uuid)`. |
| `QueueRequest` | record | Player, priority, optional preferred group and party. |
| `TemplateDescriptor` | record | `name@version`, source (`S3`/`LOCAL`), optional checksum; `coordinate()`. |
| `TemplateSource` | enum | `S3` (production), `LOCAL` (dev-only). |
| `PodPhase` | enum | Pod lifecycle state. |
| `GamePhase` | enum | Match phase. |

## Events (`dev.bedwars.api.event`)

`GameEvent` is a **sealed interface**; `GameEventListener` is a functional interface.

Permitted records:

- `GameStarted(gameId, arenaGroup, template, playerCount)`
- `PhaseChanged(gameId, from, to)`
- `BedDestroyed(gameId, teamId, breaker)`
- `TeamEliminated(gameId, teamId)`
- `PlayerEliminated(gameId, victim, killer, finale)`
- `PlayerRespawned(gameId, player)`
- `GameEnded(gameId, winnerTeamId, durationMillis)`

Core publishes these on its own `EventBus`; adapters translate them into platform
events or infrastructure calls (see `DomainEventBridge`).

## Services (`dev.bedwars.api.service`)

| Interface | Contract |
|---|---|
| `StatsService` | Async load/apply/updateElo; additive writes keyed by UUID. |
| `LeaderboardService` | Cached `top(board, limit)`; `boards()`; `lastRefreshedAtMillis()`. |
| `TemplateSource` | `materialise(descriptor, stagingDir)` → loadable path; `productionReady()`. |
| `PodReporter` | reportReady / reportGameStarted / reportGameEnded / heartbeat / reportDraining. |
| `QueueService` | enqueue → `DispatchResult`; dequeue; `queueDepthByGroup()`. |

## JSON (`dev.bedwars.api.json`)

`JsonSupport.gson()` returns the canonical Gson for the wire protocol. It registers
adapters for `Optional` (Gson has none) and `UUID` (serialised as a string), so all
components encode DTOs identically.

## Core domain (`dev.bedwars.core`)

`Game` is the per-match aggregate: `addPlayer`, `startCountdown`, `beginMatch`,
`recordDamage`, `onDeath`, `destroyBed`, `enterSuddenDeath`, `endGame`, `results`.
Time is injected (`nowMillis`) so the class is deterministic and testable.

`GameManager` is the registry and ownership resolver used by thin listeners.

## Events in Java

```java
EventBus bus = new EventBus();
bus.subscribe(event -> {
    if (event instanceof GameEvent.GameEnded ended) {
        System.out.println("winner: " + ended.winnerTeamId().orElse("draw"));
    }
});
```

## Ranking (`dev.bedwars.core.ranking`)

| Type | Contract |
|---|---|
| `EloCalculator` | `expected(ours, theirs)`, `delta(...)`, `apply(current, delta)`; K configurable, clamped to `[0, 5000]`. |
| `MatchResultPersister` | `persist(GameResult, StatsService)` → `CompletableFuture<Void>`. Loads stats, applies every delta, recomputes team-aware ELO. The only path that writes match results to MySQL. |

## Config (`dev.bedwars.core.config`)

`ArenaConfigLoader.load(InputStream)` parses `arena.yml` into an `ArenaDefinition`:
group, team beds/spawns, generators, shop, `start-items`, and the per-group
`upgrades:` tree. When `upgrades:` is absent, `UpgradeCatalog.defaults()` is used.

## Logging (`dev.bedwars.core.logging`)

`StructuredLog.json(event, fields)` renders one dependency-free JSON line per event
(`{"event":"game_ended","game_id":"…","winner_team_id":…}`) for log aggregation.

## HTTP surface (controller)

| Method | Path | Body / result |
|---|---|---|
| POST | `/pods/ready` | `{podId, arenaGroup, template, gameId}` → `{"accepted":true}` |
| POST | `/pods/started` | `{gameId, playerCount}` |
| POST | `/pods/ended` | `{podId, result: GameResult}` — pod is dropped from the ready pool |
| POST | `/pods/heartbeat` | `{podId, gameId, tps, playerCount, phase, uptimeMillis}` |
| POST | `/pods/draining` | `{podId, gameId, remainingPlayers, phase}` — pod is dropped from the pool |
| POST | `/lobby/queue` | `QueueRequest` → `DispatchResult` (pod address + members) or `retryAfterMillis` |
| GET | `/queue/depth` | `{"solo":0,…}` |
| GET | `/lobby/arena-status` | `{"solo":{"ready":1,"queued":0},…}` for lobby NPC/sign displays |
| GET | `/metrics` | Prometheus text: `bedwars_queue_depth{group}`, `bedwars_ready_pods{group}` |
| GET | `/healthz` | `ok` |