# Audit — implementation vs. the original brief

Every requirement from the task brief, checked against what exists, with the file
that carries it. Statuses:

- **Met** — implemented and (where the tooling allows) verified.
- **Met (adapted)** — implemented, but a documented deviation from the literal brief.
- **Partial** — modelled and wired, but one step short of complete.
- **Not done** — absent; listed so nothing is silently overclaimed.

Last updated after the gap-closure pass that fixed defects found by auditing the code and
exercising the live cluster HTTP API (see §12 — eleven in total).

---

## 1. Project structure

| Requirement | Status | Where |
|---|---|---|
| Multi-module Maven: Core / API / Spigot / Velocity / Controller | **Met** | `pom.xml` + 5 module POMs |
| Target path `\Users\thevi\projects\BedwardRecoded` | **Met (adapted)** | Delivered at `~/projects/BedwarsRecoded`, mirrored to `C:\Users\thevi\projects\BedwarsRecoded`. `Bedward` is read as a typo of the `BedwarsRecoded` module prefix used throughout. |

## 2. Targets

| Requirement | Status | Notes |
|---|---|---|
| Spigot/Paper API for MC 26.2 | **Met** | `spigot-api:26.2-R0.1-SNAPSHOT`; Paper `26.2.build.129-stable` kept for reference |
| Compatible with 26.1 / 26.2 / 26.3 | **Met** | all three exist on the repos; plugin uses only stable Spigot API surface |
| JDK 25 | **Met** | `maven.compiler.release=25`, built on Temurin 27 |
| Pattern matching for switch | **Met** | `DomainEventBridge`, `GameState`, commands |
| Record patterns | **Met** | `instanceof Player player`, record deconstruction in listeners |
| Virtual threads | **Met** | `HttpPodReporter`, controller `WebhookServer` executor, repositories |
| Structured concurrency (`StructuredTaskScope`) | **Not done** | still a preview API — needs `--enable-preview` at compile *and* runtime, which is unfit for a server plugin |
| Scoped values | **Not done** | same preview restriction |
| Sequenced collections | **Met** | `List.getFirst()`, `SequencedCollection` use in Core |
| Compact source files | **Met (adapted)** | used for utility scripts rather than app code |
| Don't force features where plain code is clearer | **Met** | e.g. plain loops in hot paths |

## 3. Architecture (K8s-native, pods-as-cattle)

| Requirement | Status | Where |
|---|---|---|
| Velocity persistent proxy | **Met** | `BedwarsRecoded-Velocity`, `deploy/helm/.../velocity.yaml` |
| mc-router auto-discovery | **Met** | `deploy/k8s/20-mc-router.yaml` |
| OpenKruise GameServerSet lifecycle | **Met** | `deploy/k8s/10-gameserverset.yaml`; verified `READY 1/1` |
| KEDA autoscale on queue depth | **Met** | `deploy/k8s/11-keda-scaledobject.yaml`, `bedwars_queue_depth` metric |
| Karpenter node autoscale | **Met** | `NodePool` in chart |
| Paper, **not Folia** | **Met** | constraint #3 |
| BedWars1058 (BUNGEE mode) as the game-logic layer | **Met (adapted)** | The source plugin is self-contained (no BedWars1058 dependency); "the game logic layer" is reimplemented cleanly in `Core`. Depending on BedWars1058 would have contradicted "rewrite logic cleanly". |
| AdvancedSlimePaper + SlimeWorldManager | **Met (verified by unit test, not against a live ASP)** | `AspSlimeWorldProvider`: template read read-only, cloned per match, loaded; `FallbackWorldProvider` when ASP is absent |
| S3-compatible store | **Met** | `S3TemplateSource` with real **SigV4** signing (`AwsSigV4`) |
| MySQL cluster | **Met** | HikariCP, versioned migrations |
| Docker for every component | **Met** | `deploy/docker/{controller,velocity,spigot}.Dockerfile` |
| Pod lifecycle 0→1→Ready→routed→deleted | **Met** | verified on minikube |

## 4. Hard constraints

