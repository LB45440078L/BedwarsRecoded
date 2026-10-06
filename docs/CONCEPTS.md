# Concepts — containers, Kubernetes, pods, and the rest of the stack

This document explains **what each piece is and why it is there**. Read it before
`SETUP.md` if words like *pod*, *container*, *GameServerSet* or *mc-router* are new,
or if you just want to know which part does what.

The whole project exists to answer one question: *how do you run a Bedwars match that
can start in seconds, never interferes with another match, and costs nothing when
nobody is playing?*

---

## 1. Containers, in one page

A **container** is a process (or a few) running on a Linux host, isolated from the rest
of that host. It has its own filesystem, its own network namespace, its own process
tree — but it shares the host's kernel, so it starts in milliseconds rather than the
minutes a virtual machine needs.

- A **container image** is the packaged filesystem + metadata (which command to run,
  which ports to expose). `deploy/docker/*.Dockerfile` are the recipes that build ours.
- A **container** is a running instance of an image.
- **Docker** is the most common tool for building and running them; **containerd** is
  the lower-level runtime that Kubernetes uses.

Why we use them: every component here — the proxy, the controller, a game server — is
shipped as an image, so it runs the same way on a laptop and on a cluster, and a game
server can be created and thrown away cheaply.

> **Our containers** (`deploy/docker/`):
> `controller.Dockerfile`, `velocity.Dockerfile`, `spigot.Dockerfile`.

---

## 2. Kubernetes, in one page

**Kubernetes** ("K8s") is a system that runs containers across a pool of machines and
keeps them in the state you declared. You describe *what you want* in YAML; Kubernetes
works out *how* to keep it true, and repairs it when reality drifts.

The objects you will actually see in this project:

| Object | What it is | Where |
|---|---|---|
| **Pod** | The smallest unit K8s schedules: one or more containers that share an address. **This is the unit we throw away per match.** | created by the GameServerSet |
| **Deployment** | "Keep N copies of this pod running, forever." Used for the long-lived pieces. | `40-controller.yaml`, `30-velocity.yaml`, `20-mc-router.yaml` |
| **StatefulSet** | Like a Deployment but with a stable identity and its own storage. Used for the database. | `51-mysql.yaml` |
| **Service** | A stable DNS name + virtual IP in front of pods that come and go. This is how "`mysql`" resolves inside the cluster. | inside the manifests above |
| **ConfigMap / Secret** | Configuration and credentials handed to pods as files or environment variables. | `50-s3-config.yaml` |
| **GameServerSet** | A pod set purpose-built for game servers, with a game-aware lifecycle (`Ready → Allocated → Destroyed`). Supplied by **OpenKruise**. | `10-gameserverset.yaml` |
| **ScaledObject** | A rule that changes the replica count based on a metric. Supplied by **KEDA**. | `11-keda-scaledobject.yaml` |
| **NodePool** | A rule that adds/removes *machines*. Supplied by **Karpenter**. | in the Helm chart |
| **Namespace** | A folder for cluster objects. Ours is `bedwars`. | `00-namespace.yaml` |

Two terms used throughout the docs:

- **Scale to zero** — the normal state of a game pod set is *zero replicas*. No players
  means no pods, which means no CPU and no memory being spent.
- **Pods as cattle, not pets** — pods are anonymous and disposable. You never log in to
  fix one; a broken pod is deleted and replaced. This project takes it one step further:
  the pod is deleted *as its normal end of life*, and that deletion is how the world
  gets reset for the next match.

---

## 3. Why this shape, for Bedwars specifically

The classic way to run a minigame server is one machine hosting many arenas, with each
arena resetting itself in place: restore the blocks you broke, clear the entities,
reset the scoreboard. That reset is where minigame plugins historically get their worst
bugs — a chest half-refilled, a block that reappears in the wrong place, a player who
carries state from the last round into the next.

This project throws that problem away instead of solving it:

1. **One match = one pod.** The arena is never "reset" — the whole server is destroyed.
   There is no block-by-block rollback code anywhere in this repository, because there
   is nothing to roll back.
2. **The map is a file, not a directory.** The world lives as a **Slime** template in
   object storage (S3-compatible). A pod downloads it and loads it instantly instead of
   generating or copying a world.
3. **Nothing important lives inside a pod.** Stats, leaderboards and shop layouts go to
   a shared MySQL, so destroying the pod destroys nothing that matters.
4. **Nothing scales by hand.** Player demand raises the replica count (KEDA), replicas
   raise the machine demand (Karpenter).

The cost of this design is that you need a controller and a proxy to route players into
pods that did not exist a moment ago. That is what the rest of the stack is.

---

## 4. Every component, and its scope

The point of the table below is the **"does not do"** column. Almost every bug in a
system like this comes from a component quietly doing a job that belongs to another one.

### Players' path into a game

| Component | Does | Does **not** do |
|---|---|---|
| **Minecraft client** | Joins a server address. | — |
| **mc-router** | Watches game pods and connects a player to the pod that is ready for them, based on the address they dialled. Starts pods from zero as needed. | Know anything about Bedwars rules. |
| **Velocity** | The single, always-on entry point. Terminates the player's connection and forwards it into the right game server. | Host a match itself. |
| **Game pod (Paper + this plugin)** | Runs exactly one match: teams, beds, generators, shop, scoreboard, stats reporting. | Route players, scale pods, or persist anything beyond the match. |

