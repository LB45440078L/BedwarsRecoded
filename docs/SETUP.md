# Setup — from a bare machine to a Bedwars match

This guide takes you from nothing installed to a server you can actually join and play.
Every step says **what it does** and **how to check it worked**, so you are never
copy-pasting blindly.

If words like *container*, *pod* or *GameServerSet* are new, read
[`CONCEPTS.md`](CONCEPTS.md) first — it is a one-page explainer of every technology in
this project and what each one is responsible for.

---

## Minimum requirements

This project deploys as containers: **Docker or Kubernetes, nothing else.** There is no
supported "run the jar on a bare host" mode — the plugin is built to run as a managed
game server that the controller starts and reclaims.

| | Minimum | Comfortable |
|---|---|---|
| **CPU** | 2 cores | 4+ cores |
| **RAM** | 4 GB free (Compose) · 8 GB free (Kubernetes) | 16 GB |
| **Disk** | 12 GB free | 25 GB (image cache, Maven repo, worlds) |
| **OS** | Linux or macOS (x86-64 or arm64) | any current LTS |
| **JDK** | 25 (build *and* runtime) | Temurin 25 |
| **Maven** | 3.9 | 3.9+ |
| **Docker** | Engine 24+ / Desktop 4.x | current |
| **Kubernetes** | 1.30+ — **only** for the Kubernetes path | minikube or kind locally |
| **Network** | outbound HTTPS for the first build | — |

Notes that actually bite:

- **RAM is the constraint, not CPU.** Docker Desktop itself takes 1.0–1.5 GB, a local
  minikube control plane ~1 GB, MySQL 300–500 MB, the controller stack ~400 MB, and one
  game server 700 MB–1.5 GB. 4 GB free runs the Compose stack; a cluster wants 8 GB free.
  Run one heavy stack at a time (`docker compose down` before starting minikube).
- **Disk fills quietly.** A game-server image is ~700 MB and Kubernetes keeps its own
  copy of every image. `minikube delete` reclaims a node's cache; `docker system prune -a`
  reclaims build layers.
- **No GPU, no display, no game client required.** The server is fully headless and is
  verified over RCON and the controller's HTTP API (see
  [`deploy/tools/README.md`](../deploy/tools/README.md)).
- **The first build needs the network** for Maven dependencies, the plugin's own
  runtime libraries, and (unless you supply a jar) the server build.

---

## 0. The two deployment paths

| Path | What it gives you | Needs | Use it when |
|---|---|---|---|
| **A. Docker Compose** | The whole stack on one machine: MySQL, MinIO, the controller, and a real game server. | Docker | you want to run and test **the integration** — stats, templates, controller, dispatch |
| **B. Kubernetes** | The production shape: one disposable pod per match, autoscaling, scale-to-zero. | a cluster (minikube or kind locally) | you want to run **the architecture** as designed |

Both paths run the same game-server image and the same plugin contract; Kubernetes adds
the lifecycle (pods created on demand, destroyed after a match) that Compose emulates with
a single manually-started container. **Start with Compose** — it is the fastest way to a
joinable server, and everything you learn there transfers.

---

## 1. Prerequisites

Install for the path you intend to use; nothing extra is needed for the other.

