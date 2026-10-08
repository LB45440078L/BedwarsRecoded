# 🏰 BedwarsRecoded

<p align="center">
  <b>Kubernetes-native Bedwars — a lobby matchmakes, dedicated servers host many concurrent matches, and servers are provisioned on demand and reclaimed when idle.</b>
</p>

<p align="center">
  <a href="https://github.com/LB45440078L/BedwarsRecoded/actions/workflows/ci.yml"><img src="https://github.com/LB45440078L/BedwarsRecoded/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&amp;logoColor=white" alt="Java 25"></a>
  <a href="https://www.spigotmc.org/"><img src="https://img.shields.io/badge/Spigot%20API-26.3-yellow" alt="Spigot API 26.3"></a>
  <a href="https://velocitypowered.com/"><img src="https://img.shields.io/badge/Velocity-4.2.0-00B4D8" alt="Velocity 4.2.0"></a>
  <a href="https://kubernetes.io/"><img src="https://img.shields.io/badge/Kubernetes-native-326CE5?logo=kubernetes&amp;logoColor=white" alt="Kubernetes"></a>
  <a href="https://helm.sh/"><img src="https://img.shields.io/badge/Helm-chart-0F1689?logo=helm&amp;logoColor=white" alt="Helm chart"></a>
  <a href="https://www.docker.com/"><img src="https://img.shields.io/badge/Docker-images-2496ED?logo=docker&amp;logoColor=white" alt="Docker images"></a>
  <a href="#-testing"><img src="https://img.shields.io/badge/tests-197%20passing-brightgreen" alt="Tests"></a>
  <a href="#-build"><img src="https://img.shields.io/badge/plugin%20JAR-%3C%204%20MB-brightgreen" alt="Plugin JAR under 4 MB"></a>
  <a href="https://github.com/LB45440078L/BedwarsRecoded/issues"><img src="https://img.shields.io/badge/PRs-welcome-blueviolet" alt="PRs welcome"></a>
</p>

