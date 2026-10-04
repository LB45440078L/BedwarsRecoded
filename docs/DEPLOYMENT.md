# Deployment

Two ways to run BedwarsRecoded: a local **Compose** stack for development and
integration testing, and the **Kubernetes** platform for production.

Everything can be checked without a cluster or Docker daemon:

```bash
./deploy/verify.sh
```

runs `mvn verify` (unit + HTTP integration tests), the deployment-asset verifier
(`deploy/verify_deploy.py`), and the 4 MB JAR gate.

## Local integration stack (Docker Compose)

`deploy/compose/docker-compose.yml` brings up the non-Kubernetes equivalent of the
platform: MySQL, MinIO (S3), a bucket initialiser, and the controller.

```bash
cd deploy/compose
cp .env.example .env          # optional; defaults are fine
docker compose up --build     # infra + controller
```

Then exercise the controller:

```bash
curl -s localhost:8080/healthz
curl -s localhost:8080/metrics
# simulate a pod becoming ready, then a lobby queue request
curl -s -XPOST localhost:8080/pods/ready -d '{"podId":"pod-1","arenaGroup":"solo"}'
curl -s -XPOST localhost:8080/lobby/queue \
  -d '{"player":"00000000-0000-0000-0000-000000000001","username":"alice","priority":0,"preferredGroup":"solo","party":null,"requestedAtMillis":0}'
```

The optional `game` profile adds one real Paper pod (heavy — it downloads a Paper
server jar and needs a world):

```bash
docker compose --profile game up --build
```

The controller reads its settings from environment variables
(`BEDWARS_*`); the same names are used by the Kubernetes manifests.

## Kubernetes platform

Manifests live in `deploy/k8s` and are consumed with Kustomize:

```bash
kubectl apply -k deploy/k8s
```

What gets created:

| File | Contents |
|---|---|
| `00-namespace.yaml` | `bedwars` namespace |
| `10-gameserverset.yaml` | OpenKruise GameServerSet (scale-from-zero game pods, strict limits) |
| `11-keda-scaledobject.yaml` | KEDA ScaledObject on `bedwars_queue_depth` + Karpenter NodePool |
| `12-servicemonitor.yaml` | Prometheus ServiceMonitor for the controller `/metrics` |
| `13-networkpolicy.yaml` | default-deny ingress; pods never talk to each other |
| `14-pdb.yaml` | PDBs for the persistent tier only (game pods are cattle) |
| `20-mc-router.yaml` | mc-router deployment + Service + RBAC |
| `30-velocity.yaml` | Velocity proxy deployment + Service |
| `40-controller.yaml` | controller deployment + Service + RBAC |
| `50-s3-config.yaml` | S3 endpoint/bucket ConfigMap + credentials Secret |
| `51-mysql.yaml` | MySQL StatefulSet (dev-grade; use a managed cluster in prod) |
| `52-minio.yaml` | dev-only S3 (MinIO) |

### Images

Build the three images and push them to your registry:

```bash
docker build -f deploy/docker/spigot.Dockerfile     -t $REG/bedwars-spigot:1.0.0     .
docker build -f deploy/docker/velocity.Dockerfile   -t $REG/bedwars-velocity:1.0.0   .
docker build -f deploy/docker/controller.Dockerfile -t $REG/bedwars-controller:1.0.0 .
```

Update `deploy/k8s/kustomization.yaml` `images:` to point at your registry.

### Secrets

Replace the placeholder Secrets (`bedwars-s3-credentials`, `bedwars-mysql`) with
SealedSecrets or ExternalSecrets before applying to a real cluster. The verifier
does not check secret values.

### Scaling model

- **KEDA** watches `bedwars_queue_depth` (exposed by the controller) and adjusts
  GameServerSet replicas, including from zero.
- **Karpenter** provisions nodes when pod demand exceeds capacity.
- The controller also pre-warms: when a queue request cannot be satisfied it asks
  the GameServerSet for one more replica.

