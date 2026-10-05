# 🏰 BedwarsRecoded

<p align="center">
  <b>Kubernetes-native Bedwars — every match runs in its own ephemeral pod, and pod destruction is the reset.</b>
</p>

<p align="center">
  <a href="https://github.com/LB45440078L/BedwarsRecoded/actions/workflows/ci.yml"><img src="https://github.com/LB45440078L/BedwarsRecoded/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&amp;logoColor=white" alt="Java 25"></a>
  <a href="https://www.spigotmc.org/"><img src="https://img.shields.io/badge/Spigot%20API-26.2-yellow" alt="Spigot API 26.2"></a>
  <a href="https://papermc.io/"><img src="https://img.shields.io/badge/Paper-26.3-blue" alt="Paper 26.3"></a>
  <a href="https://velocitypowered.com/"><img src="https://img.shields.io/badge/Velocity-4.2.0-00B4D8" alt="Velocity 4.2.0"></a>
  <a href="https://kubernetes.io/"><img src="https://img.shields.io/badge/Kubernetes-native-326CE5?logo=kubernetes&amp;logoColor=white" alt="Kubernetes"></a>
  <a href="https://helm.sh/"><img src="https://img.shields.io/badge/Helm-chart-0F1689?logo=helm&amp;logoColor=white" alt="Helm chart"></a>
  <a href="https://www.docker.com/"><img src="https://img.shields.io/badge/Docker-images-2496ED?logo=docker&amp;logoColor=white" alt="Docker images"></a>
  <a href="#-testing"><img src="https://img.shields.io/badge/tests-135%20passing-brightgreen" alt="Tests"></a>
  <a href="#-build"><img src="https://img.shields.io/badge/plugin%20JAR-%3C%204%20MB-brightgreen" alt="Plugin JAR under 4 MB"></a>
  <a href="https://github.com/LB45440078L/BedwarsRecoded/issues"><img src="https://img.shields.io/badge/PRs-welcome-blueviolet" alt="PRs welcome"></a>
</p>

