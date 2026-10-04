# Setup Guide — from a bare machine to a running match

This is the complete, ordered setup path. It goes from "nothing installed" to a
Paper game pod that has booted, loaded the plugin, reported **Ready** to the
controller, and received a dispatched player from `/lobby/queue`.

Three paths are covered:

| Path | Use it for | Cost |
|---|---|---|
| **A. Local JVM** | fastest smoke test of the plugin | ~2 min, no containers |
| **B. Docker Compose** | integration stack (MySQL + MinIO + controller + one game pod) | ~5 min |
| **C. Kubernetes (minikube)** | the real target: pods-as-cattle, GameServerSet, KEDA, mc-router, Velocity | ~15 min |

Path C is the one that was verified end-to-end on a real cluster; A and B are
supported and validated but were not exercised in this environment (no Compose
invocation was possible here — see the note at the end).

---

## 0. Prerequisites

| Tool | Version used | Notes |
|---|---|---|
| JDK | **25+** (built with Temurin 27, `--release 25`) | required for records/pattern matching/virtual threads |
| Maven | 3.9.16 | wrapper-less; any 3.9+ works |
| Docker | Docker Desktop 4.x | only for paths B and C |
| minikube | 1.39+ | path C; `docker` driver |
| kubectl | 1.3x | path C |
| helm | 3.x | path C |

Everything Maven builds runs against the Spigot API `26.2-R0.1-SNAPSHOT` and the
Velocity API `4.2.0`, both of which resolve from public repositories
(`hub.spigotmc.org`, `repo.papermc.io`).

> **Windows-hosted WSL note.** On this machine the build runs in WSL while Docker,
> minikube, kubectl and helm are Windows binaries. `deploy/tools/winrun.sh` bridges
> that gap — it invokes a Windows `.exe` from WSL and strips carriage returns:
> ```bash
> ~/projects/BedwarsRecoded/deploy/tools/winrun.sh minikube.exe -p bedwars status
> ```
> Windows binaries cannot read WSL `/tmp` paths; if a tool needs a temp directory,
> point it at a `/mnt/c/...` path (e.g. `BEDWARS_K8S_TMP=/mnt/c/Users/<you>/bedwars-k8s/tmp`).

---

## 1. Get the code and set up the toolchain

```bash
# The WSL copy is canonical; builds only run here.
cd ~/projects/BedwarsRecoded

# Project toolchain (JDK 27 + Maven 3.9.16) — sets JAVA_HOME/PATH
source ~/.local/tools/env.sh
```

If you cloned fresh instead:

```bash
git clone https://github.com/LB45440078L/BedwarsRecoded.git ~/projects/BedwarsRecoded
```
(the project is also mirrored to `C:\Users\<you>\projects\BedwarsRecoded`.)

---

## 2. Build everything

```bash
cd ~/projects/BedwarsRecoded
source ~/.local/tools/env.sh

mvn clean install          # all 5 modules + full test suite
```

Expected: `BUILD SUCCESS`, **72 tests**, 0 failures. The `verify` phase runs an Ant
size gate that **fails the build if the plugin jar exceeds 4 MB**.

Module build only (faster loop):

```bash
mvn -pl BedwarsRecoded-Spigot -am package
```

Artifacts:

```
BedwarsRecoded-API/target/BedwarsRecoded-API-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Core/target/BedwarsRecoded-Core-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-1.0.0-SNAPSHOT.jar   # the plugin
BedwarsRecoded-Velocity/target/BedwarsRecoded-Velocity-1.0.0-SNAPSHOT.jar
BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-1.0.0-SNAPSHOT.jar
```

Verify the size gate yourself:

```bash
ls -lh BedwarsRecoded-Spigot/target/*.jar     # ~210 KB, far under 4 MB
```

---

## Path A — Local JVM smoke test (no containers)

1. Get a Paper server jar for 26.2:

   ```bash
   mkdir -p ~/bedwars-local/server/plugins
   cd ~/bedwars-local/server
   # download the Paper 26.2 build from https://papermc.io/downloads
   ```

2. Accept the EULA:

   ```bash
   echo "eula=true" > eula.txt
   ```