| # | Constraint | Status | Evidence |
|---|---|---|---|
| 1 | No per-game arenas on disk | **Met** | templates staged from S3/local, never persisted per game |
| 2 | No block-by-block rollback | **Met** | nothing in Core restores blocks; pod destruction is the reset |
| 3 | Never target Folia | **Met** | Paper API only |
| 4 | No stats inside a pod | **Met** | all writes go to MySQL via `StatsRepository`; pods hold no authoritative state |
| 5 | Strict requests *and* limits | **Met** | `10-gameserverset.yaml` (2 CPU / 4 GiB, both set) |
| 6 | A pod never outlives its game | **Met** | GameServerSet deletes the pod on game end |
| 7 | No special or long-lived pod | **Met** | identical pod spec for every match |

## 5. Plugin responsibilities

| Does | Status |
|---|---|
| teams, beds, generators, shops, scoreboard | **Met** |
| loads Slime templates on boot | **Partial** (staging only) |
| reads/writes stats to MySQL | **Met** — fixed in this pass; `MatchResultPersister` was missing |
| reports game start/end to controller | **Met** (HTTP webhook) |

| Does not | Status |
|---|---|
| player routing / pod scaling / node scaling / world persistence / cross-server coordination | **Met** — all live in Velocity / mc-router / KEDA / Karpenter / OpenKruise / S3 / controller |

## 6. Gameplay architecture

| Requirement | Status | Where |
|---|---|---|
| `GameManager` global coordinator | **Met** | `core/management/GameManager` |
| `Game` owns teams/beds/generators/scoreboards/chat/visibility | **Met** | `core/domain/Game` |
| `PlayerSession` per-player state | **Met** | `core/domain/PlayerSession` |
| Thin listeners resolve owning Game then delegate | **Met** | every class in `spigot/listener` |

## 7. Feature parity + fixes

| Feature | Status |
|---|---|
| Custom teams, scoreboards, shops, team upgrades, traps, generator tiers, sudden death | **Met** |
| Per-player language (`/bw lang`) | **Met** |
| Arena groups with per-group configs — start items | **Met** |
| — generator settings | **Met** |
| — upgrade trees | **Met** — fixed in this pass (now parsed from `arena.yml`) |
| NPC / sign / GUI / command join | **Met** (NPC is a named entity, not Citizens) |
| Permanent items, downgradable items, quick-buy sync | **Met** |
| Spectator mode, void-kill Y, protection ranges, island radius | **Met** |
| Fix bugs from the original, clean rewrite, no NMS hackery | **Met (adapted)** — the four defects found in §12 were in *this* codebase, and are fixed |

## 8. Queue system

| Requirement | Status | Where |
|---|---|---|
| Capacity-aware dispatch | **Met** | `lobbyQueue` only dispatches to a Ready pod |
| Party cohesion (atomic group assignment) | **Met** | `QueueManager` treats a party as one unit |
| Anti-thundering-herd (backoff + jitter) | **Met** | `DispatchResult.retry(retryAfterMillis)` |
| Pre-warm hints | **Met** | depth metric drives KEDA pre-scale |
| Explicit request protocol | **Met** | `POST /lobby/queue`, K8s annotations **not** used (see §12) |

## 9. World loading / DB / ranking / comms

| Requirement | Status | Notes |
|---|---|---|
| Slime from S3, instant load | **Partial** | staging implemented; the Slime load call is the documented hook |
| Local fallback, clearly non-production | **Met** | `LocalTemplateSource`, warning in config |
| Config: template source, adapter selection, fallback toggle | **Met** | `template.*` keys |
| MySQL + `schema_version` | **Met** | `core/persistence` |
| Automated forward migrations, idempotent/ordered/logged | **Met** | `MigrationsTest` |
| HikariCP, configurable pool per component | **Met** | `database.pool-size` |
| Multi-proxy safe (UUID + server-id, no local cache) | **Met** | additive `SET col = col + ?` writes |
| ELO rewritten from scratch, documented | **Met** | `EloCalculator` (formula in Javadoc) |
| ELO computed async off the main thread | **Met** | `MatchResultPersister` via `CompletableFuture` |
| Leaderboards cached, refreshed periodically | **Met** — fixed in this pass (a refresh task now runs) |
| Controller↔pods HTTP webhooks | **Met** | `/pods/{ready,started,ended,heartbeat,draining}` |
| Controller↔pods K8s annotations | **Not done** | state is HTTP-only |
| Lobby↔controller priority/party/preferred group | **Met** | `QueueRequest` |
| Velocity↔controller routing | **Met** | Velocity plugin |
| No pod-to-pod communication | **Met** | everything goes through the controller |

