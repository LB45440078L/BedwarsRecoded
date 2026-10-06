# Development & operations tools

Small helpers, standard library only. Everything here runs on Linux and macOS with the
tools you already have.

| Tool | Needs | What it does |
|---|---|---|
| `join-server.sh` | bash + `kubectl` | Port-forwards a game server so a Minecraft client can connect. |
| `rcon.py` | Python 3 (stdlib) | Source-RCON client: run server/plugin commands and read the reply. |
| `server-status.py` | Python 3 (stdlib) | Server List Ping: is the server up, what version, who is on. |
| `test-resolve-server-jar.sh` | bash + Python 3 | Proves the game-server image compiles Spigot only when it has no other option. |

Repository-root checks:

| Tool | Needs | What it does |
|---|---|---|
| `deploy/verify.sh` | bash + Maven | Runs the test suite and the JAR size gate. |
| `deploy/verify_deploy.py` | Python 3 + PyYAML | Structural checks over the manifests, the Compose file and the image assets. |
| `deploy/verify_k8s.sh` | helm, kubectl, kubeconform | Renders the Helm chart and the Kustomize base, then schema-validates them. |

The verifiers find `helm`, `kubectl` and `kubeconform` on `PATH`; override with the
`HELM`, `KUBECTL`, `KUBECONFORM` environment variables.

---

## Connecting a client to a running game server

Game servers are ephemeral and have no stable address, so there is nothing to type into
a client by default. Expose one:

```bash
deploy/tools/join-server.sh                      # localhost:25565 -> the running game server
NAMESPACE=bedwars deploy/tools/join-server.sh 25570
```

Under Docker Compose the port is already published, so connect straight to
`localhost:25565`. In production players never dial a pod directly — they arrive through
Velocity, which routes them to a server the controller has placed them on.

---

## Headless verification

Anything that touches gameplay is only proven on a running server. Two ways to drive one
without a human at the keyboard:

- **RCON** — `deploy/tools/rcon.py "bw status"` against any BedwarsRecoded server. This is
  how match lifecycle, the dragon phases and the map-destruction counters were checked.
- **A protocol-level bot client** — log in, walk, mine, chat, and assert on the reply. Any
  Minecraft-protocol library works; `deploy/tools/server-status.py` covers the quick
  "is it up / what version" case, and a scripted bot covers the rest.

Both speak plain TCP, so they work identically against a local server, a Compose
container, or a Kubernetes pod (via `join-server.sh`).
