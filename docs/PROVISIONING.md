# Provisioning and controller security

This document explains how the controller creates and reclaims game servers, and how
the remote control plane is protected. It is the operational model behind the
`ServerProvisioner` abstraction.

## Why an abstraction at all

The controller's job is matchmaking and capacity management. It must not care whether a
game server is a Kubernetes pod, a Docker container, or a process on another machine.
Everything the controller knows about infrastructure goes through one interface:

```
ServerProvisioner
├── KubernetesProvisioner   (OpenKruise GameServerSet, production)
├── DockerProvisioner       (Docker CLI, single host / dev)
└── NoopProvisioner         (scaling disabled)
```

`ProvisionerFactory` is the only place that names a concrete backend. The battle-tested
part — queue, dispatch, allocation — is identical whichever backend is configured,
which is exactly what "keep infrastructure providers replaceable" means in practice.

## Capacity model

Capacity is expressed in two units:

- a **server** — a dedicated Minecraft host; and
- **games per server** — how many concurrent BedWars matches one host runs.

```
total game slots = servers × games-per-server
```

`games-per-server` is configurable (`BEDWARS_GAMES_PER_SERVER`, default 25) and is never
hard-coded into gameplay logic. It is a planning figure the controller reports on
`/metrics` (`bedwars_game_capacity`) and on `/infra`; it is the host's own resource
limits that ultimately decide how many matches fit.

## Allocation (avoiding races)

1. Players queue at the lobby; the lobby POSTs `/lobby/queue`.
2. The queue drains in priority order; each entry is dispatched to a **ready** pod
   taken atomically from `ReadyPodRegistry` (a pod is removed from the pool the instant
   it is allocated, so two simultaneous requests can never receive the same slot).
3. If no ready pod exists, the controller calls `provisioner.scaleUpOne()` — a pre-warm
   hint — and tells the client to retry after a backoff.
4. When a new server boots it reports `/pods/ready`; the registry re-drains the queue
   and waiting players are placed.

Because allocation removes from a concurrent queue, duplicate matchmaking requests
cannot double-book a slot.

## Scale-down (reclaiming idle servers)

Dynamically created servers are reclaimed by `ScaleDownPolicy`, which is pure and
unit-tested. A server is only reclaimed when **all** of these hold:

- scale-down is enabled;
- nobody is waiting in any queue;
- there is an idle (ready) server to reclaim — an allocated server is never in the
  ready pool, so this is what guarantees *"never terminate an active game"*;
- the fleet is above `BEDWARS_MIN_SERVERS`; and
- the fleet has been continuously idle for `BEDWARS_IDLE_MINUTES`.

The controller evaluates the policy every 60s and, on a positive decision, calls
`provisioner.scaleTo(current - 1)`.

## Kubernetes backend

`KubernetesProvisioner` delegates to a `GameServerSetScaler` (fabric8 client) that
mutates only `spec.replicas` of an OpenKruise `GameServerSet`. Primary scaling is KEDA's
job (see `deploy/k8s/11-keda-scaledobject.yaml`); the controller's own scaling is a
pre-warm hint. If the Kubernetes client cannot be built, the factory falls back to
`NoopProvisioner` and the controller still serves the queue.

## Docker backend

`DockerProvisioner` drives the Docker CLI. It names containers `<prefix>-<n>`, labels
them `bedwars.provisioned=true`, and caps each with `BEDWARS_CONTAINER_MEMORY`.

- **Isolation:** it only ever touches containers carrying its label, so unrelated
  containers on the host are invisible to it.
- **No shells:** every command is an argument list (`ProcessBuilder`), so there is no
  shell to inject into and no quoting bugs.
- **No orphans:** scale-down stops *and* removes the container; an opt-in integration
  test asserts the host is left clean.
- **Timeouts:** each command is bounded; a hung `docker` cannot stall the controller.
- **Reuse:** `scaleTo` restarts existing stopped containers before creating new ones.

Configuration (`BEDWARS_PROVISIONER=DOCKER`):

| Variable | Default | Meaning |
|---|---|---|
| `BEDWARS_SERVER_PREFIX` | `bedwars-game` | container name prefix |
| `BEDWARS_DOCKER_IMAGE` | `bedwars-recoded-game:latest` | image to run |
| `BEDWARS_CONTAINER_MEMORY` | `1536m` | per-container memory cap |
| `BEDWARS_CONTROLLER_ADVERTISE_URL` | `http://host.docker.internal:8080` | URL a container uses to reach the controller |
| `BEDWARS_ARENA_GROUP` | `solo` | arena group the container joins |

## Controller security

The controller is a remote control plane: game servers on other machines report to it,
and any caller can enqueue players. Mutating endpoints are therefore protected by a
shared secret.

- Set `BEDWARS_API_TOKEN` to require authentication on `POST /pods/*` and
  `POST /lobby/queue`. Clients present it as `X-Bedwars-Token: <token>` or
  `Authorization: Bearer <token>`.
- The comparison is constant-time (`MessageDigest.isEqual`); the token is never logged
  or returned by any endpoint (`/infra` reports only `authenticated: true|false`).
- Read-only endpoints (`/healthz`, `/metrics`, `/queue/depth`, `/lobby/arena-status`,
  `/infra`) stay open so monitoring works without the secret.
- With no token configured the controller logs a loud warning at startup and stays open
  — the documented single-host development mode. **Do not expose an unauthenticated
  controller to a network you do not control.**

Never commit the token. Supply it as an environment variable / Kubernetes Secret.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/lobby/queue` | yes | request a game slot (capacity-aware dispatch) |
| POST | `/pods/ready` | yes | a game server reports it is ready for a match |
| POST | `/pods/started` | yes | match started |
| POST | `/pods/heartbeat` | yes | TPS / player count / phase |
| POST | `/pods/draining` | yes | server is leaving the pool |
| POST | `/pods/ended` | yes | match ended |
| GET | `/healthz` | no | liveness |
| GET | `/metrics` | no | Prometheus metrics |
| GET | `/queue/depth` | no | per-group queue depth (JSON) |
| GET | `/lobby/arena-status` | no | per-group ready/queued counts |
| GET | `/infra` | no | provisioner description, server/capacity counts |