| Tool | Needed for | Install |
|---|---|---|
| **JDK 25+** | building (both paths) | `apt install openjdk-25-jdk` · `brew install openjdk@25` · [Adoptium](https://adoptium.net) |
| **Maven 3.9+** | building | `apt install maven` · `brew install maven` |
| **Docker** | A and B | [docs.docker.com/get-docker](https://docs.docker.com/get-docker/) |
| **kubectl, helm** | B | `brew install kubectl helm` · [helm.sh](https://helm.sh/docs/intro/install/) |
| **minikube** | B (local cluster) | `brew install minikube` · [minikube.sigs.k8s.io](https://minikube.sigs.k8s.io/docs/start/) |
| **Python 3** | the verification tools | usually already present |

Check what you have:

```bash
java -version && mvn -v
docker --version
kubectl version --client && helm version --short && minikube version   # path B only
```

---

## 2. Build the project

**What this does:** compiles the five modules, runs the test suite, and produces the jars.

```bash
git clone https://github.com/LB45440078L/BedwarsRecoded.git
cd BedwarsRecoded
mvn clean install
```

**How to check it worked:** the build ends with `BUILD SUCCESS` and reports
**189 tests, 0 failures**. The `verify` phase also fails the build if the plugin jar ever
grows past **4 MB**.

```
BedwarsRecoded-API/target/BedwarsRecoded-API-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Core/target/BedwarsRecoded-Core-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-1.0.0-SNAPSHOT.jar   <- the plugin
BedwarsRecoded-Velocity/target/BedwarsRecoded-Velocity-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-1.0.0-SNAPSHOT.jar
```

Only the Spigot jar is a Minecraft plugin; the others run inside the containers.

> **Why the plugin jar is only ~270 KB.** It does not bundle its dependencies.
> `plugin.yml` declares a `libraries:` block (HikariCP, the MySQL driver) and the server
> downloads those on first start. That keeps the jar far under the 4 MB gate, and it is
> why a game server needs network access on its first boot.

---

## 3. The server jar (read this before building an image)

The game-server image runs a Minecraft server that the plugin loads into. Which server it
runs is a **build argument**, and the image is built to avoid compiling anything unless it
has to.

Resolution order in [`deploy/docker/resolve-server-jar.sh`](../deploy/docker/resolve-server-jar.sh):

| # | Source | Cost |
|---|---|---|
| 1 | **A jar you supply** in `server-jars/` | instant — copied straight in |
| 2 | `SERVER_ENGINE=paper` → downloaded from the PaperMC API | a download, never a compile |
| 3 | `SERVER_ENGINE=spigot` (default) with no jar supplied → compiled with the official **BuildTools** | minutes |

The engine is authoritative: with `SERVER_ENGINE=paper`, a `spigot-*.jar` in the folder is
ignored (with a log line) and Paper is fetched instead, so an explicit engine choice is
never silently overridden by a leftover jar.

**The fast path — supply the jar and skip the compile:**

```bash
cp /path/to/spigot-26.3.jar server-jars/         # any single *.jar is used as-is
docker build -f deploy/docker/gameserver.Dockerfile -t bedwars-spigot:1.0.0 .
```

With a jar present, the resolver logs
`using supplied jar … (skipping the Spigot compile entirely)` and the image build takes
tens of seconds. Without one, Spigot is compiled from source — correct, but minutes slower
on every build. See [`server-jars/README.md`](../server-jars/README.md).

**Running Paper instead** (Paper publishes prebuilt jars, so this never compiles):

```bash
docker build --build-arg SERVER_ENGINE=paper --build-arg PAPER_VERSION=26.3 \
             -f deploy/docker/gameserver.Dockerfile -t bedwars-spigot:1.0.0 .
```

> **Why Spigot is the default.** The plugin is written against the Spigot API only, with a
> build-failing test that rejects Paper/Folia imports. Paper is supported as an *engine*
> option because it is a drop-in server implementation, but nothing in the plugin depends
> on it.

**How to check it worked:** the build prints one `[server-jar]` line naming the source
used, and `ls -l` inside the image shows a ~50–90 MB `/server/server.jar`. The jar is
always at that path whatever the engine, so manifests, probes and the entrypoint never
care which one you picked.

---

## 4. Path A — the whole stack with Docker Compose

**What this gives you:** MySQL, MinIO (S3-compatible storage), the controller, and one
game server — the integration, on one machine.

| Service | Role | Why it is here |
|---|---|---|
| `mysql` | player stats, ELO, leaderboards | proof that a game server holds no durable state |
| `minio` | S3-compatible object storage | holds the map template |
| `controller` | queue, server registry, `/metrics` | the piece that routes players to servers |
| `game-pod` | one Minecraft server running the plugin | a real game server, locally |

### A.1 Configure

```bash
cd deploy/compose
cp .env.example .env
$EDITOR .env          # set the MySQL and S3 credentials
```

**How to check:** `.env` exists with non-empty passwords. Never commit it.

### A.2 Start the infrastructure and the controller

```bash
docker compose up -d mysql minio minio-init controller
docker compose ps
```

**How to check:** the four services are `running` (or `exited (0)` for `minio-init`, which
is a one-shot bucket-creation job — exiting 0 *is* success).

### A.3 Start a game server

```bash
docker compose --profile game up -d --build game-pod
docker compose logs -f game-pod
```

**How to check:** the server boots, stages the arena, enables the plugin and reports ready:

```
[entrypoint] staging local template 'Glacier' from /templates/Glacier
[entrypoint] arena ready: … world at /server/world
[BedwarsRecoded] Enabling BedwarsRecoded v1.0.0-SNAPSHOT
bedwars_setup controller_reporting=enabled … controller_url=http://controller:8080
pod_ready pod=pod-local-1 group=solo …
```

The `--build` is where the fast path pays off: with a jar in `server-jars/` this step is
seconds instead of a Spigot compile.

### A.4 Exercise the queue

```bash
curl -s localhost:8080/healthz
curl -s localhost:8080/metrics | grep bedwars_
curl -s localhost:8080/infra
curl -s localhost:8080/lobby/arena-status
curl -s -XPOST localhost:8080/lobby/queue -H 'Content-Type: application/json' \
  -d '{"player":"11111111-1111-1111-1111-111111111111","username":"Tester","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
```

A server with a free match slot answers `{"podAddress":"…","members":[…]}`; with no
capacity you get `{"podAddress":null,"retryAfterMillis":…}`, telling the client to back off
rather than to keep hammering.

**How to check it worked:** `/infra` reports `"registeredServers": 1`, and the queue
dispatch returns an address while a slot is free.

### A.5 Join and play

Connect a Minecraft client (version 26.3) to **`localhost:25565`** and run:

```
/bw join        join the match
/bw status      game id, state, players, teams
/bw gui         join menu
/bw shop        item shop (shift-click an item to toggle quick buy)
/bw upgrades    team upgrades
/bw quickbuy    quick-buy editor
/bw lang        your language
```

Operators additionally get `/bw start`, `/bw stop`, `/bw reload`, and
`/bw create <n>` to open extra matches on the same server.

**How to check it worked:** `/bw status` reports `players=1` after you join, and when a
second player joins, the countdown runs and the state moves to `RUNNING`.

### A.6 Tear down

```bash
docker compose --profile game down -v
```

---

## 5. Path B — Kubernetes (the production shape)

**What this gives you:** the real architecture — one ephemeral pod per match, a
scale-to-zero idle state, and pods that are destroyed rather than reset. Read
[`CONCEPTS.md`](CONCEPTS.md) §2–§4 if the object names below are unfamiliar.

### B.1 Start a cluster

```bash
minikube start -p bedwars --cpus=4 --memory=6g
kubectl get nodes
```

**What this does:** creates a single-node cluster inside a container.
**How to check:** `kubectl get nodes` shows one node, `Ready`.

### B.2 Build and load the images

```bash
docker build -f deploy/docker/controller.Dockerfile -t bedwars-controller:1.0.0 .
docker build -f deploy/docker/velocity.Dockerfile   -t bedwars-velocity:1.0.0   .
docker build -f deploy/docker/gameserver.Dockerfile -t bedwars-spigot:1.0.0     .
# ^ put a jar in server-jars/ first (see §3) or this one compiles Spigot for minutes

minikube -p bedwars image load bedwars-controller:1.0.0 bedwars-velocity:1.0.0 bedwars-spigot:1.0.0
```

**How to check:** `minikube -p bedwars image ls | grep bedwars` shows all three.

> **The Minecraft version must match your client's protocol.** It is fixed by the jar you
> run, not by a config value: supply/build the server jar for the version you play on.
> A client on a different version is refused at login.

> **Local clusters can only see loaded images.** That is why the GameServerSet sets
> `imagePullPolicy: IfNotPresent` — with the OpenKruise default (`Always`) the node tries
> to pull a tag that exists only in your local daemon and the pod never starts.

### B.3 Install the stack

```bash
helm install bedwars deploy/helm/bedwars \
  -f deploy/helm/values-minikube.yaml \
  -n bedwars --create-namespace
```

**What this does:** creates the namespace and installs every object — the controller, the
GameServerSet, the KEDA scaling rule, the RBAC the controller needs, MySQL, and the S3
configuration.

`values-minikube.yaml` is the **small-machine overlay**: it shrinks the game pod and
disables MinIO. The base `values.yaml` ships the production footprint (2 CPU / 4 GiB per
pod, requests *and* limits).

**How to check:**

```bash
kubectl -n bedwars get pods
kubectl -n bedwars get gameserversets
```

Expect the controller and MySQL `Running`. The GameServerSet shows **DESIRED 0** — that is
correct: pods are created on demand, not kept warm for free.

### B.4 Start a game pod and watch it boot

```bash
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=1
kubectl -n bedwars get pods -w          # Ctrl-C once the pod is 1/1 Running
```

**What this does:** exactly what the controller does when a player queues — the replica
count goes 0 → 1 and Kubernetes schedules a fresh pod.

**How to check:** the pod reaches `1/1 Running` in roughly 30–60 seconds, and

```bash
kubectl -n bedwars logs <pod> | grep -E "bedwars_setup|pod_ready"
kubectl -n bedwars logs deploy/bedwars-controller | grep READY
```

shows the plugin's startup summary, then `pod_ready`, then the controller recording the
pod as ready for its group.

### B.5 Join the running server

A game pod has no permanent public address — by design, it is created on demand and
destroyed when its match ends. For local testing, tunnel in:

```bash
deploy/tools/join-server.sh            # localhost:25565 -> the ready game pod
deploy/tools/join-server.sh 25570      # or a different local port
```

Connect your client to **`localhost:25565`** (version 26.3). `Ctrl-C` stops the tunnel; the
pod keeps running. In production players never do this — they arrive through Velocity,
which routes them to the server the controller placed them on.

### B.6 Verify the routing story end to end

```bash
kubectl -n bedwars port-forward svc/bedwars-controller 8080:8080 &
curl -s localhost:8080/metrics | grep bedwars_free_slots   # capacity the controller can see
curl -s localhost:8080/lobby/arena-status                  # {"solo":{"ready":1,"queued":0}}
curl -s -XPOST localhost:8080/lobby/queue -H 'Content-Type: application/json' \
  -d '{"player":"11111111-1111-1111-1111-111111111111","username":"Tester","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
curl -s localhost:8080/metrics | grep bedwars_free_slots   # one slot fewer: it was consumed
```

Scaling back to zero removes the pod from the pool, which is the correct behaviour:

```bash
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=0
kubectl -n bedwars logs deploy/bedwars-controller | tail -3
```

### B.7 Tear down

```bash
helm uninstall bedwars -n bedwars
minikube -p bedwars stop            # or: delete, to reclaim the disk
```

---

## 6. Verify everything

```bash
./deploy/verify.sh                          # test suite + the 4 MB jar gate
python3 deploy/verify_deploy.py             # manifests, Compose, and the image assets
./deploy/verify_k8s.sh                      # render the chart + kustomize base, then schema-validate
bash deploy/tools/test-resolve-server-jar.sh  # the image compiles Spigot only when it must
mvn verify                                  # the plugin's tests alone
```

`verify_k8s.sh` takes `HELM`, `KUBECTL` and `KUBECONFORM` from the environment if your
tools are not on `PATH`. All of these run without a cluster.

For a real end-to-end check, start a server (§4 or §5) and drive it headless — over RCON
or with a protocol-level bot client. See
[`deploy/tools/README.md`](../deploy/tools/README.md).

---

## 7. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `report_failed … ConnectException` repeating | The server cannot reach its controller. Reporting is throttled and then disabled for the session; check `BEDWARS_CONTROLLER_URL` names the controller on the shared network. |
| `persistence_unavailable running without stats` | No MySQL reachable. Set `persistence.enabled: false` if you deliberately run without one. |
| `Public Key Retrieval is not allowed` | MySQL 8's default auth over a non-TLS link. The JDBC URL already sets `allowPublicKeyRetrieval=true`; if you changed it, put it back. |
| `You are not whitelisted on this server!` | A server build that enables the whitelist by its own default, with an empty `whitelist.json`, rejects everyone. The image writes `white-list=false` and the plugin does too (`server.force-whitelist-off`); set it to `LEAVE` only if you really want a whitelist. |
| Pod/container exits immediately | Missing `eula.txt`. The image writes it; a hand-rolled image will not. |
| Client cannot connect (protocol mismatch) | The server jar's Minecraft version must match the client. Supply or build the matching jar — see §3. |
| The image build compiles Spigot every time | `server-jars/` is empty. Drop a jar in it to skip the compile entirely. |
| `ready_pods` never appears | The server's `arena.group` must equal the request's `preferredGroup`. Check `READY for group <x>` in the controller log. |
| `helm upgrade` did not change pod env | Pods keep their creation-time environment. Re-run the upgrade, then re-scale the GameServerSet. |
| Node OOM / API server timeouts | The production footprint is 2 CPU / 4 GiB per pod. Use `values-minikube.yaml` on a small machine. |
| `port-forward` prints nothing | A previous forward still holds the port. Kill it, or pass a different local port. |
| A stale world or `session.lock` | A downloaded world carries a lock from wherever it was last opened. Delete `world/session.lock`; the entrypoint does this for you on a pod. |

---

## 8. Where to go next

- [`CONCEPTS.md`](CONCEPTS.md) — containers, Kubernetes, pods, and each component's scope.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — pod lifecycle, the controller protocol, the scaling model.
- [`API.md`](API.md) — DTOs, events, services and the HTTP surface.
- [`MIGRATIONS.md`](MIGRATIONS.md) — how to add a database migration.
- [`DEPLOYMENT.md`](DEPLOYMENT.md) — deployment details and verification.
- [`../server-jars/README.md`](../server-jars/README.md) — supplying a server jar.
- [`../deploy/tools/README.md`](../deploy/tools/README.md) — RCON, status and port-forward tooling.