### Pod lifecycle

`PENDING → READY → ALLOCATED → DRAINING → TERMINATING → DESTROYED`. A pod reports
`POST /pods/ready` when its template is loaded, then `/pods/started`, `/pods/ended`
and `/pods/heartbeat`. On SIGTERM it reports `/pods/draining` and finishes the
current game within `terminationGracePeriodSeconds` (60s).

## Verifying a running cluster

```bash
kubectl -n bedwars get gameserversets
kubectl -n bedwars get pods -l app.kubernetes.io/part-of=bedwars-recoded
kubectl -n bedwars port-forward svc/bedwars-controller 8080:8080 &
curl -s localhost:8080/metrics | grep bedwars_
```

## Troubleshooting

- **Pods never become READY** — check the template pull (S3 credentials, bucket,
  `BEDWARS_TEMPLATE_S3_*`). The controller's `bedwars_ready_pods` gauge stays 0.
- **Queue never dispatches** — `bedwars_ready_pods{group=...}` is 0; KEDA has not
  scaled or the pod is still booting.
- **JAR size gate fails** — a dependency was shaded into the plugin; move it to
  `plugin.yml` `libraries` or mark it `provided`.

## Verifying on a real cluster (minikube)

Verified end-to-end with Docker Desktop + minikube (Kubernetes v1.37, containerd).

1. Cluster and prerequisites:

```bash
minikube start --profile bedwars --driver=docker --cpus=2 --memory=4096
helm repo add openkruise https://openkruise.github.io/charts/
helm install kruise      openkruise/kruise      -n kruise-system --create-namespace --wait
# GameServerSet lives in OpenKruise-Game, a separate chart:
helm install kruise-game openkruise/kruise-game -n openkruise-game-system --create-namespace --wait
```

2. Build and load the images into the node:

```bash
docker build -f deploy/docker/controller.Dockerfile -t bedwars-controller:1.0.0 .
docker build -f deploy/docker/velocity.Dockerfile   -t bedwars-velocity:1.0.0 .
minikube -p bedwars image load bedwars-controller:1.0.0 bedwars-velocity:1.0.0
```

3. Install:

```bash
helm install bedwars deploy/helm/bedwars -n bedwars --create-namespace -f deploy/helm/values-minikube.yaml
```

Observed result: `bedwars-controller`, `velocity`, `mc-router` and `mysql` all `1/1
Running`; `gameserverset.game.kruise.io/bedwars-solo` present; and the controller API
works end-to-end:

```
GET  /healthz      -> ok
POST /pods/ready   -> {"accepted":true}
POST /lobby/queue  -> {"podAddress":"pod-minikube-1","members":["..."],"retryAfterMillis":0}
GET  /metrics      -> bedwars_queue_depth / bedwars_ready_pods
```

MinIO is disabled in the minikube overlay because this sandbox cannot pull
`quay.io/minio/minio` anonymously; the manifest is correct for clusters that can, or
point `s3.endpoint` at an external store.

### Windows-hosted WSL tooling

Here docker/kubectl/helm/minikube/kubeconform are Windows binaries, and WSL does not
inherit the user PATH — which breaks docker's credential helper and minikube's docker
lookup. `deploy/tools/winrun.sh` regenerates a `.bat` that prepends the Docker
Desktop (and Helm/kubeconform/minikube) bin directories and forwards arguments:

```bash
deploy/tools/winrun.sh docker.exe build -t app:1 .
HELM="deploy/tools/winrun.sh helm.exe" \
KUBECTL="deploy/tools/winrun.sh kubectl.exe" \
KUBECONFORM="deploy/tools/winrun.sh kubeconform.exe" \
  deploy/verify_k8s.sh
```

Run these from a `/mnt/c/...` path and set `BEDWARS_K8S_TMP` to a `/mnt/c` directory:
Windows tools cannot read WSL paths, nor run with a UNC working directory.