3. Install the plugin:

   ```bash
   cp ~/projects/BedwarsRecoded/BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar plugins/
   mkdir -p plugins/BedwarsRecoded/templates
   # put a world template directory at plugins/BedwarsRecoded/templates/Glacier
   ```

4. Start the server:

   ```bash
   java -Xms1G -Xmx2G -jar paper-*.jar nogui
   ```

5. On first boot Paper downloads the **runtime `libraries:`** declared in `plugin.yml`
   (HikariCP, mysql-connector-j) — this is why the jar stays tiny. You should see:

   ```
   [BedwarsRecoded] Loading server plugin BedwarsRecoded v1.0.0-SNAPSHOT
   ```

   With no MySQL reachable the plugin logs `Persistence unavailable; running without
   stats` and keeps working — stats are simply not written.

6. Drive the game in-game:

   ```
   /bw status      # game id, state, players, teams
   /bw join        # join the match
   /bw gui         # join GUI
   /bw shop        # item shop (shift-click toggles quick buy)
   /bw upgrades    # team upgrade merchant
   /bw quickbuy    # quick-buy editor
   /bw lang <code> # per-player language
   /bw start       # start countdown
   /bw reload      # hot-reload config.yml + arena.yml (needs bedwars.admin)
   ```

---

## Path B — Docker Compose integration stack

The Compose stack brings up MySQL, MinIO, the controller, and (optionally) one game
pod — enough to run the whole dispatch flow without Kubernetes.

### B.1 Configure

```bash
cd ~/projects/BedwarsRecoded/deploy/compose
cp .env.example .env
$EDITOR .env        # set MYSQL_ROOT_PASSWORD, S3 creds; NEVER commit real secrets
```

### B.2 Start infra + controller

```bash
docker compose up -d mysql minio minio-init controller
docker compose ps
```

### B.3 Upload a Slime template to the local MinIO

```bash
# minio-init creates the bucket; put your template here:
#   s3://bedwars-templates/<name>/<version>/<name>-<version>.slime
docker compose exec minio-init mc ls local/bedwars-templates
```

### B.4 Start a game pod

```bash
docker compose --profile game up -d game
docker compose logs -f game
```

Look for the pod reporting ready to the controller:

```
[bedwars-pod] pod_ready pod=game-1 game=... template=Glacier@1.0.0
```

### B.5 Exercise the queue

```bash
curl -s localhost:18080/healthz
curl -s localhost:18080/metrics | grep bedwars_
curl -s -XPOST localhost:18080/lobby/queue \
     -H 'Content-Type: application/json' \
     -d '{"player":"11111111-1111-1111-1111-111111111111","username":"Tester","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
```

A ready pod yields `{"podAddress":"...","members":[...]}`; no capacity yields a
`retryAfterMillis` backoff instead.

### B.6 Tear down

```bash
docker compose --profile game down -v
```

---

## Path C — Kubernetes (minikube) — the real target

### C.1 Start the cluster

```bash
# Windows-hosted WSL:
~/projects/BedwarsRecoded/deploy/tools/winrun.sh minikube.exe start -p bedwars
# native Linux/macOS:
minikube start -p bedwars
```

Deploy from a **`/mnt/c` directory** (Windows `cmd.exe` cannot use a UNC working dir):

```bash
mkdir -p /mnt/c/Users/thevi/bedwars-k8s
cd /mnt/c/Users/thevi/bedwars-k8s
W=~/projects/BedwarsRecoded/deploy/tools/winrun.sh
$W kubectl.exe get nodes
```

### C.2 Build and load the images

Docker builds must run where the Docker daemon is (Windows side):

```bash
cd /mnt/c/Users/thevi/projects/BedwarsRecoded      # the mirror
W=~/projects/BedwarsRecoded/deploy/tools/winrun.sh

$W docker.exe build -f deploy/docker/controller.Dockerfile -t bedwars-controller:1.0.0 .
$W docker.exe build -f deploy/docker/velocity.Dockerfile   -t bedwars-velocity:1.0.0   .
$W docker.exe build -f deploy/docker/spigot.Dockerfile     -t bedwars-spigot:1.0.0     .

$W minikube.exe -p bedwars image load bedwars-controller:1.0.0 bedwars-velocity:1.0.0 bedwars-spigot:1.0.0
```

