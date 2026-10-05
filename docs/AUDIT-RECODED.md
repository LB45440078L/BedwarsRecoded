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

This is a different concrete architecture from the brief's sketch (which implied a
persistent lobby and many games per long-lived server). Both are valid; the pod-per-match
model is what was actually built and tested. Rather than discard a working, tested design
for an unproven one, this pass **keeps the pod-per-match model** and fixes the real
defects in it. The divergence is called out honestly under "Remaining limitations".

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
| 8 | C | A missing world template logged a full `CompletionException` stack trace at ERROR on a plain server. | **Fixed** — one actionable WARN line |
| 9 | C | README claimed Paper 26.2, a "135 passing" badge and MockBukkit — inaccurate. | **Fixed** — README corrected to Spigot 26.3 / api 26.1 / 174 tests |
| 10 | C | `config.yml` defaults assume a cluster (`mysql` host, `templates/Glacier`), producing noise on a fresh standalone server. | **Mitigated** — degrades to a one-line warning; not changed by default (the cluster path is the primary target). |

## Bugs fixed (detail)

**#1 — the win condition.** The rule is now: a team is out when it has no members at all
(never filled, or the last player left) **or** its bed is destroyed and no member can
still respawn. `beginMatch` sweeps empty teams immediately, and `checkWinCondition`
therefore only ever counts teams that can actually win. `WinConditionTest` covers the
multi-team, abandonment, single-survivor, draw, and no-premature-end cases.

**#2 — null safety.** `evaluateEliminations` guards the session lookup so a removed
session cannot NPE the elimination path.

## Decisions

- **Keep pod-per-match; add the provisioner seam rather than replace the architecture.**
  Rationale: correctness first, and the existing design was already tested end-to-end.
  Rewriting it into a lobby + multi-game-per-server network is a multi-week effort with
  no test evidence yet; the brief's *reachable* infrastructure requirements (replaceable
  provisioner, Docker + Kubernetes, min/max capacity, idle scale-down, authentication) are
  implemented without that rewrite.
- **Delete MockBukkit rather than keep Paper test-scoped.** The only MockBukkit test
  tested MockBukkit itself, not project code; removing it makes "Spigot-only" true on
  every classpath and is enforced by a build-failing guard.
- **Keep `plugin.yml libraries:`** (the verified Spigot 26.3 build supports it, so the
  jar stays 244 KB), but degrade gracefully via the `LinkageError` guard for servers that
  do not.
- **HTTP + shared secret** for the control plane (already the design): simplest mechanism
  that gives reliability, authentication, correlation and timeouts without a broker.

## Verification performed

- `mvn clean install`: **174 tests, 0 failures** (Core 92, Spigot 26, Controller 51,
  API 5). JAR gate passes; jar is 244 KB.
- **Real Spigot 26.3 server**: plugin loads, bootstraps to STANDALONE, loads libraries,
  logs `pod_ready`, no stack traces.
- **RCON on the live server**: `/bw help`, `/bw status`, `/bw start` exercised. After
  `start` with no players the match now reaches `ENDED` (previously it would have hung in
  `RUNNING` forever) — the fix is live, not only unit-tested.
- **Real Docker daemon**: `DockerProvisionerIntegrationTest` provisions an actual
  container, health-checks it, reclaims it, and asserts the host is left clean.

## Remaining limitations

- **Lobby and multi-game-per-server are not implemented.** The system routes players via
  Velocity to a per-match pod, not to a matchmaking lobby that fills N games on a shared
  server. The controller's queue is the matchmaker. `games-per-server` is modelled and
  reported but the runtime still treats one pod as one match.
- **Full multiplayer gameplay scenarios (A/D with 8 bot players) were not run live.** The
  win condition is verified by unit tests against the real `Game` code and by the live
  `start → ENDED` transition, but a bot-driven end-to-end match was not executed here.
- **Dragon Buff** is purchasable but spawns no dragon. **NPC join** uses a named entity,
  not Citizens. **gRPC** is not used (HTTP).
- **AdvancedSlimePaper** world loading is implemented and unit-tested but not run on a
  live ASP server (the test server is plain Spigot).
- **Kubernetes** path is unchanged and was not re-run on a live cluster in this pass
  (no minikube profile is configured on this machine); the Docker path is the one
  exercised for real here.
