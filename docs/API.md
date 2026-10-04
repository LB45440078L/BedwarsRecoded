# Public API

Everything public lives in `BedwarsRecoded-API` (`dev.bedwars.api`). The module is
platform-neutral: no Bukkit, no Velocity, no JDBC.

## DTOs (`dev.bedwars.api.dto`)

| Type | Kind | Notes |
|---|---|---|
| `PlayerStats` | record | Full persisted stat snapshot; `fresh(uuid, name, baselineElo)` factory. |
| `PlayerStatDelta` | record | Per-match additive delta applied on match end. |
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