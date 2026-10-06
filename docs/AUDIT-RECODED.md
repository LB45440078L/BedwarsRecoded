# Audit and revision (this pass)

This document is the audit that drove the current revision. It records what the project
already was, what was wrong, what was changed, why, and what remains. It is written for
the next engineer, not for a changelog.

## Method

1. Clone and read the whole tree (5 Maven modules, ~10k LOC of Java).
2. Read `docs/` and the original plugin (`LB45440078L/Bedwars`).
3. Build it (`mvn clean install`) and run the suite.
4. Load the packaged plugin on a **real Spigot 26.3 dedicated server** and drive it over
   RCON.
5. Fix the confirmed defects; re-test after every subsystem.
6. Add the infrastructure seam the brief requires (Docker *and* Kubernetes behind one
   interface) and a real Docker integration test.

## What the project was

A coherent, Kubernetes-native interpretation of the brief: **one match per ephemeral
pod, and pod destruction is the reset.** Five modules:

- `API` — platform-neutral contracts/DTOs.
- `Core` — Bukkit-free game domain (state machine, teams, beds, generators, shop,
  upgrades, traps, ranking, persistence).
- `Spigot` — the game-server plugin (thin listeners over Core).
- `Velocity` — the proxy that asks the controller where to route players.
- `Controller` — queue + capacity service (originally Kubernetes-only).

This started as a Kubernetes-native, one-match-per-pod interpretation of the brief. The
brief's sketch implied a persistent lobby and many games per long-lived server; both are
valid. Rather than discard a working, tested design, the pod-per-match model was kept
while the real defects were fixed — and multi-match hosting (many games per server) was
then added on top, so the same jar now supports either topology. See "Decisions".

## Findings

Severity: **A** = correctness/critical, **B** = compliance/security, **C** = polish.

| # | Sev | Finding | Status |
|---|---|---|---|
| 1 | A | **A match does not end when only one team remains** when the arena declares more teams than were filled, or when a team is abandoned by disconnects. `checkWinCondition` counted any team not explicitly eliminated, and elimination required a destroyed bed *and* no active member — so an empty team was "standing" forever. | **Fixed** (`Game.evaluateEliminations`, `beginMatch`; 7 new tests) |
| 2 | A | `evaluateEliminations` could NPE on a member whose session was already removed, aborting the whole death/quit path and leaving the match unstoppable. | **Fixed** (null-guard) |
| 3 | B | **Paper API on the classpath.** The plugin was described as "Paper/Spigot"; `paper-api` and MockBukkit-for-Paper were declared (test scope) and the README advertised Paper. The brief requires Spigot **exclusively**. | **Fixed** — Paper + MockBukkit removed from every classpath; `SpigotOnlyApiTest` enforces it |
| 4 | B | `plugin.yml` declared `api-version: 26.2`, not the required `26.1`. | **Fixed** (`26.1`; compile target moved to Spigot `26.3`) |
| 5 | B | Persistence bootstrap caught only `RuntimeException`; on a server without library support the missing JDBC classes would throw a `LinkageError` and **crash `onEnable`**. | **Fixed** (`catch RuntimeException \| LinkageError`) |
| 6 | B | **No controller authentication.** `/lobby/queue` and `/pods/*` were fully open; any caller could steal queue slots or report fake readiness. | **Fixed** — shared-secret auth (`BEDWARS_API_TOKEN`), constant-time compare, never logged; 9 new tests |
| 7 | B | **No `ServerProvisioner` abstraction and no Docker backend** — the controller was hard-wired to OpenKruise `GameServerSet`. The brief requires Docker *and* Kubernetes behind a replaceable interface, with min/max servers and games-per-server. | **Fixed** — `ServerProvisioner` + Kubernetes/Docker/Noop + factory + config; real Docker integration test |
| 8 | C | A missing world template logged a full `CompletionException` stack trace at ERROR. | **Fixed** — one actionable WARN line |
| 9 | C | README claimed Paper 26.2, a "135 passing" badge and MockBukkit — inaccurate. | **Fixed** — README corrected to Spigot 26.3 / api 26.1 / 174 tests |
| 10 | C | `config.yml` defaults assume a cluster (`mysql` host, `templates/Glacier`), producing noise on a freshly started server. | **Mitigated** — degrades to a one-line warning; not changed by default (the cluster path is the primary target). |