### The control plane

| Component | Does | Does **not** do |
|---|---|---|
| **Controller** (this project) | Holds the queue, tracks which pods are ready, allocates a pod to a party, scales the GameServerSet, receives pod webhooks (`/pods/ready`, `/heartbeat`, `/ended`, `/draining`). | Run game logic; talk to Minecraft clients. |
| **OpenKruise GameServerSet** | Creates and destroys game pods, and tracks their game lifecycle (`Ready → Allocated → Destroyed`). | Decide how many pods there should be. |
| **KEDA** | Reads a metric (our queue depth) and sets the GameServerSet's replica count. | Know about players individually. |
| **Karpenter** | Adds or removes *nodes* when pods cannot be scheduled. | Touch pods. |

### State

| Component | Does | Does **not** do |
|---|---|---|
| **MySQL** | Player stats, ELO, leaderboards, quick-buy layouts. The only durable player state. | Hold anything about a running match. |
| **S3-compatible storage** (MinIO locally, S3/Ceph in production) | Stores Slime map templates, versioned. | Serve worlds to players directly. |
| **HikariCP** | The JDBC connection pool inside the plugin. Downloads at runtime via `plugin.yml`, not shipped in the jar. | Persist anything itself. |

### Inside a game pod

| Component | Does | Does **not** do |
|---|---|---|
| **Paper** | The Minecraft server engine (a high-performance Spigot fork). Explicitly **not** Folia. | Anything Bedwars-specific. |
| **AdvancedSlimePaper (ASP)** | Loads a Slime world instantly and can clone it per match. | Store maps (that is S3). |
| **This plugin** | The game: state machine, teams, beds, generators, shop, upgrades, traps, scoreboard, language, stats. | Controller/queue/routing/scaling work. |
| **Prometheus** (optional) | Scrapes the controller's `/metrics` and each pod's heartbeats (TPS, players, phase). | Store long-term data by itself. |

### How the arena world gets into a pod

A pod plays exactly **one** match, so its main world *is* the arena — there is no
throwaway world to generate and then replace.

The catch: Paper reads its main world (`level-name` in `server.properties`) **during
startup**, before any plugin is enabled. A plugin therefore cannot stage the arena by
itself. The container does it, in `deploy/docker/entrypoint.sh`, before the JVM starts:

1. Read `BEDWARS_TEMPLATE_SOURCE` and `BEDWARS_TEMPLATE_NAME`.
2. `LOCAL` → copy the template baked at `/templates/<name>` into `/server/world`.
   `S3` → download `…/templates/<name>/<version>.zip` and unpack it.
3. Drop the world's stale `session.lock`, then `exec java -jar spigot.jar`.

With **AdvancedSlimePaper** installed there is a second, richer path: the map stays a
packed Slime file and the plugin loads/clones it at runtime. Set
`arena.templateEnabled: true` for that; leave it `false` when the entrypoint stages the
world, so the two do not both act on it.

### How you run it

| Tool | Does | Does **not** do |
|---|---|---|
| **Docker / Compose** | Builds the images; Compose brings up the whole stack on a single machine. | Provide autoscaling or pod replacement. |
| **Helm** | Templates and installs the Kubernetes objects as one release. | — |
| **Kustomize** | Applies plain-YAML overlays without templating (the `deploy/k8s` base). | — |
| **kubeconform** | Validates manifests against Kubernetes schemas. | — |

---

## 5. Glossary

- **Arena group** — a size of match: `solo`, `doubles`, `quads`. A pod serves exactly one.
- **BUNGEE / modern forwarding** — how Velocity proves to the game server which player
  is connecting, without the server doing its own authentication.
- **Cattle vs pets** — see §2.
- **ELO** — the skill rating; this project uses a documented team-vs-team variant.
- **Game phase** — `WAITING → COUNTDOWN → RUNNING → SUDDEN_DEATH → ENDED`.
- **GameServerSet (GSS)** — OpenKruise's game-aware pod set (§2).
- **Heartbeat** — the pod's periodic report of TPS/players/phase to the controller.
- **Mc-router** — the component that turns "a player dialled this address" into
  "connect them to this pod".
- **Pod** — the disposable unit of a match (§2).
- **Port-forward** — a `kubectl` tunnel from your machine into a pod; how you connect
  manually during local testing (`deploy/tools/join-server.sh`).
- **RCON** — the remote console protocol Minecraft servers expose; `deploy/tools/rcon.py`.
- **Ready pool** — the set of pods that have reported Ready and can be allocated.
- **Scale to zero** — zero replicas when nobody is playing (§2).
- **Slime / SRF** — the *Slime Region Format*: a compact, fast-loading world format.
- **Template** — the stored Slime map, `name@version` (e.g. `Glacier@1.0.0`).
- **Webhook** — the HTTP calls a pod makes to the controller.

---

## 6. What this project deliberately does **not** do

These are design constraints, not missing features:

1. No arena kept on disk per game — maps come from templates.
2. No block-by-block rollback — destroying the pod is the reset.
3. No Folia.
4. No player stats held inside a pod — they live in MySQL.
5. No pod without strict CPU/memory requests **and** limits.
6. No pod outliving its match.
7. No special or long-lived pod — if a design decision makes one pod different, it is
   wrong.
