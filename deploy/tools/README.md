# Development & operations tools

Everything in this directory is portable unless a file says otherwise.
**Linux and macOS are the primary targets**; Windows is supported through a small,
clearly-labelled bridge. Nothing in the deployment itself (Dockerfiles, Kubernetes
manifests, Helm chart, Compose file) is platform-specific — servers run Linux.

| Tool | Platform | What it does |
|---|---|---|
| `join-server.sh` | **any** (bash + `kubectl`) | Port-forwards a game pod so a Minecraft client can connect. |
| `rcon.py` | **any** (Python 3, stdlib only) | Source-RCON client: run server/plugin commands and read the reply. |
| `winrun.sh` | **Windows / WSL only** | Bridge that runs a Windows `.exe` from WSL with a usable `PATH`. Not needed anywhere else. |
| `botdrive.ps1` | **Windows only** | Drives the Windows bot client in headless mode. The bot itself is a Windows binary; see below for the portable alternative. |

Also at the repository root:

| Tool | Platform | What it does |
|---|---|---|
| `deploy/verify.sh` | any (bash) | Runs the test suite, the deployment-asset checks and the JAR size gate. |
| `deploy/verify_k8s.sh` | any (bash) | Renders the Helm chart and the Kustomize base, then schema-validates them. |
| `deploy/verify_deploy.py` | any (Python 3) | Structural checks over the manifests and the Compose file. |

`verify_k8s.sh` and `verify_deploy.py` find `helm`, `kubectl` and `kubeconform` on your
`PATH`; override with the `HELM`, `KUBECTL`, `KUBECONFORM` environment variables:

```bash
# Linux / macOS - just install the tools normally
sudo apt install -y kubectl helm          # or brew install kubectl helm
deploy/verify_k8s.sh
```

---

## Running a server locally (any platform)

You do **not** need Kubernetes, or Windows, to run this project. Two ordinary paths
work identically on Linux, macOS and Windows:

1. **A plain Minecraft server + the plugin jar** — see `docs/SETUP.md` §"Path A".
   This is the simplest way to test gameplay.
2. **Docker Compose** — MySQL + MinIO + the controller + one game pod, one command,
   no cluster. See `docs/SETUP.md` §"Path B" and `deploy/compose/`.

Kubernetes (`docs/SETUP.md` §"Path C") is the *production* shape: it is what gives you
one ephemeral pod per match, autoscaling and scale-to-zero. Use it when you are testing
the architecture rather than the game.

---

## Notes on the Windows-only tools

They exist because this project was originally developed on a Windows 11 machine with
WSL, where Docker/minikube/kubectl are Windows binaries and WSL cannot call them
directly. If you are on Linux or macOS you can ignore both files entirely.

- **`winrun.sh`** writes a small `.bat` and runs a Windows `.exe` through `cmd.exe`.
  It also fixes `PATH`, because a Docker Desktop install needs
  `docker-credential-desktop` findable. On Linux/macOS the equivalent is simply calling
  the tool: `kubectl …`, `helm …`, `docker …`. Each invocation uses a unique `.bat`
  name, so two concurrent calls no longer clobber each other.

- **`botdrive.ps1`** drives the Windows Minecraft bot client. The portable answer is to
  use any Minecraft client, or a cross-platform bot library:

  | Need | Portable option |
  |---|---|
  | A client to join and play | the official Minecraft client, or [Prism Launcher](https://prismlauncher.org/) |
  | Scripted bots on Linux/macOS | [mineflayer](https://github.com/PrismarineJS/mineflayer) (Node.js) or [baritone](https://github.com/cabaletta/baritone)-style agents |
  | Just "is the server up / who is on" | `rcon.py "list"` |
  | A protocol-level smoke test | `mcstatus` (`pip install mcstatus`), which speaks the Server List Ping protocol |

  Example — two mineflayer bots joining and running a plugin command:

  ```bash
  npm install mineflayer
  node -e '
    const mineflayer = require("mineflayer");
    for (const name of ["BotAlpha", "BotBeta"]) {
      const bot = mineflayer.createBot({ host: "127.0.0.1", port: 25565, username: name, version: "26.3" });
      bot.once("spawn", () => bot.chat("/bw join"));
    }'
  ```

  For the record, the Windows bot needs two things that are easy to get wrong, and the
  same shape applies to any bot: its runtime DLLs must be on `PATH` (the MSYS2 `ucrt64`
  `bin`, for `zlib1.dll`), and its stdin must stay **open** — a file redirect hits EOF
  and the bot disconnects immediately.