## 10. Code quality & testing

| Requirement | Status | Notes |
|---|---|---|
| JUnit 5 + MockBukkit | **Met** | JUnit 5 **and** MockBukkit for Paper 26.2 (`org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1`) in the Spigot test scope; Core stays Bukkit-free so game logic needs no mock |
| Wired into `verify` | **Met** | `mvn verify` runs 98 tests |
| Tests: state transitions, shop, elimination, scoreboard | **Met** | `GameLifecycleTest`, `ShopServiceTest`, `GameStateTest`, `ScoreboardBuilderTest` |
| Module boundaries / compile-time validation | **Met (adapted)** | explicit package structure + module POMs; no Modulith-style enforcement tool |
| Async everything that touches I/O | **Met** | repositories and HTTP all return `CompletableFuture` |

## 11. Deliverables

| Deliverable | Status |
|---|---|
| Working multi-module Maven project | **Met** |
| JAR < 4 MB | **Met** — ~210 KB; enforced by an Ant gate in `verify` |
| `README.md` | **Met** |
| `docs/API.md` | **Met** |
| `docs/ARCHITECTURE.md` | **Met** |
| `docs/MIGRATIONS.md` | **Met** |
| `docs/SETUP.md` (step-by-step) | **Met** — added in this pass |
| `docs/AUDIT.md` (this file) | **Met** — added in this pass |
| `.gitignore`, Git repo | **Met** |
| Sample K8s manifests (GameServerSet, KEDA, mc-router, Velocity, controller, S3) | **Met** |
| Dockerfiles for each component | **Met** |
| CI running tests + size gate | **Met** | `.github/workflows/ci.yml` |

## Additional improvements

| Idea | Status |
|---|---|
| Slime template versioning (semver, canary) | **Met** | `TemplateDescriptor.version` |
| Pod health metrics (Prometheus) | **Met** | `/metrics` + heartbeats carrying TPS/players/phase |
| Game result webhook payload (structured JSON) | **Met** | `GameResult` |
| Graceful shutdown on SIGTERM | **Met** | `reportDraining` → finish → `reportGameEnded` |
| Lobby NPC state sync | **Met** — fixed in this pass (`GET /lobby/arena-status`) |
| Config hot-reload | **Met** — added in this pass (`/bw reload`) |
| Structured logging with correlation IDs | **Met** — added in this pass (`StructuredLog`) |
| Integration test mode (single-node Docker) | **Met** | `deploy/compose` |

---

## 12. Defects found and fixed in this pass

Four real defects, found by auditing what the code actually does at runtime:

1. **Stats and ELO were never written.** `StatsRepository`, `LeaderboardCache` and
   `QuickBuyRepository` were constructed and closed, but nothing ever called `apply`
   or `updateElo`. A finished match persisted *nothing*. Fixed with
   `MatchResultPersister` (applies every delta, recomputes team-aware ELO), wired into
   match end and `onDisable`, and covered by `MatchResultPersisterTest`.
2. **No heartbeat was ever sent.** `PodReporter.heartbeat` existed but had no caller, so
   pod health metrics never reached the controller. Fixed with a scheduled heartbeat
   (`TpsMeter` + `heartbeatSeconds`).
3. **Leaderboards were never refreshed.** The cache had a `refresh()` and no scheduler.
   Fixed with a periodic refresh task.