A production-grade recode of the Spigot Bedwars plugin
([LB45440078L/Bedwars](https://github.com/LB45440078L/Bedwars), `top.cmarco.bedwars`)
into a **Kubernetes-native** architecture: a **lobby** matchmakes, **dedicated
servers host many concurrent matches each** (`arena.games-per-server`), and further
servers are **provisioned on demand and reclaimed when idle**. One match per server
(the Kubernetes default) is still a supported configuration — with
`games-per-server: 1` the plugin keeps its original single-match behaviour.

This is a clean rewrite. The original 17k-LOC single-module plugin is replaced by a
multi-module Maven project with a Bukkit-free domain core, a platform-neutral API,
and a controller that drives scaling.

## 🧩 Modules

| Module | Responsibility |
|---|---|
| `BedwarsRecoded-API` | Public interfaces, platform-neutral events, DTOs, shared JSON. No Bukkit, no JDBC. |
| `BedwarsRecoded-Core` | Game domain, state machine, managers, ranking, persistence, migrations. **Bukkit-free** so it is unit-testable. |
| `BedwarsRecoded-Spigot` | Spigot plugin entrypoint (**Spigot API only**). Thin listeners resolve the owning `Game` and delegate. |
| `BedwarsRecoded-Velocity` | Persistent proxy; asks the controller where to route players. |
| `BedwarsRecoded-Controller` | Standalone service: queue watcher, **replaceable `ServerProvisioner` (Kubernetes *or* Docker)**, idle scale-down, pod webhook receiver. |

## ✨ Implemented features

Gameplay (Core, unit-tested, Bukkit-free):
- Match state machine (WAITING → COUNTDOWN → RUNNING → SUDDEN_DEATH → ENDED) with
  validated transitions.
- Teams with auto-balancing, beds, bed-protection radius, island radius, void-Y kill.
- Generators with tiers and a deterministic tick; Iron Forge upgrades retier a team's
  generators.
- Shop system: categories, prices in four currencies, permanent items, downgradable
  tiered items with refund, quick-buy store.
- Team upgrades (Sharpness, Protection, Maniac Miner, Iron Forge, Heal Pool, Dragon
  Buff) and traps (It's a Trap!, Counter-Offensive, Alarm, Miner Fatigue, Blindness &
  Poison) with scaling trap cost and FIFO trigger order.
- Per-player language (`/bw lang`) with a message catalog and English fallback.
- Scoreboard content builder.
- Arena definitions loaded from YAML (SnakeYAML) — teams, beds, spawns, generators, shop.
- ELO ranking, MySQL persistence with versioned migrations, cached leaderboards.
- Upgrade effects: team upgrades map to concrete effects (Sharpness/Protection
  enchants, Haste, Regeneration) via `TeamEffectCalculator`.
- Trap triggering: `TrapTriggerService` fires a team's next armed trap when an enemy
  enters its base, with cooldown; each trap's effects are described by `TrapEffect`.
- Quick-buy persistence (`QuickBuyRepository` + `quick_buy` table) for cross-pod sync.
- Start items per arena group.
- **Multi-match hosting** (`GameHost` + `GameFactory`) — one dedicated server runs up to
  `arena.games-per-server` concurrent matches. Joiners fill the emptiest match before a
  new one is created, matches are fully independent, each may run in its own world
  (`GameWorldService`), and the server reports its free **match slots** to the controller.
- **Match persistence** (`MatchResultPersister`): on match end every player's additive
  stat delta is written and team-aware ELO is recalculated — this is the code path that
  actually writes to MySQL (previously the repositories were constructed but never called).
- **Per-arena-group upgrade trees** parsed from the `arena.yml` `upgrades:` block;
  falls back to `UpgradeCatalog.defaults()` when absent.
- **Structured JSON logging** (`StructuredLog`) carrying `game_id`/`player_uuid`
  correlation fields, enabled by `logging.json`.
- **Correlation context** (`CorrelationContext`) built on **Scoped Values** — finalised
  in JDK 25 (JEP 506), so no preview flag. The tick wraps every match operation in the
  game/pod ids, and structured logs pick them up automatically.
- **Slime world loading** — `AspSlimeWorldProvider` reads the template through the
  AdvancedSlimePaper API **read-only** and clones it to a per-match instance, so a pod
  can never mutate the shared template. Without ASP the documented
  `FallbackWorldProvider` (local copy, non-production) is used.
- **S3 SigV4 signing** (`AwsSigV4`) — real `AWS4-HMAC-SHA256` request signing, so
  templates fetch from AWS S3, MinIO or Ceph rather than only public buckets.
- **Sudden-death dragons** (`DragonService`) — when a match enters `SUDDEN_DEATH` the
  server spawns dragons: one neutral dragon per `dragon.base-per-match`, plus one per
  Dragon Buff level for every surviving team (eliminated teams bring none). A dragon
  hunts the nearest enemy player, hurls players away with a configurable high knockback,
  and tears the map apart beneath itself as it flies (bounded radius and depth; never
  beds, bedrock, obsidian, end stone or barriers). Configured from the top-level
  `dragon:` block and hot-reloadable.

Spigot adapter:
- Shop GUI (`/bw shop`, shift-click toggles quick buy), Quick-buy GUI (`/bw quickbuy`),
  Upgrade GUI (`/bw upgrades`), Join GUI (`/bw gui`), live scoreboard sidebar.
- Live effects: `UpgradeEffectApplier` enchants gear and applies Haste/Regen;
  `TrapApplier` applies blind/slow/poison/mining-fatigue to intruders and buffs to
  defenders; `TrapTriggerListener` fires traps on base entry.
- Listeners: bed break, death/respawn, join/quit, void kill, bed protection, spectator
  on elimination, sign join, NPC join (named entity `[bedwars]`), quick-buy sync.
- Join via command (`/bw join`), sign, GUI, or NPC; `/bw status|start|stop|shop|
  quickbuy|upgrades|gui|lang|reload`.
- Pod reporting to the controller (ready/started/ended/heartbeat/draining). A periodic
  heartbeat carries **TPS, player count, phase and uptime** (`TpsMeter`).
- Periodic async **leaderboard refresh** (no per-request DB read).
- **Config hot-reload** (`/bw reload`, `bedwars.admin`): re-reads `config.yml` +
  `arena.yml` and re-applies shop, upgrade tree, start items and countdown mid-match.

## ⚠️ Known gaps

Honest list of what is modelled but not fully wired, or absent:

- **AdvancedSlimePaper end-to-end** — the loader is implemented against the real ASP
  3.0.0 API and unit-tested (read-only template, per-match clone, load), but the local
  test server is Spigot rather than AdvancedSlimePaper, so the world swap has not run
  on a live ASP.
- **gRPC** — controller communication is HTTP only (the brief allowed HTTP webhooks).
- **K8s annotations for pod state** — state is reported over HTTP webhooks only.
- **NPC via Citizens** — join NPCs use a named entity, not a Citizens hook.
- **The API token travels as an environment variable** — the controller enforces
  `BEDWARS_API_TOKEN` on every mutating endpoint (constant-time, never logged), and every
  shipped client now presents it: the game server's reporter, the proxy's controller client,
  and the Docker provisioner, which passes it to each container it starts. Nothing is left
  unauthenticated except the read-only endpoints, deliberately. The residual risk is
  distribution, not implementation: a token in a pod spec or `docker inspect` is readable by
  anyone who can read those. See `docs/MANUAL.md` §21.2 and §21.3.
- **Structured concurrency (`StructuredTaskScope`)** — still a preview API in JDK 25
  (verified: `javac --release 25` rejects it without `--enable-preview`), so plain
  virtual threads are used. Scoped Values *are* used, since those are final.

Formerly listed here and now **verified on a real Spigot 26.3 server with real 26.3
bot clients**: plugin bootstrap, match lifecycle, player joins, death handling,
structured logging, MySQL 8 migrations and stat persistence (see `docs/AUDIT.md` §15).

This revision was additionally verified live with the project's own offline-mode
**Python bot clients** (`Documents/MinecraftPythonBot/mcbot`): two bots joined a running
server, drove a match through `COUNTDOWN → RUNNING → SUDDEN_DEATH`, and the dragons
spawned, destroyed the map and knocked a bot into the air — proven by the server-side
lines `dragons_spawned`, `dragon_map_damage` (147 blocks) and `dragon_knockback`, and by
the bot's own chat and position feed. The controller was also deployed to a live
**minikube** cluster (OpenKruise `GameServerSet` CRD installed) and answered `/healthz`,
`/metrics` and `/infra` with the Kubernetes provisioner active; the Docker provisioner is
exercised by a real-daemon integration test. The game-pod Docker image now builds Spigot
itself with the official **BuildTools** (no prebuilt Spigot jar exists), verified by
building the image on minikube.

Verified on a real cluster: the Compose stack and Helm chart are wired and the
controller API was exercised end-to-end on minikube (see `docs/DEPLOYMENT.md`). The
K8s manifests were schema-validated with `kubeconform` and rendered with `helm` +
`kustomize`. The Compose stack was also run for real: the controller came up
`healthy` and, on a `POST /lobby/queue`, provisioned an actual game container from the
Spigot image, which joined the Compose network, resolved the controller and registered
(`registeredServers: 1`).

## 🎯 Targets (verified)

- **Spigot API**: compiled against `26.3-R0.1-SNAPSHOT`, with `api-version: '26.1'` in
  `plugin.yml` so the same jar loads on every Spigot 26.x server. **No Paper API is on
  any classpath** (main, test or runtime) — `SpigotOnlyApiTest` fails the build if a
  Paper/Folia/Adventure import or a `paper-api` dependency reappears.
- **Velocity API**: `4.2.0`.
- **Java**: source/target release **25** (`maven.compiler.release=25`), built and
  tested on JDK 25.
- **Engine**: Spigot. Explicitly **not** Folia and **not** Paper-only.

Verified load: the packaged jar runs on a real Spigot 26.3 dedicated server inside the
game-server container; it stages the arena, enables, registers with the controller and
reports capacity with no stack traces (a missing database or template degrades gracefully
to a one-line warning).

## 🔨 Build

Builds are plain Maven. JDK 25 or newer is required (`maven.compiler.release=25`):

```bash
mvn clean install                          # all modules + tests
mvn -pl BedwarsRecoded-Spigot -am package  # just the plugin jar
```

The `verify` phase enforces the **JAR < 4 MB** budget for the plugin jar via an
Ant size gate in `BedwarsRecoded-Spigot/pom.xml`. Runtime-only dependencies
(HikariCP, MySQL connector) are declared in `plugin.yml` `libraries:` and downloaded
by the server at startup, so they are never shaded.

## 🧪 Testing

JUnit 5 + AssertJ. `Core` is deliberately Bukkit-free, so game logic is tested with
plain JUnit — no server, no mocking framework. The suite is **197 tests**:

- **Core (95)** — game lifecycle, and the win condition in particular
  (`WinConditionTest`): a match must reach a single survivor even when the arena
  declares more teams than were filled, and when a team is abandoned by disconnects.
  `GameHostTest` covers many matches on one server: players fill a match before a new
  one is created, the configured `games-per-server` is never exceeded, created matches
  consume slots, and finished matches are pruned and unregistered.
  `SuddenDeathDragonTest` covers the dragon plan: one per Dragon Buff level, eliminated
  teams bring none, and the neutral count is honoured.
- **Spigot (35)** — config (including the `dragon:` block and its clamps), the dragon's
  block rules (`DragonRulesTest`: air/indestructible blocks/beds are never broken),
  reporting (`HttpPodReporterTest` reads the `X-Bedwars-Token` header off a real HTTP
  server, and asserts no header is sent when no token is configured), template sources, and
  `SpigotOnlyApiTest`, which enforces the Spigot-only constraint.
- **Controller (62)** — queue dispatch over real HTTP (`WebhookServerTest`), token
  authentication (`WebhookServerAuthTest`), the match-slot capacity model
  (`ServerRegistryTest`), `DockerProvisionerTest` (fake runner: asserts the provisioned
  container is handed the token when one is set, and not handed an empty one when it is
  not), `PrewarmGuardTest`
  (a booting server must not be re-pre-warmed), `ScaleDownPolicyTest`,
  and an opt-in `DockerProvisionerIntegrationTest` that drives a
  **real** Docker daemon: it provisions an actual container, health-checks it, reclaims
  it, and asserts nothing is left behind (self-skips when no daemon is present).

`verify` runs the suite. There is no Paper API and no MockBukkit anywhere on the
classpath.

## 🚀 Running it

The fastest route from a fresh checkout to a running network is the guided installer:

```bash
./install.sh              # guided: Docker or Kubernetes, every parameter prompted
./install.sh --dry-run    # print every action, change nothing
./install.sh --teardown   # undo exactly what it created
```

It probes the machine and tells you which path it is ready for, asks every parameter (the
repository's own defaults are one Enter away), copies your world files and server jar into
place, streams the real image-build output with elapsed time, waits for readiness, and then
**proves** the result: controller health, the lobby logging `role=LOBBY`, the proxy resolving
its lobby server, and no hub masquerading as a match host in the registry. `--yes` accepts every
default for an unattended run; `--mode kubernetes` skips the mode question. Walkthrough:
[`docs/MANUAL.md`](docs/MANUAL.md) §13.8.

There is one shape: a **game server managed by the controller**. It runs as a container,
started when players queue and reclaimed when its match ends. Docker and Kubernetes are
both supported through the same `ServerProvisioner` seam; there is no unmanaged
"run the jar on a host" mode.

The game-server image's engine is a build argument, and the image only compiles anything
when it has no other option — see [`server-jars/README.md`](server-jars/README.md):

| Source | Cost |
|---|---|
| a jar you supply in `server-jars/` | copied straight in, nothing compiled |
| `SERVER_ENGINE=paper` | downloaded from PaperMC, nothing compiled |
| `SERVER_ENGINE=spigot` (default), no jar supplied | compiled with BuildTools — minutes |

```bash
# A. the whole stack on one machine: MySQL, MinIO, controller, one game server
cd deploy/compose && docker compose up -d
docker compose --profile game up -d --build game-pod

# B. Kubernetes
helm install bedwars deploy/helm/bedwars -f deploy/helm/values-minikube.yaml -n bedwars --create-namespace
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=1
deploy/tools/join-server.sh          # then connect to localhost:25565
```

At boot the plugin logs where it reports and to which controller:

```
bedwars_setup controller_reporting=enabled controller_url=http://bedwars-controller:8080
bedwars_setup server_id=pod-local-1 arena_group=solo teams=2x2 games_per_server=3 template=Glacier@1.0.0(LOCAL) persistence=mysql
```

Settings that used to be split across a `deployment.mode` switch now live where they
belong: reporting knobs (and `heartbeat-seconds`) under `controller:`, and the
`server.force-whitelist-off` policy next to the server block.

## ☸️ Deployment

`deploy/k8s` contains the full manifest set (apply with `kubectl apply -k deploy/k8s`
or Kustomize): namespace, GameServerSet, KEDA `ScaledObject` + Karpenter `NodePool`,
mc-router, Velocity, controller (+ RBAC), S3 config, MySQL. `deploy/docker` contains
Dockerfiles for each component.

## 📚 Documentation

- `docs/MANUAL.md` — **the complete manual** (~25,000 words). Every technology explained
  from first principles, requirements, command-by-command setup for both paths, and a
  reference for **every parameter** in every config file. Start here.
- `docs/SETUP.md` — **step-by-step setup**, from a bare machine to a running match, with
  a **Minimum Requirements** section. Two paths (Docker Compose / Kubernetes), every step
  explained.
- `docs/CONCEPTS.md` — what containers, Kubernetes and pods are, and **what each
  component of this stack is responsible for** (and what it must never do).
- `docs/AUDIT.md` — requirement-by-requirement audit against the original brief.
- `docs/ARCHITECTURE.md` — pod lifecycle, controller protocol, scaling model.
- `docs/PROVISIONING.md` — the `ServerProvisioner` seam: Kubernetes vs Docker,
  capacity (`games-per-server`), idle scale-down, and controller authentication.
- `docs/AUDIT-RECODED.md` — the audit that drove this revision: defects found,
  decisions taken, and what remains.
- `docs/DEPLOYMENT.md` — Compose stack, Kubernetes platform, verification.
- `docs/API.md` — public API reference (DTOs, events, services, HTTP endpoints).
- `docs/MIGRATIONS.md` — how to add database migrations.
- `deploy/tools/README.md` — the RCON, status and port-forward helpers.

New here? Read `docs/MANUAL.md` (everything in one file), or `docs/CONCEPTS.md` first and
then `docs/SETUP.md` if you prefer the short path.

## ✅ Verifying everything

```bash
./deploy/verify.sh
```

Runs `mvn verify` (unit + real HTTP integration tests for the controller), the
deployment-asset verifier (`deploy/verify_deploy.py` — parses every manifest and the
compose file, 119 structural checks), and the 4 MB JAR gate.

`deploy/verify_k8s.sh` additionally lints and renders the Helm chart, renders the
Kustomize base, and schema-validates both with `kubeconform` (set `HELM`, `KUBECTL`,
`KUBECONFORM` if they are not on `PATH`). It then runs
`deploy/tools/check_env_unique.py`, which fails if any rendered container declares the same
environment variable twice — valid YAML, a valid schema, and silently last-wins in
Kubernetes. `deploy/tools/test-resolve-server-jar.sh`
proves the image compiles Spigot only when it has no other option, and
`deploy/tools/test-install-interactive.py` drives `install.sh` through a pty to prove the
guided wizard honours, validates and re-asks for its answers (dry-run: no Docker needed).

## 🔒 Hard constraints honoured

1. No per-game arenas on disk — templates live in S3 as Slime files (or a local copy for
   development); a match's world is a throwaway clone.
2. No block-by-block rollback — a match's world is discarded and re-cloned.
3. Not Folia.
4. No player stats inside a server — all persistence goes to MySQL.
5. Every provisioned server has strict resource requests and limits (`deploy/k8s/10-gameserverset.yaml`).
6. A provisioned server is reclaimed only when it holds no match and no players.
7. No server is special; the configured minimum pool size is the only long-lived guarantee.

## 🗂️ Repository layout

```
install.sh                  guided installer: Docker or Kubernetes, end to end
BedwarsRecoded-API/         public contracts
BedwarsRecoded-Core/        domain + persistence
BedwarsRecoded-Spigot/      Spigot plugin
BedwarsRecoded-Velocity/    proxy plugin
BedwarsRecoded-Controller/  k8s controller service
deploy/k8s/                 manifests
deploy/docker/              Dockerfiles
docs/                       architecture, API, migrations
```

Built with JDK 25 (source/target release 25) and Maven 3.9+. Every module builds and
its tests run with a single `mvn verify`.