### C.3 Install the Helm chart

```bash
cd /mnt/c/Users/thevi/bedwars-k8s
$W helm.exe install bedwars ~/projects/BedwarsRecoded/deploy/helm/bedwars \
   -f ~/projects/BedwarsRecoded/deploy/helm/bedwars/values-minikube.yaml \
   -n bedwars --create-namespace
```

`values-minikube.yaml` is required on a small machine: it shrinks the game-pod
footprint to request `500m/512Mi`, limit `1/1536Mi`, and disables MinIO (whose image
cannot be pulled anonymously in this sandbox). The base `values.yaml` ships the
production footprint (**2 CPU / 4 GiB, hard requests *and* limits**).

Wait for the control plane:

```bash
$W kubectl.exe -n bedwars rollout status deploy/bedwars-controller --timeout=150s
$W kubectl.exe -n bedwars get pods
```

Expect `controller`, `mc-router`, `velocity`, `mysql` all `1/1 Running`.

### C.4 Scale a game pod up and watch it boot

```bash
$W kubectl.exe -n bedwars get gameserversets
$W kubectl.exe -n bedwars scale gameserversets bedwars-solo --replicas=1
$W kubectl.exe -n bedwars get pod bedwars-solo-0 -w      # Ctrl-C once 1/1 Running
```

First boot takes ~60 s (Paper world load + runtime library download). In the log:

```
Preparing level "world"
Done preparing level (17.512s)
Done (53.587s)! For help, type "help"
[BedwarsRecoded] Loading server plugin BedwarsRecoded v1.0.0-SNAPSHOT
```

### C.5 Verify the full dispatch loop

```bash
# Forward the controller API
$W kubectl.exe -n bedwars port-forward svc/bedwars-controller 18081:8080 &
C=/mnt/c/Windows/System32/curl.exe

# 1. the pod has reported Ready for its arena group
"$C" -s http://localhost:18081/metrics | grep bedwars_
#   bedwars_queue_depth{group="solo"} 0
#   bedwars_ready_pods{group="solo"} 1      <-- the live Paper pod

# 2. lobby NPC/sign state
"$C" -s http://localhost:18081/lobby/arena-status
#   {"solo":{"ready":1,"queued":0}}

# 3. dispatch a player
"$C" -s -XPOST http://localhost:18081/lobby/queue \
     -H 'Content-Type: application/json' \
     -d '{"player":"11111111-1111-1111-1111-111111111111","username":"Tester","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
#   {"podAddress":"bedwars-solo-0","members":["1111..."],"retryAfterMillis":0}

# 4. the pod is consumed
"$C" -s http://localhost:18081/metrics | grep bedwars_ready_pods
#   bedwars_ready_pods{group="solo"} 0
```

Scaling back down removes the pod from the pool on SIGTERM:

```bash
$W kubectl.exe -n bedwars scale gameserversets bedwars-solo --replicas=0
$W kubectl.exe -n bedwars logs deploy/bedwars-controller --tail=3
#   Pod bedwars-solo-0 removed from the ready pool; {"podId":"bedwars-solo-0",...,"phase":"DRAINING"}
```

### C.6 Run the verification suites

```bash
cd /mnt/c/Users/thevi/projects/BedwarsRecoded     # scripts must run from the /mnt/c mirror
./deploy/verify.sh                 # 72 tests + 70 deploy-asset checks + 4 MB gate
./deploy/verify_k8s.sh             # helm lint/render + kustomize render + kubeconform
```

### C.7 Teardown

```bash
$W helm.exe uninstall bedwars -n bedwars
$W minikube.exe -p bedwars stop        # or: delete
```

---

## 4. Configuration reference

Precedence: **environment variable → `config.yml` → built-in default**.

### `BedwarsRecoded-Spigot/src/main/resources/config.yml`