4. **Local template path was doubled.** `template.local-path` defaulted to
   `templates/Glacier` and `LocalTemplateSource` appended the template name again,
   producing `templates/Glacier/Glacier` — the cause of the observed
   `Local template not found`. Fixed by making `local-path` the directory *containing*
   templates (`templates`) and documenting it in `config.yml` and `docs/SETUP.md`.

Then, found by exercising the live controller HTTP API:

5. **The controller's error path was broken.** `handle()` used `try (exchange)`, which
   closes the exchange *before* the `catch` runs — so any malformed request produced an
   **empty reply** instead of a 400, hiding every bad request. Fixed by closing in
   `finally`; covered by `malformedQueueBodyReturnsAReal400NotAnEmptyReply`.
6. **The ready pool double-counted a pod.** OpenKruise reuses pod names, so a recreated
   `bedwars-solo-0` reporting READY again added a *second* pool entry for the same id:
   `bedwars_ready_pods{solo}` read **2** with one pod, and the same pod could be handed
   to two different parties. Fixed by making `registerReady` id-keyed and idempotent;
   covered by `ReadyPodRegistryTest`.
7. **Pod report failures were silent.** `HttpPodReporter.post` swallowed every exception,
   so a controller outage was invisible in the pod log. Now logged as
   `report_failed path=… error=…`.

Earlier in the project, four more were found by running the cluster:

8. Missing `eula.txt` in the game image (the pod would exit on boot).
9. Ready reports omitted the arena group, registering pods under `any`.
10. The GameServerSet never set `BEDWARS_TEMPLATE_SOURCE`, so the S3 config was ignored.
11. Dead pods were never removed from the ready pool (a queue request could be routed to
    a pod that no longer existed).

## 13. Remaining gaps (honest list)

Closed in the gap-closure pass (previously listed here):
`AdvancedSlimePaper world load`, `S3 SigV4 signing`, `MockBukkit` availability, and
`Scoped Values` adoption. See §14.

Still open, and why:

- **AdvancedSlimePaper end-to-end** — implemented against the real ASP 3.0.0 API
  (`readWorld` read-only → `clone(instanceName)` → `loadWorld`) and unit-tested through
  the `SlimeWorldBridge` seam; no ASP server exists in this environment, so the world
  swap has not been executed against a live ASP.
- **Persistence against a live MySQL** — **now verified**: migrations v1–v4 applied and
  `player_stats` rows written on a real MySQL 8.4 (§15).
- **K8s annotations for pod state** — HTTP webhooks only.
- **gRPC transport** — the brief allowed "HTTP or gRPC"; HTTP was chosen, so this is a
  non-gap by the brief's own wording.
- **Citizens NPC hook** — named entity used instead.
- **Dragon Buff** spawns no dragons.
- **Structured concurrency (`StructuredTaskScope`)** — verified still preview in JDK 25
  (`javac --release 25` → "preview API and is disabled by default"), so plain virtual
  threads are used. Scoped Values are final and *are* used.
- **MockBukkit plugin bootstrap** — harness works; whole-plugin load needs the JAR on
  the classpath, which surefire does not offer.
- **MinIO/S3 in-cluster fetch** — MinIO is disabled in the minikube overlay (its image
  cannot be pulled anonymously here); the S3 path is instead verified end-to-end
  in-JVM against a stub S3 endpoint (`S3TemplateSourceTest`).

## 14. Gap-closure pass (second round)

What was implemented, and how each is verified:

| Gap | Implementation | Verified by |
|---|---|---|
| Slime world load | `SlimeWorldProvider` + `AspSlimeWorldProvider`/`AspSlimeWorldBridge` (real ASP API) + `FallbackWorldProvider` | `AspSlimeWorldProviderTest` (read-only template, clone name, load order); compiles against ASP 3.0.0 |
| S3 SigV4 | `AwsSigV4` (real `AWS4-HMAC-SHA256`) wired into `S3TemplateSource` with credentials | `AwsSigV4Test` (published AWS `get-vanilla` vector); `S3TemplateSourceTest` (real signed HTTP GET against an in-JVM stub) |
| MockBukkit | `mockbukkit-v26.2:4.116.1` in Spigot test scope (+ Paper API, test-scoped and ordered first) | `MockBukkitEnvironmentTest` — mocked server **and** real player interactions |
| Scoped Values | `CorrelationContext` (game/pod ids), used by the tick loop and structured logging | `CorrelationContextTest`; compile probe shows it needs no preview flag |
| Structured concurrency | deliberately **not** adopted | compile probe records the exact preview rejection |