## Bugs fixed (detail)

**#1 — the win condition.** The rule is now: a team is out when it has no members at all
(never filled, or the last player left) **or** its bed is destroyed and no member can
still respawn. `beginMatch` sweeps empty teams immediately, and `checkWinCondition`
therefore only ever counts teams that can actually win. `WinConditionTest` covers the
multi-team, abandonment, single-survivor, draw, and no-premature-end cases.

**#2 — null safety.** `evaluateEliminations` guards the session lookup so a removed
session cannot NPE the elimination path.

## Decisions

- **Add the provisioner seam first, then implement multi-match hosting on a server.**
  The original design was one-match-per-pod and already tested end-to-end, so the
  provisioner abstraction (Docker + Kubernetes, min/max capacity, idle scale-down,
  authentication) was added without disturbing it. Multi-match hosting (`GameHost` +
  `GameFactory` + `GameWorldService`) was then built on the Bukkit-free core and verified
  live: a server with `games-per-server: 3` created three independent matches, each in
  its own world, reported its free match slots, and reclaimed them when matches ended.
  Both topologies ship in the same jar, selected by config.
- **Delete MockBukkit rather than keep Paper test-scoped.** The only MockBukkit test
  tested MockBukkit itself, not project code; removing it makes "Spigot-only" true on
  every classpath and is enforced by a build-failing guard.
- **Keep `plugin.yml libraries:`** (the verified Spigot 26.3 build supports it, so the
  jar stays 244 KB), but degrade gracefully via the `LinkageError` guard for servers that
  do not.
- **HTTP + shared secret** for the control plane (already the design): simplest mechanism
  that gives reliability, authentication, correlation and timeouts without a broker.

## Verification performed

- `mvn clean install`: **189 tests, 0 failures** (Core 95, Spigot 28, Controller 61,
  API 5). JAR gate passes; jar is 270 KB.
- **Real Spigot 26.3 server**: plugin loads, stages the arena, loads libraries, registers
  with the controller, logs `pod_ready`, no stack traces.
- **RCON on the live server**: `/bw help`, `/bw status`, `/bw start` exercised. After
  `start` with no players the match now reaches `ENDED` (previously it would have hung in
  `RUNNING` forever) — the fix is live, not only unit-tested.
- **Real Docker daemon**: `DockerProvisionerIntegrationTest` provisions an actual
  container, health-checks it, reclaims it, and asserts the host is left clean.
- **Live multi-match run (Spigot 26.3, RCON)**: with `games-per-server: 3`, `/bw create 2`
  created `match-1`/`match-2` (`2/3`, free slots `1`), each match loaded its own
  `bw-match-N` world; `/bw start all` ran the countdown → begin → end path and the
  finished matches were pruned with their worlds released; `/bw stop all` returned the
  server to `0` matches and `3` free slots. `/bw status`, `/bw create`, `/bw stop` and a
  graceful `stop` (`game_server_shutdown complete`) were exercised.
- **Live bot-driven dragon run (Spigot 26.3, Python bot clients)**: two offline-mode bots
  (`mcbot`) joined a live server; the match auto-started (`WAITING → COUNTDOWN →
  RUNNING`), reached `SUDDEN_DEATH` after the configured 20 s, and the server logged
  `dragons_spawned total=1`, `dragon_map_damage blocks_first_pass=147` and
  `dragon_knockback player=RedBot strength=3.0`; the bot's own feed showed the
  sudden-death chat broadcast and its position thrown from `8.5,-60,8.5` to
  `5.1,-54.2,10.3`. `dragons_cleared dragons=1 blocks_destroyed=147` confirmed cleanup.
