# BedwarsRecoded

A production-grade recode of the Spigot Bedwars plugin
([LB45440078L/Bedwars](https://github.com/LB45440078L/Bedwars), `top.cmarco.bedwars`)
into a **Kubernetes-native, pods-as-cattle** architecture: every match runs in its
own ephemeral pod, and **pod destruction is the reset**.

This is a clean rewrite. The original 17k-LOC single-module plugin is replaced by a
multi-module Maven project with a Bukkit-free domain core, a platform-neutral API,
and a controller that drives scaling.

## Modules

| Module | Responsibility |
|---|---|
| `BedwarsRecoded-API` | Public interfaces, platform-neutral events, DTOs, shared JSON. No Bukkit, no JDBC. |
| `BedwarsRecoded-Core` | Game domain, state machine, managers, ranking, persistence, migrations. **Bukkit-free** so it is unit-testable. |
| `BedwarsRecoded-Spigot` | Paper/Spigot plugin entrypoint. Thin listeners resolve the owning `Game` and delegate. |
| `BedwarsRecoded-Velocity` | Persistent proxy; asks the controller where to route players. |
| `BedwarsRecoded-Controller` | Standalone service: queue watcher, GameServerSet scaler, pod webhook receiver. |

## Implemented features

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

Spigot adapter:
- Shop GUI (`/bw shop`), Upgrade GUI (`/bw upgrades`), live scoreboard sidebar.
- Listeners: bed break, death/respawn, join/quit, void kill, bed protection, spectator
  on elimination, sign join (`[bedwars]`).
- Join via command (`/bw join`) or sign (`[bedwars]`); `/bw status|start|stop`.
- Pod reporting to the controller (ready/started/ended/heartbeat/draining).

## Targets (verified)

- **Spigot API**: `26.2-R0.1-SNAPSHOT` (the plugin's compile target — runs on Spigot and
  Paper). Paper API `26.2.build.129-stable` is retained for reference. Compatibility
  covers 26.1, 26.2, 26.3 (all exist on the respective repos). The plugin deliberately
  uses only Spigot API surface (no Paper-only Adventure), so it loads on plain Spigot.
- **Velocity API**: `4.2.0`.
- **Java**: source/target release **25** (`maven.compiler.release=25`). Built with the
  installed JDK 27 (Temurin), which targets release 25 cleanly.
- **Engine**: Paper, explicitly **not** Folia.

## Build

Builds run under WSL with the project toolchain:

```bash
source ~/.local/tools/env.sh          # JDK 27 + Maven 3.9.16
mvn clean install                     # all modules + tests
mvn -pl BedwarsRecoded-Spigot -am package
```

The `verify` phase enforces the **JAR < 4 MB** budget for the plugin jar via an
Ant size gate in `BedwarsRecoded-Spigot/pom.xml`. Runtime-only dependencies
(HikariCP, MySQL connector) are declared in `plugin.yml` `libraries:` and downloaded
by Paper at startup, so they are never shaded.

## Testing

JUnit 5 + AssertJ. `Core` is deliberately Bukkit-free, so game logic is tested with
plain JUnit — no server, no mocking framework. `verify` runs the suite.

> **MockBukkit note.** MockBukkit has **no release for Paper 26.x** (latest is
> `MockBukkit-v1.21:3.133.2`, MC 1.21). Rather than pin an incompatible artifact,
> the domain was kept free of Bukkit so it is fully testable without a mocking
> framework; the thin Spigot adapter is verified by compilation against the real
> Paper API. See `docs/ARCHITECTURE.md` for the boundary rationale.

## Running locally

```bash
# Controller (queue watcher + webhooks)
java -jar BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-*.jar

# A game pod is a Paper server with the plugin installed; in production it is
# created on demand by the GameServerSet. See deploy/k8s.
```

## Deployment

`deploy/k8s` contains the full manifest set (apply with `kubectl apply -k deploy/k8s`
or Kustomize): namespace, GameServerSet, KEDA `ScaledObject` + Karpenter `NodePool`,
mc-router, Velocity, controller (+ RBAC), S3 config, MySQL. `deploy/docker` contains
Dockerfiles for each component.

## Documentation

- `docs/ARCHITECTURE.md` — pod lifecycle, controller protocol, scaling model.
- `docs/API.md` — public API reference (DTOs, events, services).
- `docs/MIGRATIONS.md` — how to add database migrations.

## Hard constraints honoured

1. No per-game arenas on disk — templates live in S3 as Slime files.
2. No block-by-block rollback — pod destruction is the reset.
3. Not Folia.
4. No player stats inside a pod — all persistence goes to MySQL.
5. Every pod has strict resource requests and limits (`deploy/k8s/10-gameserverset.yaml`).
6. A game pod never outlives its game.
7. No pod is special or long-lived.

## Repository layout

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

The WSL copy at `~/projects/BedwarsRecoded` is canonical and the only place builds
run; it is mirrored to `C:\Users\thevi\projects\BedwarsRecoded` (Windows).