A production-grade recode of the Spigot Bedwars plugin
([LB45440078L/Bedwars](https://github.com/LB45440078L/Bedwars), `top.cmarco.bedwars`)
into a **Kubernetes-native, pods-as-cattle** architecture: every match runs in its
own ephemeral pod, and **pod destruction is the reset**.

This is a clean rewrite. The original 17k-LOC single-module plugin is replaced by a
multi-module Maven project with a Bukkit-free domain core, a platform-neutral API,
and a controller that drives scaling.

## 🧩 Modules

| Module | Responsibility |
|---|---|
| `BedwarsRecoded-API` | Public interfaces, platform-neutral events, DTOs, shared JSON. No Bukkit, no JDBC. |
| `BedwarsRecoded-Core` | Game domain, state machine, managers, ranking, persistence, migrations. **Bukkit-free** so it is unit-testable. |
| `BedwarsRecoded-Spigot` | Paper/Spigot plugin entrypoint. Thin listeners resolve the owning `Game` and delegate. |
| `BedwarsRecoded-Velocity` | Persistent proxy; asks the controller where to route players. |
| `BedwarsRecoded-Controller` | Standalone service: queue watcher, GameServerSet scaler, pod webhook receiver. |

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
- **Dragon Buff** — purchased and stored; no dragons are actually spawned.
- **gRPC** — controller communication is HTTP only (the brief allowed HTTP webhooks).
- **K8s annotations for pod state** — state is reported over HTTP webhooks only.
- **NPC via Citizens** — join NPCs use a named entity, not a Citizens hook.
- **Structured concurrency (`StructuredTaskScope`)** — still a preview API in JDK 25
  (verified: `javac --release 25` rejects it without `--enable-preview`), so plain
  virtual threads are used. Scoped Values *are* used, since those are final.

Formerly listed here and now **verified on a real Spigot 26.3 server with real 26.3
bot clients**: plugin bootstrap, match lifecycle, player joins, death handling,
structured logging, MySQL 8 migrations and stat persistence (see `docs/AUDIT.md` §15).

Verified on a real cluster: the Compose stack and Helm chart are wired and the
controller API was exercised end-to-end on minikube (see `docs/DEPLOYMENT.md`). The
K8s manifests were schema-validated with `kubeconform` and rendered with `helm` +
`kustomize`. Docker Compose itself was not run here (no Compose invocation in this
sandbox); its assets are validated structurally.

## 🎯 Targets (verified)

- **Spigot API**: `26.2-R0.1-SNAPSHOT` (the plugin's compile target — runs on Spigot and
  Paper). Paper API `26.2.build.129-stable` is retained for reference. Compatibility
  covers 26.1, 26.2, 26.3 (all exist on the respective repos). The plugin deliberately
  uses only Spigot API surface (no Paper-only Adventure), so it loads on plain Spigot.
- **Velocity API**: `4.2.0`.
- **Java**: source/target release **25** (`maven.compiler.release=25`). Built with the
  installed JDK 27 (Temurin), which targets release 25 cleanly.
- **Engine**: Paper, explicitly **not** Folia.

## 🔨 Build

Builds are plain Maven. JDK 25 or newer is required (`maven.compiler.release=25`):

```bash
mvn clean install                          # all modules + tests
mvn -pl BedwarsRecoded-Spigot -am package  # just the plugin jar
```

The `verify` phase enforces the **JAR < 4 MB** budget for the plugin jar via an
Ant size gate in `BedwarsRecoded-Spigot/pom.xml`. Runtime-only dependencies
(HikariCP, MySQL connector) are declared in `plugin.yml` `libraries:` and downloaded
by Paper at startup, so they are never shaded.

## 🧪 Testing

JUnit 5 + AssertJ. `Core` is deliberately Bukkit-free, so game logic is tested with
plain JUnit — no server, no mocking framework. The Spigot adapter additionally has
**MockBukkit for Paper 26.2** (`org.mockbukkit.mockbukkit:mockbukkit-v26.2`) for
tests that need real Bukkit objects. `verify` runs the suite.

> **MockBukkit note.** MockBukkit **does** have a release for our target: the project
> moved from `com.github.seeseemelk` to `org.mockbukkit.mockbukkit`, and
> `mockbukkit-v26.2:4.116.1` is on Maven Central. It is wired into the Spigot module's
> test scope. It needs the Paper API on the test classpath (it uses Paper's
> `NamespacedKey.value()`), so `paper-api` is declared **test-scoped and first** in the
> module POM — main code still compiles against Spigot alone. Loading the *whole plugin*
> in-process is not possible under surefire (MockBukkit's classloader wants the plugin
> JAR, and surefire runs `target/classes`), so plugin-level coverage comes from the
> packaged JAR and the cluster runs.

## 🚀 Running locally

The plugin adapts to where it is running. The first decision is `deployment.mode` in
`config.yml`:

| Mode | Behaviour |
|---|---|
| `POD` | Kubernetes game pod: reports to the controller, pulls a Slime template, writes stats. |
| `STANDALONE` | An ordinary server. **No controller requests at all**, no pod lifecycle. |
| `AUTO` (default) | Probes `<controller.base-url>/healthz` at boot and picks one. |

So the same jar runs both as an ephemeral pod and on a plain dev server without
config edits. At boot the plugin logs exactly what it decided:

```
bedwars_setup mode=STANDALONE (configured AUTO) controller_reporting=not used (not a pod) ...
bedwars_setup server_id=pod-mybox arena_group=solo teams=2x2 template=Glacier@1.0.0(LOCAL) persistence=disabled
```

Running it:

```bash
# A. plain server (any OS) - simplest way to test gameplay
java -Xms1G -Xmx2G -jar paper.jar --nogui

# B. the whole stack on one machine
cd deploy/compose && docker compose up -d

# C. Kubernetes
helm install bedwars deploy/helm/bedwars -f deploy/helm/values-minikube.yaml -n bedwars --create-namespace
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=1
deploy/tools/join-server.sh          # then connect to localhost:25565
```

## ☸️ Deployment

`deploy/k8s` contains the full manifest set (apply with `kubectl apply -k deploy/k8s`
or Kustomize): namespace, GameServerSet, KEDA `ScaledObject` + Karpenter `NodePool`,
mc-router, Velocity, controller (+ RBAC), S3 config, MySQL. `deploy/docker` contains
Dockerfiles for each component.

## 📚 Documentation

- `docs/SETUP.md` — **step-by-step setup**, from a bare machine to a running match.
  Three paths (plain server / Docker Compose / Kubernetes), every step explained.
- `docs/CONCEPTS.md` — what containers, Kubernetes and pods are, and **what each
  component of this stack is responsible for** (and what it must never do).
- `docs/AUDIT.md` — requirement-by-requirement audit against the original brief.
- `docs/ARCHITECTURE.md` — pod lifecycle, controller protocol, scaling model.
- `docs/DEPLOYMENT.md` — Compose stack, Kubernetes platform, verification.
- `docs/API.md` — public API reference (DTOs, events, services, HTTP endpoints).
- `docs/MIGRATIONS.md` — how to add database migrations.
- `deploy/tools/README.md` — the tooling, and which parts are Windows-only.

New here? Read `docs/CONCEPTS.md` first, then `docs/SETUP.md`.

## ✅ Verifying everything

```bash
./deploy/verify.sh
```

Runs `mvn verify` (unit + real HTTP integration tests for the controller), the
deployment-asset verifier (`deploy/verify_deploy.py` — parses every manifest and
the compose file, 70 structural checks), and the 4 MB JAR gate.

`deploy/verify_k8s.sh` additionally lints and renders the Helm chart, renders the
Kustomize base, and schema-validates both with `kubeconform` (set `HELM`, `KUBECTL`,
`KUBECONFORM`; on Windows-hosted WSL use `deploy/tools/winrun.sh`).

## 🔒 Hard constraints honoured

1. No per-game arenas on disk — templates live in S3 as Slime files.
2. No block-by-block rollback — pod destruction is the reset.
3. Not Folia.
4. No player stats inside a pod — all persistence goes to MySQL.
5. Every pod has strict resource requests and limits (`deploy/k8s/10-gameserverset.yaml`).
6. A game pod never outlives its game.
7. No pod is special or long-lived.

## 🗂️ Repository layout

```
BedwarsRecoded-API/         public contracts
BedwarsRecoded-Core/        domain + persistence
BedwarsRecoded-Spigot/      Paper plugin
BedwarsRecoded-Velocity/    proxy plugin
BedwarsRecoded-Controller/  k8s controller service
deploy/k8s/                 manifests
deploy/docker/              Dockerfiles
docs/                       architecture, API, migrations
```

Built with JDK 25 (source/target release 25) and Maven 3.9+. Every module builds and
its tests run with a single `mvn verify`.