| Key | Env var | Default | Meaning |
|---|---|---|---|
| `server-id` | `BEDWARS_SERVER_ID` | hostname | pod identity used in every webhook |
| `arena.group` | `BEDWARS_ARENA_GROUP` | `solo` | arena group this pod serves |
| `arena.team-count` | `BEDWARS_TEAM_COUNT` | `2` | teams in the match |
| `arena.players-per-team` | `BEDWARS_PLAYERS_PER_TEAM` | `2` | players per team |
| `arena.countdown-seconds` | `BEDWARS_COUNTDOWN_SECONDS` | `15` | pre-match countdown |
| `arena.sudden-death-after-seconds` | `BEDWARS_SUDDEN_DEATH_SECONDS` | `300` | time until sudden death |
| `arena.void-y-threshold` | — | `0.0` | Y below which a player is void-killed |
| `arena.island-radius` | — | `30.0` | island protection/radius approximation |
| `arena.bed-protection-radius` | — | `3.0` | radius around a bed that is protected |
| `template.name` | `BEDWARS_TEMPLATE_NAME` | `Glacier` | template to load |
| `template.version` | `BEDWARS_TEMPLATE_VERSION` | `1.0.0` | template semver (canary deploys) |
| `template.source` | `BEDWARS_TEMPLATE_SOURCE` | `LOCAL` | `S3` (production) or `LOCAL` (dev) |
| `template.local-path` | `BEDWARS_TEMPLATE_LOCAL_PATH` | `templates` | directory **containing** template dirs |
| `template.s3.*` | — | minio defaults | endpoint/bucket/credentials |
| `controller.base-url` | `BEDWARS_CONTROLLER_URL` | `http://bedwars-controller:8080` | webhook target |
| `controller.heartbeat-seconds` | `BEDWARS_HEARTBEAT_SECONDS` | `15` | heartbeat period |
| `database.*` | `BEDWARS_DB_*` | `localhost/bedwars` | host, port, name, user, password, pool-size |
| `ranking.k-factor` | — | `32` | ELO K |
| `ranking.leaderboard-refresh-seconds` | — | `60` | cached leaderboard refresh |
| `logging.json` | — | `true` | emit JSON event lines |

### `arena.yml`

Defines this pod's arena group end-to-end: `group`, `teams` (bed + spawn), `generators`,
`shop`, `start-items`, and the per-group `upgrades:` tree:

```yaml
upgrades:
  SHARPNESS:
    - level: 1
      currency: DIAMOND
      amount: 2
      effect: 1
      description: "Sharpness I"
```

If `upgrades:` is absent, `UpgradeCatalog.defaults()` is used.

---

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Local template not found` | `template.local-path` must point at the *directory containing* template dirs; the template dir must be named exactly `template.name`. |
| `ConnectException ... minio:9000` | `template.source: S3` but MinIO is not reachable in-cluster. Expected in the minikube overlay (MinIO disabled). |
| Pod exits immediately | Missing `eula.txt` — the Dockerfile writes it, but a manual image won't. |
| Controller never shows `bedwars_ready_pods` | The pod's `arena.group` must match the queue request's `preferredGroup`; check `READY for group <x>` in the controller log. |
| `helm upgrade` didn't change pod env | Re-run `helm upgrade` **then** re-scale the GameServerSet; pods keep their creation-time env. |
| Node OOM / API server timeouts | The production footprint is 2 CPU / 4 GiB per pod. On a small machine use `values-minikube.yaml`. |
| `port-forward` shows nothing | A previous forward still holds the port. Kill it and use a different local port (e.g. 18081). |
| Velocity pod never Ready | The probe port must match the listener (25565). |

---

## 6. What is verified vs. what is not

**Verified on a real cluster (minikube, K8s v1.37):** controller + velocity +
mc-router + mysql running; a Paper game pod boots, loads the plugin, downloads its
runtime libraries, connects to MySQL, reports **Ready for group `solo`**; a
`/lobby/queue` request dispatches to that live pod; scaling to 0 removes it from the
ready pool. `helm lint` clean; 51 manifest schemas valid; 72 tests green.

**Not exercised here:** the Docker Compose stack (no Compose invocation available in
this sandbox — assets are validated structurally), and the S3/MinIO template fetch
(MinIO is disabled in the minikube overlay because its image cannot be pulled
anonymously here).