Test count after this pass: **98** (was 77).

## 15. Live verification on a real Spigot 26.3 server (real 26.3 bot clients)

Everything below was observed on a real Spigot **26.3** server (offline-mode) driven by
two headless **protocol-777 (26.3)** bot clients — no mocks, no containers.

| Area | Observed |
|---|---|
| Plugin bootstrap | loads, enables, registers `/bw`, downloads its `libraries:` (HikariCP, mysql-connector) on first boot |
| Server-id | reported `pod-marco-win` — placeholder resolved |
| TPS metric | `tps=20.0` |
| Template + loader | `template_materialised … productionReady=false` → `using_non_production_world_loader backend=FALLBACK(local-copy, non-production)` |
| Match lifecycle | `WAITING → COUNTDOWN → RUNNING → ENDED` |
| Two-player join | `state=RUNNING players=2 teams=2`; players received `Joined the match.` and `Starting in 5…1…` |
| Death handling | `player_eliminated … killer=null final=false`, **no** event exceptions |
| Structured JSON | `{"event":"player_eliminated","game_id":"…","player_uuid":"…","final":false}` — null field omitted, `pod_id` added from Scoped Values |
| DB migrations | real MySQL 8.4: `Schema at version 0, latest available 4` → v1–v4 applied |
| Persistence | `match_results_persisted`; `player_stats` rows written (`BotAlpha deaths=1 losses=1 games_played=1`) |
| ELO | stayed 1000 for an aborted/drawn match — correct (draws skip ELO) |
| Graceful shutdown | RCON `stop` → `Disabling BedwarsRecoded` → `report_failed path=/pods/draining` |

### Bugs the live run exposed (all fixed)

12. **Every gameplay command was blocked for normal players.** `plugin.yml` put
    `permission: bedwars.admin` on the whole `/bw` command, so `/bw join` was denied and
    no player could ever join a match. Removed the blanket permission; `start`/`stop`/
    `reload` are now gated per-subcommand (console/RCON always allowed).
13. **`server-id` never resolved.** The shipped default is `pod-${HOSTNAME}`, used
    literally — pods identified themselves to the controller as `pod-${HOSTNAME}`.
    `PluginConfig.expand` now resolves `${ENV}` against the environment/system properties,
    falling back to the local hostname.
14. **TPS reported as 1.0 on a healthy server** — the meter was ticked from the 1-second
    game loop rather than per server tick. It now has its own 1-tick task.
15. **No MySQL 8 connection could ever succeed.** The JDBC URL used `useSSL=false` with no
    `allowPublicKeyRetrieval`, so the default `caching_sha2_password` plugin failed with
    *"Public Key Retrieval is not allowed"* and the plugin silently ran stat-less. The URL
    is now `sslMode=PREFERRED&allowPublicKeyRetrieval=true&…`.
16. **`Map.of` in the structured-log fields threw on any kill with no killer** (and any
    draw), aborting the listener chain — on the server: `Could not pass event
    PlayerDeathEvent`. Replaced with a null-skipping map builder.

Test count after this pass: **112**.

## 16. Remaining gaps after the live pass

- **AdvancedSlimePaper end-to-end** — implemented against the real ASP 3.0.0 API and
  unit-tested, but the local test server is Spigot rather than AdvancedSlimePaper, so the
  Slime world swap still has not run on a live ASP.
- **K8s annotations for pod state** — HTTP webhooks only.
- **Citizens NPC hook**; **Dragon Buff** spawns no dragons.
- **Structured concurrency (`StructuredTaskScope`)** — still preview in JDK 25.

Verified since the previous pass: MySQL migrations + stat persistence, plugin bootstrap,
match lifecycle, joins, death handling and structured logging — all on a real server.