- **Live minikube cluster**: controller image built into minikube and deployed; the pod
  reached Ready and served `/healthz` (`ok`), `/metrics` (`bedwars_*` gauges) and
  `/infra` reporting `provisioner: KUBERNETES` with
  `GameServerSet bedwars/bedwars-solo servers[0..50] gamesPerServer=25`. The OpenKruise
  `GameServerSet` CRD was installed to validate the manifests against the real schema.
- **Live Docker Compose stack**: `docker compose up` brought the controller up healthy
  with the DOCKER provisioner; a `POST /lobby/queue` provisioned a real container
  (`bedwars-game-1`, image `bedwars-spigot:1.0.0`) running actual Spigot (it staged the
  Glacier template from the baked copy and logged `server_ready`). The container joined
  the Compose network, so it reached the controller and **registered**
  (`registeredServers: 1`, `freeSlots: 25`) with no `ConnectException`. This path
  initially failed for two reasons worth recording: the controller handed the pod
  `CONTROLLER_URL` when the plugin only reads `BEDWARS_CONTROLLER_URL`, and it started
  the container on the default bridge with no `--network`, so the controller's name did
  not resolve. Both are fixed and covered by `DockerProvisionerTest`.
- **Pre-warm stampede fixed.** The allocator asked for a new server on *every* dispatch
  retry, and a server needs ~30 s to boot and register, so one `POST /lobby/queue`
  started 13 containers (bounded only by the maximum). `PrewarmGuard` now treats a
  started-but-unregistered server as a boot in flight and allows at most one pre-warm
  per grace window; a repeat of the same single dispatch starts exactly **1** container
  and still registers it. Covered by `PrewarmGuardTest`.
- **Game-pod image builds real Spigot**: the Docker image previously downloaded Paper;
  it now builds Spigot 26.3 from source with the official BuildTools (verified: the
  BuildTools run produced an 85 MB `spigot-26.3` jar byte-identical in size to the
  reference build, and the image was built on minikube).

## Remaining limitations

- **Multi-game-per-server is implemented and verified live; a separate long-lived lobby
  server is not.** The controller's queue is still the matchmaker and Velocity routes the
  dispatched group to a game server. The *player-facing* lobby (`/bw join` on a dedicated
  lobby that then transfers through Velocity) is configured, but its proxy-transfer hop
  was not run in this environment (no Velocity + bot client here). Multi-match hosting on
  one server — the substantive part of the model — is done and tested.
- **Full multiplayer gameplay scenarios (8-player A/D) were not run live.** A real
  two-bot match *was* driven end-to-end (join → countdown → running → sudden death →
  dragons), and the win condition is verified by unit tests against the real `Game`
  code plus the live `start → ENDED` transition, but a full 8-player game with combat,
  beds and traps was not played out.
- **Dragon Buff** now spawns dragons: verified live (spawn, map destruction, knockback,
  cleanup) and unit-tested for the per-team plan. **NPC join** uses a named entity, not
  Citizens. **gRPC** is not used (HTTP).
- **AdvancedSlimePaper** world loading is implemented and unit-tested but not run on a
  live ASP server (the test server is plain Spigot).
- **Kubernetes** was re-run on a live minikube cluster in this pass (OpenKruise
  `GameServerSet` CRD installed, controller deployed and queried) and a real Spigot
  **game pod** was scaled up on it: `bedwars-solo-0` reached `1/1 Running`, booted
  Spigot in POD mode and registered with the controller (`servers: 1`,
  `free_slots{group="solo"} 25`). It ran without a real arena template, though: the pod
  asked the cluster's MinIO endpoint for the Glacier world, which was not running, so it
  fell back to a generated world (a one-line warning, not a crash). A full match inside
  a K8s pod with an S3-served template has still not been played end-to-end.
