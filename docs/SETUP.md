# Setup — from a bare machine to a Bedwars match

This guide takes you from nothing installed to a server you can actually join and play.
Every step says **what it does** and **how to check it worked**, so you are never
copy-pasting blindly.

If words like *container*, *pod* or *GameServerSet* are new, read
[`CONCEPTS.md`](CONCEPTS.md) first — it is a one-page explainer of every technology in
this project and what each one is responsible for.

---

## 0. Pick your path

There are three ways to run this project. They are not versions of each other; they
answer different questions.

| Path | What it gives you | Needs | Use it when |
|---|---|---|---|
| **A. Plain server** | The game, running on one long-lived Minecraft server. | Java + a server jar | you want to test **gameplay** (teams, beds, shop, scoreboard) |
| **B. Docker Compose** | The whole stack on one machine: MySQL, MinIO, controller, one game pod. | Docker | you want to test **the integration** (stats, templates, controller) |
| **C. Kubernetes** | The production shape: one disposable pod per match, autoscaling, scale-to-zero. | a cluster (minikube locally) | you want to test **the architecture** |

**Recommended order:** A → B → C. Each path is a superset of understanding, and A is
the fastest way to see the plugin work.

Every path works on **Linux, macOS and Windows**. Path C is the only one with
platform-specific notes, and they live in [§7](#7-appendix-windows--wsl) at the end —
on Linux/macOS you can ignore that section entirely.

---

## 1. Prerequisites

| Tool | Version used | Needed for | Install |
|---|---|---|---|
| **JDK 25+** | built with Temurin 27, targets 25 | A, B, C | `apt install openjdk-25-jdk` / `brew install openjdk` / [Adoptium](https://adoptium.net) |
| **Maven 3.9+** | 3.9.16 | building | `apt install maven` / `brew install maven` |
| **Docker** | Docker Desktop / Engine 24+ | B, C | [docs.docker.com/get-docker](https://docs.docker.com/get-docker/) |
| **kubectl** | 1.30+ | C | `apt install kubectl` / `brew install kubectl` |
| **helm** | 3.x | C | `apt install helm` / `brew install helm` |
| **minikube** | 1.39+ | C (local cluster) | `brew install minikube` / [minikube.sigs.k8s.io](https://minikube.sigs.k8s.io/docs/start/) |

Optional: **Python 3** (for `deploy/verify_deploy.py` and `deploy/tools/rcon.py`) and
**kubeconform** (for manifest validation).

Check what you have:

```bash
java -version && mvn -v
docker --version && kubectl version --client && helm version --short && minikube version
```

---

## 2. Build the project

**What this does:** compiles the five modules, runs the test suite, and produces the
jars. It is a plain Maven multi-module build; nothing platform-specific.

```bash
git clone https://github.com/LB45440078L/BedwarsRecoded.git
cd BedwarsRecoded
mvn clean install
```

**How to check it worked:** the build ends with `BUILD SUCCESS` and reports the test
count (currently **130 tests, 0 failures**). The `verify` phase also fails the build if
the plugin jar ever grows past **4 MB**.

Where the jars land:

```
BedwarsRecoded-API/target/BedwarsRecoded-API-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Core/target/BedwarsRecoded-Core-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-1.0.0-SNAPSHOT.jar   <- the plugin
BedwarsRecoded-Velocity/target/BedwarsRecoded-Velocity-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-1.0.0-SNAPSHOT.jar
```

Only the Spigot jar is a Minecraft plugin; the others are used inside the containers.

> **Why the jar is only ~230 KB.** The plugin does not bundle its dependencies.
> `plugin.yml` declares a `libraries:` block (HikariCP, the MySQL driver), and the
> server downloads those on first start. That is what keeps the jar far under the size
> gate, and it is why the first boot needs network access.

---

## 3. Path A — run a plain server and join it

This is the simplest path and the one to start with. You end up with a Minecraft server
running the plugin, which you can join directly.

### A.1 Get a server jar

**What this does:** downloads the Minecraft server engine the plugin runs inside. This
project targets **Spigot/Paper 26.x** and deliberately does **not** support Folia.

Paper is recommended (faster, and what production uses). Grab a 26.3 build:

```bash
mkdir -p ~/bedwars-local/server/plugins
cd ~/bedwars-local/server
# from https://papermc.io/downloads  (choose 26.3) - or, on any OS:
curl -s -o paper.json https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest
curl -sL "$(grep -o 'https://[^"]*paper-26.3-[0-9]*\.jar' paper.json | head -1)" -o paper.jar
```

**How to check:** `ls -lh paper.jar` shows a jar of roughly 50–90 MB.

### A.2 Accept the EULA and install the plugin

**What this does:** Minecraft servers refuse to start until you accept Mojang's EULA,
and the plugin has to sit in the server's `plugins/` directory to be loaded.

```bash
cd ~/bedwars-local/server
echo "eula=true" > eula.txt                       # required by Mojang, once
cp ../../BedwarsRecoded/BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar plugins/BedwarsRecoded.jar
```

### A.3 Start it

```bash
java -Xms1G -Xmx2G -jar paper.jar --nogui
```

**What to look for in the console.** On the very first boot the server downloads the
plugin's runtime libraries, then enables it. You should see:

```
[SpigotLibraryLoader] [BedwarsRecoded] Loading 2 libraries... please wait
[SpigotLibraryLoader] [BedwarsRecoded] Loaded library .../HikariCP-6.3.0.jar
[BedwarsRecoded] Enabling BedwarsRecoded v1.0.0-SNAPSHOT
bedwars_setup mode=STANDALONE (configured AUTO) controller_reporting=not used (not a pod) ...
```

...and then `Done (…)` and a `>` prompt.

### A.4 Read the startup summary — it tells you which setup you are in

That `bedwars_setup` line is the plugin telling you what it decided to be. This matters,
because the same jar is designed to run both as a Kubernetes game pod and as an ordinary
server:

| Line | Meaning |
|---|---|
| `mode=STANDALONE` | Ordinary server. **No controller requests are made at all.** |
| `mode=POD` | It is behaving as a game pod: reporting to a controller, using the template store. |
| `configured AUTO` | You left `deployment.mode: AUTO`, so it probed the controller and decided. |

If you are on a plain server and you were seeing repeated
`report_failed … ConnectException` lines, that is the old behaviour — it is fixed. The
plugin now probes once at boot and, if there is no controller, never calls it. The three
settings that control this are in `plugins/BedwarsRecoded/config.yml`:

```yaml
deployment:
  mode: "AUTO"          # POD | STANDALONE | AUTO
  disable-reporting-after-failures: 5
  failure-log-interval-seconds: 300
persistence:
  enabled: true         # false = never open a JDBC connection
template:
  enabled: true         # false = keep whatever world the server already has
```

Set `mode: STANDALONE`, `persistence.enabled: false` and `template.enabled: false` for a
completely standalone, offline, zero-noise server.

### A.5 Join and play

Start your Minecraft client (26.3), connect to `localhost`, and run:

```
/bw join        join the match
/bw status      game id, state, players, teams
/bw gui         join menu
/bw shop        item shop (shift-click an item to toggle quick buy)
/bw upgrades    team upgrades
/bw quickbuy    quick-buy editor
/bw lang        your language
```

Operators additionally get:

```
/bw start       start the countdown
/bw stop        abort the match
/bw reload      re-read config.yml + arena.yml without restarting
```

**How to check it worked:** `/bw status` reports `players=1` after you join, and when a
second player joins the countdown runs and the state moves to `RUNNING`.

### A.6 Optional: a world that looks like an arena

Out of the box the plugin uses whatever world the server generated, and `arena.yml`
describes the bed/spawn/generator positions. On a fresh world those coordinates are
empty space. Two ways to fix it for testing:

- Edit `plugins/BedwarsRecoded/arena.yml` to use coordinates near spawn, or
- Put a real world at `plugins/BedwarsRecoded/templates/<name>/` and keep
  `template.source: LOCAL`.

(Neither matters for Path C: there the world arrives as a Slime template from S3.)

---

## 4. Path B — the whole stack with Docker Compose

**What this gives you:** MySQL, MinIO (S3-compatible storage), the controller, and one
game pod — the integration, on one machine. Same commands on Linux, macOS and Windows.

**What each service is:**

| Service | Role | Why it is here |
|---|---|---|
| `mysql` | player stats, ELO, leaderboards | proof that a pod holds no durable state |
| `minio` | S3-compatible object storage | holds the Slime map template |
| `controller` | queue + pod registry + `/metrics` | the piece that routes players to pods |
| `game` | one Paper server running the plugin | a real game pod, locally |

### B.1 Configure

```bash
cd deploy/compose
cp .env.example .env
$EDITOR .env          # set MYSQL_ROOT_PASSWORD and S3 credentials
```

**How to check:** `.env` exists and has non-empty passwords. Never commit it.

### B.2 Start the infrastructure and the controller

```bash
docker compose up -d mysql minio minio-init controller
docker compose ps
```

**How to check:** `docker compose ps` lists the four services as `running`/`exited (0)`
(`minio-init` is a one-shot job that creates the bucket, so exiting 0 is success).

### B.3 Put a template in the object store

**What this does:** the game pod pulls its world from here, which is why no pod ever
keeps an arena on disk.

```bash
docker compose exec minio-init mc ls local/bedwars-templates
```

A template lives at `templates/<name>/<version>.slime` inside that bucket.

### B.4 Start a game pod

```bash
docker compose --profile game up -d game
docker compose logs -f game
```

**How to check:** the log shows the plugin enabling and reporting ready:

```
bedwars_setup mode=POD (configured AUTO) controller_reporting=enabled ...
pod_ready pod=game-1 game=… template=Glacier@1.0.0
```

### B.5 Exercise the queue

```bash
curl -s localhost:8080/healthz
curl -s localhost:8080/metrics | grep bedwars_
curl -s localhost:8080/lobby/arena-status
curl -s -XPOST localhost:8080/lobby/queue -H 'Content-Type: application/json' \
  -d '{"player":"11111111-1111-1111-1111-111111111111","username":"Tester","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
```

A ready pod answers with `{"podAddress":"…","members":[…]}`; with no capacity you get
`{"podAddress":null,"retryAfterMillis":…}` telling the client to back off.

### B.6 Tear down

```bash
docker compose --profile game down -v
```

---

## 5. Path C — Kubernetes (the production shape)

**What this gives you:** the real architecture — one ephemeral pod per match, a session
of zero players costing nothing, and pods that are destroyed rather than reset. Read
[`CONCEPTS.md`](CONCEPTS.md) §2–§4 if any of the object names below are unfamiliar.

The commands below are plain POSIX. If you are on Windows/WSL, read
[§7](#7-appendix-windows--wsl) first — the commands are the same, only how you reach the
binaries changes.

### C.1 Start a cluster

```bash
minikube start -p bedwars --cpus=4 --memory=6g
kubectl get nodes
```

**What this does:** creates a single-node Kubernetes cluster inside a container.
**How to check:** `kubectl get nodes` shows one node, `Ready`.

### C.2 Build and load the images

**What this does:** builds the three images from `deploy/docker/` and pushes them into
the cluster's image store. `minikube image load` is how a local cluster gets an image
that is not in a registry.

```bash
docker build -f deploy/docker/controller.Dockerfile -t bedwars-controller:1.0.0 .
docker build -f deploy/docker/velocity.Dockerfile   -t bedwars-velocity:1.0.0   .
docker build --build-arg PAPER_VERSION=26.3 \
             -f deploy/docker/spigot.Dockerfile     -t bedwars-spigot:26.3     .

minikube -p bedwars image load bedwars-controller:1.0.0 bedwars-velocity:1.0.0 bedwars-spigot:26.3
```

> The Minecraft version is a **build argument**: the game pod only accepts clients whose
> protocol matches. Build `PAPER_VERSION=26.3` if you play on 26.3.

**How to check:** `minikube -p bedwars image ls | grep bedwars` shows all three.

### C.3 Install the stack

```bash
helm install bedwars deploy/helm/bedwars \
  -f deploy/helm/values-minikube.yaml \
  -n bedwars --create-namespace
```

**What this does:** creates the namespace and installs every object: the controller, the
mc-router, Velocity, MySQL, the GameServerSet, the KEDA scaling rule, the RBAC the
controller needs, and the S3 configuration.

`values-minikube.yaml` is the **small-machine overlay**: it shrinks the game pod and
disables MinIO. The base `values.yaml` ships the production footprint (2 CPU / 4 GiB per
pod, requests *and* limits).

**How to check:**

```bash
kubectl -n bedwars get pods
kubectl -n bedwars get gameserversets
```

Expect the controller, mc-router, Velocity and MySQL `Running`. The GameServerSet shows
**DESIRED 0** — that is correct: pods are created on demand.

### C.4 Start a game pod and watch it boot

```bash
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=1
kubectl -n bedwars get pods -w          # Ctrl-C once bedwars-solo-0 is 1/1 Running
```

**What this does:** this is exactly what the controller does when a player queues — the
replica count goes 0 → 1, and Kubernetes schedules a fresh pod.

**How to check:** the pod reaches `1/1 Running` in roughly 30–60 seconds, and:

```bash
kubectl -n bedwars logs bedwars-solo-0 | grep bedwars_setup
```

shows `mode=POD … controller_reporting=enabled`. The controller log then records:

```bash
kubectl -n bedwars logs deploy/bedwars-controller | grep READY
#  Pod bedwars-solo-0 READY for group solo
```

### C.5 Join the running server

A game pod has no permanent public address — by design, it is created on demand and
destroyed when its match ends. For local testing you tunnel in with `kubectl`:

```bash
deploy/tools/join-server.sh            # localhost:25565 -> the ready game pod
# or a different local port:
deploy/tools/join-server.sh 25570
```

Then connect your Minecraft client to **`localhost:25565`** (version 26.3).

**What the script does:** finds the running game pod by its label, port-forwards the
Minecraft port to your machine, and prints the address. `Ctrl-C` stops the tunnel; the
pod keeps running. In production players never do this — they arrive through
mc-router/Velocity.

### C.6 Verify the routing story end to end

```bash
kubectl -n bedwars port-forward svc/bedwars-controller 8080:8080 &
curl -s localhost:8080/metrics | grep bedwars_          # ready_pods{solo} 1
curl -s localhost:8080/lobby/arena-status               # {"solo":{"ready":1,"queued":0}}
# dispatch a player to that pod:
curl -s -XPOST localhost:8080/lobby/queue -H 'Content-Type: application/json' \
  -d '{"player":"11111111-1111-1111-1111-111111111111","username":"Tester","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
curl -s localhost:8080/metrics | grep bedwars_ready_pods  # now 0: the pod was consumed
```

Scaling back to zero removes the pod from the ready pool, which is the correct
behaviour and is visible in the controller log:

```bash
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=0
kubectl -n bedwars logs deploy/bedwars-controller | tail -3
#  Pod bedwars-solo-0 removed from the ready pool; {"phase":"DRAINING"}
```

### C.7 Tear down

```bash
helm uninstall bedwars -n bedwars
minikube -p bedwars stop            # or: delete, to reclaim the disk
```

---

## 6. Verify everything

```bash
./deploy/verify.sh              # tests + deployment-asset checks + the 4 MB jar gate
./deploy/verify_k8s.sh          # render the chart/kustomize base, then schema-validate
python3 deploy/verify_deploy.py # structural checks over manifests and the compose file
```

All three are portable. `verify_k8s.sh` takes `HELM`, `KUBECTL` and `KUBECONFORM` from
the environment if your tools are not on `PATH`.

Run the plugin's tests alone with:

```bash
mvn verify
```

---

## 7. Appendix: Windows / WSL

Ignore this whole section on Linux and macOS. It exists because on a Windows 11 machine
Docker Desktop, minikube, kubectl and helm are **Windows binaries**, while a shell in
WSL cannot call them directly and does not inherit the Windows `PATH`.

The only Windows-specific tool is `deploy/tools/winrun.sh`, which runs a Windows `.exe`
from WSL with a repaired `PATH`:

```bash
W=deploy/tools/winrun.sh
$W docker.exe build -f deploy/docker/spigot.Dockerfile -t bedwars-spigot:26.3 .
$W minikube.exe -p bedwars start
$W kubectl.exe -n bedwars get pods
```

Two consequences worth knowing:

- **Run it from a `/mnt/c/...` directory.** Windows `cmd.exe` cannot use a
  `\\wsl.localhost\...` working directory, so set `BEDWARS_WIN_WORKDIR` or `cd` to a
  path under `/mnt/c` first.
- **WSL cannot reach a Windows service on `127.0.0.1`.** For `port-forward`/RCON use the
  host address instead:

  ```bash
  HOSTIP=$(ip route show default | awk '{print $3}')
  python3 deploy/tools/rcon.py --server-dir /mnt/c/... --host "$HOSTIP" "bw status"
  ```

If you are testing *without* a cluster (Path A), none of this applies — a plain
`java -jar` server works the same in WSL as anywhere else.

### Running low on memory

A 16 GB laptop cannot hold everything at once. Rough budget:

| Component | RSS |
|---|---|
| Docker Desktop | 1.0–1.5 GB |
| minikube control plane | ~1 GB |
| MySQL | 300–500 MB |
| controller + mc-router + Velocity | ~400 MB |
| one Paper game pod | 700 MB–1.5 GB |

So: **run one heavy thing at a time**, always deploy Kubernetes with
`values-minikube.yaml`, and stop what you are not using:

```bash
kubectl -n bedwars scale gameserversets bedwars-solo --replicas=0
minikube -p bedwars stop
docker compose down
```

Cap the memory WSL itself may take (`%UserProfile%\.wslconfig`):

```ini
[wsl2]
memory=6GB
processors=4
swap=2GB
```

Free disk as well as RAM — a full disk wedges a node just as thoroughly:
`minikube -p bedwars delete` reclaims the node's image cache; `docker system prune -a`
reclaims build layers.

Everything except the cluster can be verified without any of this: `mvn verify` needs
no Docker at all.

---

## 8. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `report_failed … ConnectException` repeating | A controller was expected but is absent. This is now throttled and then disabled; to remove it entirely set `deployment.mode: STANDALONE`. |
| `persistence_unavailable running without stats` | No MySQL reachable. Expected on a plain server; set `persistence.enabled: false` to silence it. |
| `Public Key Retrieval is not allowed` | MySQL 8's default auth over a non-TLS link. The JDBC URL already sets `allowPublicKeyRetrieval=true`; if you changed it, put it back. |
| `Local template not found` | `template.local-path` must be the directory *containing* template directories, and the directory must be named after `template.name`. |
| Pod exits immediately | Missing `eula.txt`. The game image writes it; a manual image will not. |
| `You are not whitelisted on this server!` | Some server builds enable the whitelist by their own default; with an empty `whitelist.json` that rejects everyone. The game image now writes `white-list=false` **and** the plugin switches it off in `POD` mode. On a standalone server, set `server.force-whitelist-off: OFF` to have the plugin do it too, or `LEAVE` to keep your whitelist. |
| Client cannot connect (protocol mismatch) | The pod's Minecraft version must match your client. Rebuild the image with `--build-arg PAPER_VERSION=<your version>`. |
| `ready_pods` never appears | The pod's `arena.group` must equal the request's `preferredGroup`. Check `READY for group <x>` in the controller log. |
| `helm upgrade` did not change pod env | Pods keep their creation-time environment. Re-run the upgrade, then re-scale the GameServerSet. |
| Node OOM / API server timeouts | The production footprint is 2 CPU / 4 GiB per pod. Use `values-minikube.yaml` on a small machine. |
| `port-forward` prints nothing | A previous forward still holds the port. Kill it, or pass a different local port. |
| Pod never becomes Ready | Check `kubectl -n bedwars logs <pod>`. On a plain server the common cause is a missing world template. |

---

## 9. Where to go next

- [`CONCEPTS.md`](CONCEPTS.md) — containers, Kubernetes, pods, and every component's scope.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — pod lifecycle, the controller protocol, the scaling model.
- [`API.md`](API.md) — DTOs, events, services and the HTTP surface.
- [`MIGRATIONS.md`](MIGRATIONS.md) — how to add a database migration.
- [`DEPLOYMENT.md`](DEPLOYMENT.md) — deployment details and verification.
- [`../deploy/tools/README.md`](../deploy/tools/README.md) — the tooling, and which parts are Windows-only.
