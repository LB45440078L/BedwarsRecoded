# BedwarsRecoded — The Complete Manual

> Everything about this project in one place: the technologies it is built from, the
> requirements it places on a machine, the exact commands to run it, and an explanation of
> every parameter in every configuration file.
>
> Written to be read end to end by someone who has never deployed a server, and to be used
> as a reference by someone who has.

---

## Contents

**Part I — Orientation**

1. [What BedwarsRecoded is](#1-what-bedwarsrecoded-is)
2. [How to read this manual](#2-how-to-read-this-manual)

**Part II — The technologies, from first principles**

3. [Minecraft servers, plugins, and the Spigot API](#3-minecraft-servers-plugins-and-the-spigot-api)
4. [Java, the JVM, and the build](#4-java-the-jvm-and-the-build)
5. [Containers, images, and Docker](#5-containers-images-and-docker)
6. [Docker Compose](#6-docker-compose)
7. [Kubernetes, and the parts this project uses](#7-kubernetes-and-the-parts-this-project-uses)
8. [OpenKruise, KEDA, and Karpenter](#8-openkruise-keda-and-karpenter)
9. [Velocity, the lobby, and mc-router: how players reach a match](#9-velocity-the-lobby-and-mc-router-how-players-reach-a-match)
10. [The data layer: MySQL, object storage, and Slime worlds](#10-the-data-layer-mysql-object-storage-and-slime-worlds)

**Part III — Getting it running**

11. [Requirements](#11-requirements)
12. [The repository, file by file](#12-the-repository-file-by-file)
13. [Build it, command by command](#13-build-it-command-by-command)
    - [13.8 The guided installer](#138-the-guided-installer-installsh)
      - [13.8.1 Missing dependencies](#1381-missing-dependencies)
14. [The server jar: Spigot versus Paper](#14-the-server-jar-spigot-versus-paper)
15. [Path A — the whole stack with Docker Compose](#15-path-a--the-whole-stack-with-docker-compose)
16. [Path B — Kubernetes](#16-path-b--kubernetes)

**Part IV — Reference and operation**

17. [Configuration reference](#17-configuration-reference)
18. [Running and operating a match](#18-running-and-operating-a-match)
19. [Verification: what each tool proves](#19-verification-what-each-tool-proves)
20. [Troubleshooting](#20-troubleshooting)
21. [Security](#21-security)
22. [Capacity and performance](#22-capacity-and-performance)

**Part V — Appendices**

- [A. Glossary](#appendix-a--glossary)
- [B. Ports and endpoints](#appendix-b--ports-and-endpoints)
- [C. Environment variable index](#appendix-c--environment-variable-index)
- [D. Frequently asked questions](#appendix-d--frequently-asked-questions)
- [E. Known limitations](#appendix-e--known-limitations)

---

# Part I — Orientation

## 1. What BedwarsRecoded is

BedwarsRecoded is a complete BedWars platform for Minecraft, built for a specific
architectural idea: **the game server is disposable.**

Most Minecraft servers are pets. They are installed once, tended for years, patched by
hand, and everyone is afraid to touch them because the world lives on that disk. That
model does not scale, and it cannot recover from a crash cleanly. BedwarsRecoded treats
game servers as **cattle**: a server exists to host a small number of matches, then it is
destroyed. Nothing important is stored on it, so destroying it costs nothing.

That single decision cascades into everything else in this repository:

- **A pod is the reset button.** In classic BedWars servers, resetting an arena means
  regenerating the world or reloading a schematic, and every plugin that has ever touched
  that world has to be told to forget it. Here, the match ends, the pod is destroyed, and
  the next match starts on a pod with a pristine world. There is no reset code to get wrong.
- **Worlds come from object storage.** Because a pod may be created at any moment, the arena
  cannot live in the image as a mutable directory. Templates are versioned objects in
  S3-compatible storage and are staged into the pod at boot.
- **Stats leave the pod immediately.** Player stats, ELO and leaderboards are written to a
  shared MySQL. A pod has no unique data, so it needs no backup.
- **The queue, not the server, decides who plays.** Players ask a central controller for a
  slot; the controller answers with the address of a server that has room. Players never
  browse a server list.

### The pieces

| Piece | What it is | Lives where |
|---|---|---|
| **Plugin** | The BedWars game itself: arenas, teams, generators, shop, dragons, ranking. | Runs inside each game server |
| **Controller** | Matchmaking and capacity control plane. Decides who plays where and when to create a server. | One Deployment/container |
| **Velocity** | The proxy. Players connect here first; it forwards them to the game server the controller chose. | 2 replicas, persistent |
| **mc-router** | A tiny TCP router that directs a player's connection by hostname. | 1 replica, persistent |
| **Game server** | One dedicated Minecraft server hosting a small number of matches. Created and destroyed all day. | OpenKruise GameServerSet / Docker |
| **MySQL** | Stats, ELO, leaderboards, quick-buy layouts. Shared, persistent. | Persistent |
| **Object storage** | Versioned arena templates. | Persistent (MinIO or real S3) |

### The constraints this project is built to

These are not preferences; the design is shaped around them.

1. **Spigot is the target.** The plugin compiles against the Spigot API and runs on Spigot.
   Paper is supported as a *deployment option* for the game server, never as a code
   dependency. (`SpigotOnlyApiTest` enforces this mechanically.)
2. **Java 25.**
3. **The plugin JAR stays under 4 MB.** It is 284,366 bytes. Heavy libraries (the JDBC driver,
   the connection pool) are declared in `plugin.yml` and downloaded by the server at startup
   instead of being bundled.
4. **Capacity is `servers × games-per-server`**, and both are configurable. Never hard-coded.
5. **Every pod has CPU and memory requests and limits.** No unbounded pods.
6. **Game servers never talk to each other.** All coordination passes through the controller.
7. **Two deployment paths, both first-class:** Docker Compose (one host) and Kubernetes.

---

## 2. How to read this manual

The manual is deliberately cumulative. Part II explains the technologies assuming you know
nothing about them; Part III applies them; Part IV is a reference you will come back to.

Three suggestions:

- **If you have never deployed a Minecraft server:** read Part II in order. It is not filler.
  The later chapters assume you know what a container is and what a Kubernetes Service does.
- **If you know Kubernetes but not Minecraft:** read chapters 3, 9, 10 and 14 carefully; skim
  the rest of Part II.
- **If you just want it running:** jump to chapter 13, then 15 (Docker) or 16 (Kubernetes),
  and keep chapter 17 open beside you.

**Conventions used throughout:**

- Commands are shown with the repository root as the working directory, and a leading `$`
  marks the shell prompt (do not type the `$`).
- Anything in `<angle brackets>` is a placeholder you replace.
- `BEDWARS_*` names in `CONSTANT_WIDTH` are environment variables.
- Claims about behaviour are things that were measured or read out of the code, not assumed.
  Where something was **not** verified in this environment, the manual says so plainly.

A note on honesty, because it matters for a manual: this repository has been run on a real
machine, its tests executed, its images built and booted, and a live game server registered
with a live controller under both Docker and Kubernetes. The places where that is *not* true
are collected at the end in [Appendix E](#appendix-e--known-limitations) rather than being
quietly implied.

---

## 3. Minecraft servers, plugins, and the Spigot API

### 3.1 The base game

Minecraft Java Edition ships a server (`server.jar`). Run it and you get a world, a
`server.properties` file, and a console. It is not moddable by design — plugins and mods are
third-party additions — and the Java edition server has always been distributed in a way
that makes automation awkward (more on that in chapter 14).

### 3.2 The Bukkit/Spigot API

The overwhelming majority of Minecraft "plugins" are written against the **Bukkit API**, an
event-driven abstraction layered over the vanilla server. Its shape is simple and it is the
shape you need to understand before anything else in this project makes sense:

```java
public final class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new MyListener(), this);
    }
}

public final class MyListener implements Listener {
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.SURVIVAL) {
            event.setCancelled(true);   // veto the vanilla behaviour
        }
    }
}
```

Three ideas carry the whole model:

1. **Events.** The server fires events (`PlayerJoinEvent`, `BlockBreakEvent`, `EntityDamageEvent`)
   and plugins may observe them, modify them, or cancel them.
2. **The scheduler.** Plugins never block the main thread; work is scheduled onto ticks
   (20 per second by default).
3. **Adapters, not forks.** Worse plugins reimplement game logic against raw NMS (the
   obfuscated internals). Better ones express domain logic in plain Java and keep the
   Bukkit-facing code as thin as possible.

**Spigot versus Bukkit versus Paper.** Bukkit is the original API. Spigot is a
performance-oriented fork of the vanilla server that also maintains the API, and it is what
the vast majority of public plugins target. Paper is a further fork of Spigot with additional
optimisations and *additional, Paper-only API*. A plugin written against Paper's API will not
run on Spigot; a plugin written against Spigot's API runs on both. That asymmetry is the
reason for constraint #1 above: **this project compiles against Spigot and uses no Paper-only
surface**, so the same plugin runs on either engine. Chapter 14 explains how to pick the
engine at deploy time.

### 3.3 The parts of the API this project actually uses

| API surface | Used for |
|---|---|
| `JavaPlugin` / `onEnable` / `onDisable` | Plugin lifecycle, config loading, task scheduling |
| `Listener` + `@EventHandler` | All gameplay reactions (damage, death, bed break, joins) |
| `BukkitScheduler` | The heartbeat, generator ticking, countdowns, dragon phases |
| `World`, `Location`, `Block` | Arena geometry, island/bed protection, map destruction |
| `Player`, `ItemStack`, `Inventory` | Shops, kits, currencies, inventory views |
| `EnderDragon` / entities | The sudden-death dragons |
| `AsyncChatEvent`-equivalent chat hooks | Tab/prefix and cross-server messages |
| `ServerListPing` (protocol, not API) | External health checks (see `deploy/tools/server-status.py`) |

### 3.4 The plugin's own layering

This repository is a five-module Maven reactor, and the split is not cosmetic:

```
BedwarsRecoded-API        interfaces, records, events — platform-neutral, no Bukkit/Velocity/JDBC
BedwarsRecoded-Core       the game itself: Game aggregate, arenas, dragons, ranking, persistence
BedwarsRecoded-Spigot     the Bukkit adapters: listeners, commands, config binding
BedwarsRecoded-Velocity   the proxy side: queueing players, reading controller dispatches
BedwarsRecoded-Controller matchmaking, capacity, provisioning, and the HTTP control plane
```

`Core` does not import anything from `Spigot`. That is what makes the game logic unit-testable
without a Minecraft server: 95 of the 189 tests run against `Core` alone, with an injected
clock. A rule like "a bed break on a team with no players left eliminates the team" is tested
as a plain Java rule, not by starting a server.

This is also why the manual spends time on architecture: when you deploy this, the thing you
are configuring is not a monolith. It is a small distributed system that happens to play
BedWars.

---

## 4. Java, the JVM, and the build

### 4.1 The JVM and why the version matters so much

Java code is compiled to **bytecode**, not to machine code. A program called the
**JVM (Java Virtual Machine)** reads that bytecode and executes it. Two consequences matter
here:

1. **"Java 25" is not one thing — it is three.** You will encounter three separate installs
   and they are not interchangeable:
   - **JDK (Java Development Kit)** — compiler, tools *and* runtime. Needed to *build*.
   - **JRE (Java Runtime Environment)** — runtime only. Enough to *run*.
   - **`JAVA_HOME`** — an environment variable pointing at whichever JDK you want the tools
     to use. This is the single most common source of confusing build failures.

2. **Bytecode has a version, and older JVMs reject newer bytecode.** A class file compiled
   with `--release 25` will not load on a JVM 21. Since this project sets
   `<maven.compiler.release>25</maven.compiler.release>`, **every machine that compiles or
   runs this software must have Java 25 or newer.**

Why 25 and not 21 (the previous long-term-support release)? The code uses features that are
still preview or absent before it — most visibly `Executors.newVirtualThreadPerTaskExecutor()`
for the controller's HTTP server and a large amount of pattern matching. Virtual threads are
the important one: they let the controller serve every request on its own thread without a
thread pool, which is exactly the property you want in a control plane that mostly waits on
network I/O.

### 4.2 How to install Java 25

The project needs a JDK. Any distribution of OpenJDK 25 works. Verify with:

```bash
$ java -version
openjdk version "25" 2026-09-16
OpenJDK Runtime Environment Temurin-25 (build 25+0)
OpenJDK 64-Bit Server VM Temurin-25 (build 25+0, mixed mode, sharing)
```

If that prints 21 or 17, your build will fail with an error like
`invalid target release: 25`. Install a 25 JDK and make sure it is the one in use.

**macOS** (Homebrew):

```bash
$ brew install openjdk@25
```

Homebrew deliberately does not link a JDK into `/usr/bin` (it would fight with the system
Java). The supported way to select it is with macOS's own helper, which must be run in the
same shell as your Maven command:

```bash
$ export JAVA_HOME=$(/usr/libexec/java_home -v 25)
$ echo "$JAVA_HOME"
/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

That `export` is not permanent — it affects only the current shell. Putting it in `~/.zshrc`
makes it permanent. **Every command in this manual that runs Maven assumes it has been run.**

**Debian/Ubuntu:**

```bash
$ sudo apt-get update
$ sudo apt-get install -y openjdk-25-jdk
$ export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
```

**RHEL/Fedora:**

```bash
$ sudo dnf install -y java-25-openjdk-devel
$ export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
```

### 4.3 The two settings you will actually set

| Variable | Meaning | Set it when |
|---|---|---|
| `JAVA_HOME` | Which JDK the build tools use. | Always, before `mvn`. |
| `JAVA_TOOL_OPTIONS` | JVM flags injected into every Java process, including ones you did not launch yourself. | On game servers, to control the heap |

`JAVA_TOOL_OPTIONS` deserves a warning. It is read by *every* JVM that starts in that
environment, and each one prints `Picked up JAVA_TOOL_OPTIONS: ...` to stderr. That is normal
and harmless. It is used here because the game server is launched by a shell script
(`entrypoint.sh`) and also, indirectly, by Spigot's own library downloader — setting it once
covers all of them. The image sets it to:

```
-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
```

Explained in chapter 22, but briefly: cap the heap at 65 % of the container's memory limit
(leaving the rest for metaspace, Netty's off-heap buffers, thread stacks and the JVM itself),
use the G1 garbage collector, and kill the process outright rather than limping along after
an out-of-memory error — because in this architecture a dead pod is a *replaced* pod.

### 4.4 Maven, and this project's reactor

Maven is the build tool. It reads `pom.xml` ("project object model"), resolves dependencies
from repositories, compiles, runs tests, and packages. The commands you need:

```bash
$ mvn clean install              # compile, test, package, and install into the local repo
$ mvn clean install -DskipTests  # same, without running tests (fast; used for images)
$ mvn -q verify                  # quiet mode: tests + any verification wired into the build
$ mvn -pl BedwarsRecoded-Spigot -am -DskipTests package
```

That last one is worth decoding completely, because it appears in every Dockerfile:

| Flag | Meaning |
|---|---|
| `-pl BedwarsRecoded-Spigot` | `--projects`: build only this module |
| `-am` | `--also-make`: *also* build the modules it depends on (`API`, `Core`) |
| `-DskipTests` | skip test execution (still compiles the tests) |
| `package` | run the lifecycle up to and including packaging the .jar |

`mvn install` is what you run locally; it places the built artifacts in `~/.m2/repository` so
other modules (and future builds) can resolve them. `mvn package` alone does not.

**A "reactor"** is Maven's word for a multi-module build. The parent `pom.xml` lists the five
modules and builds them in dependency order — `API`, then `Core`, then the three consumers.
A parent `pom.xml` with `<packaging>pom</packaging>` produces no jar of its own; it exists to
share versions and plugin configuration.

### 4.5 Where the dependencies come from, and the one that is not there

`pom.xml` declares four repositories:

| Repository | Provides |
|---|---|
| `repo.papermc.io` | The Velocity API |
| `hub.spigotmc.org` | The Spigot API (`spigot-api:26.3-R0.1-SNAPSHOT`) |
| `repo.infernalsuite.com` | The AdvancedSlimePaper API (Slime world loading) |
| `repo.rapture.pw` | `flow-nbt`, a transitive dependency of the above, excluded where unused |

Spigot API dependencies carry `<scope>provided</scope>`, which means "compile against it, but
do not bundle it": the running server supplies those classes. This is normal and it is a large
part of how the JAR stays under 4 MB.

Note the deliberate absence: **a Paper API dependency**. `SpigotOnlyApiTest` asserts that no
Paper-only class is referenced, so a well-meaning import cannot quietly break Spigot
compatibility. The test is the enforcement; the constraint is in the prose.

---

## 5. Containers, images, and Docker

### 5.1 The problem containers solve

"Works on my machine" is the oldest problem in software. A program depends on a language
runtime, libraries, environment variables, file paths and a kernel version, and if any of
those differ between two machines the program behaves differently.

A **container** is an isolated process with its own filesystem, network namespace and process
tree, running on a shared kernel. It is not a virtual machine: there is no second operating
system, so it starts in milliseconds and costs almost nothing. What it gives you is
*packaging* — the filesystem the process sees is defined by an **image**, and the same image
behaves the same way everywhere.

An **image** is a stack of read-only **layers**. Each instruction in a Dockerfile produces a
layer, and layers are content-addressed and cached. This matters enormously in practice:

- Rebuild an image after changing only your application code and Docker **reuses** the base
  image, the language install and the dependency-download layers. That is why the game-server
  image build dropped from minutes to 23 seconds once a prebuilt server jar was supplied.
- Layer order in a Dockerfile is therefore a performance decision, not a stylistic one:
  put things that rarely change near the top, and things that change every commit near the
  bottom.

### 5.2 The vocabulary you need

| Term | Meaning |
|---|---|
| **Image** | The immutable template. `bedwars-spigot:1.0.0` |
| **Container** | A running instance of an image. |
| **Tag** | The part after the colon. `1.0.0`, `latest`. **Not** a version guarantee — it is a movable label |
| **Registry** | A server that stores and serves images (Docker Hub, GHCR, Quay). |
| **Volume** | Storage that outlives the container. `mysql-data` is a volume; the MySQL container's filesystem is not |
| **Docker daemon** | The background service that actually builds and runs containers. The `docker` CLI talks to it over a socket |
| **Docker socket** | `/var/run/docker.sock`. Mounting it into a container gives that container root-equivalent control of the host |
| **Build context** | The directory sent to the daemon when you run `docker build`. The trailing `.` in `docker build ... .` is the context — enlarge it carelessly and you ship gigabytes to the daemon |

### 5.3 Multi-stage builds — the technique this project leans on

Every Dockerfile here is **multi-stage**: several `FROM` instructions, where later stages copy
only specific files out of earlier ones. The reason is size and attack surface. The
game-server Dockerfile has three stages:

```
plugin    (maven:3.9-eclipse-temurin-25, ~700 MB)  ->  builds the plugin jar
server    (eclipse-temurin:25-jdk)                 ->  resolves/compiles the server jar
runtime   (eclipse-temurin:25-jre)                 ->  the actual image
```

The final image never contains Maven, git, the JDK or BuildTools. It is a JRE, the server jar,
the plugin jar and an entrypoint script. The `server` stage *can* compile Spigot; the `runtime`
stage cannot, and does not need to.

### 5.4 The four images this project builds

| Image | Dockerfile | Stage chain | Contents |
|---|---|---|---|
| `bedwars-spigot:1.0.0` | `deploy/docker/gameserver.Dockerfile` | plugin, server, runtime | JRE, `/server/server.jar`, plugin, `/templates` |
| `bedwars-controller:1.0.0` | `deploy/docker/controller.Dockerfile` | build, runtime | JRE, `controller.jar` (+ docker CLI if asked) |
| `bedwars-velocity:1.0.0` | `deploy/docker/velocity.Dockerfile` | build, runtime | JRE, Velocity proxy + the proxy plugin |
| (base images) | — | — | `maven:3.9-eclipse-temurin-25`, `eclipse-temurin:25-jdk`, `eclipse-temurin:25-jre` |

The image is named `bedwars-spigot` for historical reasons. It is **not** Spigot-specific: the
engine is a build argument and the dockerfile is `gameserver.Dockerfile` precisely because the
same image may contain either engine.

### 5.5 Commands worth knowing before you start

```bash
$ docker version                     # is the daemon reachable? (client + server)
$ docker build -f <dockerfile> -t <name:tag> <context>
$ docker run --rm -it <image> sh     # a throwaway container with a shell
$ docker ps -a                       # every container, running or stopped
$ docker logs -f <container>         # follow a container's output
$ docker exec -it <container> sh     # a shell inside a running container
$ docker images                      # what you have locally
$ docker system prune                # reclaim disk from stopped containers/networks
```

If `docker version` prints a client but no server section, the daemon is not running —
`Docker Desktop` on macOS/Windows, `sudo systemctl start docker` on Linux.

---

## 6. Docker Compose

### 6.1 What Compose is for

`docker compose` describes a *set* of containers that belong together in a single YAML file and
starts them with one command. It is the right tool for a single host and for local
development. It is **not** a production orchestrator: it does not reschedule a container that
died, it does not spread work over machines, and its "scaling" is a replica count on one
machine.

The Compose file for this project is `deploy/compose/docker-compose.yml`. Its services:

| Service | Purpose | Persistent? |
|---|---|---|
| `mysql` | Stats and ELO | Yes — named volume `mysql-data` |
| `minio` | S3-compatible template store | Yes — named volume `minio-data` |
| `minio-init` | One-shot job: creates the bucket | No — exits when done |
| `controller` | Matchmaking control plane | No |
| `game-pod` | One real game server (**opt-in**) | No — deliberately |

### 6.2 The vocabulary

| Term | Meaning |
|---|---|
| `services:` | The containers |
| `profiles:` | A service only starts when its profile is named. `game-pod` is behind the `game` profile |
| `depends_on` | Start ordering. With `condition: service_healthy` it also waits for a healthcheck |
| `volumes:` (top level) | Named volumes, created by Compose and kept across `down` |
| `networks:` | The shared network. Every service here joins one called `bedwars` |
| `x-...` | An extension key. `x-controller-env: &controller-env` defines a reusable YAML anchor |
| `<<: *controller-env` | "Merge that anchor's keys here" — this is how the controller's environment is defined once |
| `${VAR:-default}` | Shell-style interpolation, resolved from the shell or from a `.env` file |
| `$$VAR` | A literal `$` passed *into* the container (used in `minio-init`, where Compose would otherwise expand it) |

### 6.3 Five Compose decisions that were made for a reason

1. **The network is pinned** with `name: bedwars_bedwars`. Compose normally prefixes network
   names with the project name, and the controller hands game containers a `--network` flag.
   A generated name would mean containers the controller starts join a network that does not
   exist. Pinning makes `BEDWARS_DOCKER_NETWORK` predictable.
2. **`BEDWARS_PROVISIONER: "DOCKER"` is explicit.** The controller defaults to `KUBERNETES`.
   On a single host with no cluster, the default would report "no capacity" forever. This is
   the single most common misconfiguration of the Compose path.
3. **The controller advertise URL is a *name*** (`http://controller:8080`), not `localhost`.
   The controller is a container; `localhost` inside it is its own loopback. Game containers
   reach it by service name on the shared network.
4. **The Docker socket is mounted** into the controller so it can start sibling containers.
   That is root-equivalent host access. It is documented in the file as local-development-only
   for exactly that reason.
5. **The game pod is behind a profile.** Bringing up MySQL, MinIO and the controller should be
   cheap and instant. One real game server is heavy (a JRE, an 80 MB server jar and a world),
   so it is opt-in with `--profile game`.

### 6.4 The `.env` file

Compose reads a file named `.env` next to the Compose file (or in the project directory) and
uses it for `${...}` interpolation. `deploy/compose/.env.example` is the template. A `.env` is
never committed — it is where credentials live.

---

## 7. Kubernetes, and the parts this project uses

### 7.1 What Kubernetes is

Kubernetes is a system for running containers across a set of machines and keeping them
running. You describe the *desired state* ("three replicas of this image, each with these
resources") and Kubernetes continuously works to make reality match. A **control plane**
(API server, scheduler, controllers) stores your objects and reconciles them; **nodes** run
the actual containers via a kubelet.

The word to internalise is **reconcile**. You do not deploy. You *declare*, and a controller
loop closes the gap. A crashed container is restarted because a Deployment's controller
notices it is not there. This is why the architecture here can destroy game servers freely:
something else is responsible for making sure the right number exists.

### 7.2 The objects this project uses

| Kind | What it is | Where it appears here |
|---|---|---|
| **Namespace** | A scope for names and access control | `bedwars` (`00-namespace.yaml`) |
| **Pod** | One or more containers scheduled together. The atom | Everywhere; game pods are created by OpenKruise |
| **Deployment** | "N identical pods, replace any that die" | controller, velocity |
| **Service** | A stable virtual IP + DNS name for a set of pods | `bedwars-controller`, `velocity` |
| **ConfigMap** | Non-secret configuration | `bedwars-s3` (endpoint, bucket, region) |
| **Secret** | Base64-encoded key/value store, used for credentials | `bedwars-s3-credentials` |
| **ServiceAccount + Role + RoleBinding** | Identity and permissions for a pod | The controller, which must scale GameServerSets |
| **NetworkPolicy** | Firewall rules between pods | Default-deny ingress, then explicit allows |
| **PodDisruptionBudget** | How many replicas may be down during voluntary disruptions | Velocity |
| **ServiceMonitor** | Tells the Prometheus Operator to scrape a service | The controller's `/metrics` |
| **StatefulSet** | Pods with stable identity and their own storage | MySQL |
| **Job/CronJob** | Run-to-completion work | Bucket initialisation |
| **Namespace-scoped CRDs** | Custom objects | `GameServerSet`, `ScaledObject`, `NodePool` |

**Service discovery is DNS.** The game pods are told to report to
`http://bedwars-controller:8080`. That resolves because a Service named `bedwars-controller`
exists in the namespace: Kubernetes runs a DNS server that maps it to the Service's cluster IP.
You never hard-code an IP address.

### 7.3 Requests, limits, and why they are mandatory here

- A **request** is what the scheduler reserves on a node: "this pod needs 1 CPU and 2 GiB to
  exist".
- A **limit** is the ceiling: exceed the memory limit and the kernel kills the container
  (`OOMKilled`); exceed the CPU limit and it is throttled.

A Minecraft server that gets no CPU does not serve players: ticks stretch, the server appears
frozen, and players time out. A server with no memory limit can consume a node and take
everything else with it. So **every pod in this project states both**, and this is enforced by
the deploy verifier, not by convention.

That said, the limits are a *planning figure*, not a promise. The number of matches that
actually fit on one server is decided by its CPU and RAM (chapter 22), which is why
`games-per-server` is configurable and reported rather than assumed.

### 7.4 Probes: how Kubernetes decides a pod is safe

| Probe | Question | Used on game pods |
|---|---|---|
| `readinessProbe` | May traffic be sent yet? | TCP connect on 25565, 20 s delay, every 5 s |
| `livenessProbe` | Is it wedged and in need of restart? | TCP connect on 25565, 60 s delay, every 15 s, 4 failures |

A **TCP probe** succeeds if a connection can be established. That is the right check for a
Minecraft server: it begins listening on `:25565` only once the world is loaded and it is
actually accepting connections. There is no trivial HTTP health endpoint to call instead, and
a process-alive check would happily keep a hung server in the pool.

The `initialDelaySeconds` values are generous on purpose. A game pod is not ready in one
second: it must start a JVM, download its libraries on first run, load a world, and enable
plugins. A liveness probe that fires too early will restart a perfectly healthy server in a
loop — a genuinely nasty failure mode, because the logs look like a crash.

### 7.5 `preStop`, `terminationGracePeriodSeconds`, and the drain

When Kubernetes wants to remove a pod it sends `SIGTERM`, waits
`terminationGracePeriodSeconds`, then sends `SIGKILL`. Two mechanisms here make that safe:

1. **`preStop: sleep 5`** — the pod keeps running for five seconds after the deletion decision.
   During that window the plugin's shutdown hook reports `DRAINING` to the controller, which
   removes the pod from the ready pool. Without this, a pod could be handed a player a
   fraction of a second before it exits.
2. **`terminationGracePeriodSeconds: 60`** — the long, second wait, which is what gives a
   match in progress time to end cleanly rather than being cut off mid-game.

Be precise about what each does. The `sleep 5` does **not** drain a match; it is a five-second
window for the *signal* to propagate. The 60 seconds is the real grace. Both are needed, and
neither is a substitute for the other.

---

## 8. OpenKruise, KEDA, and Karpenter

### 8.1 Why not a plain Deployment for game servers?

A Deployment is excellent at keeping N *identical, interchangeable* pods alive. A game server
is neither identical nor interchangeable: pod #3 is hosting a specific match with specific
players, and killing it ends that match. A Deployment would happily do exactly that during a
rollout.

**OpenKruise** is a set of Kubernetes extensions from Alibaba that includes workload types
designed for exactly this. The one used here is the **`GameServerSet`**, from the
`kruise-game` sub-project. It tracks each replica's **lifecycle state**:

```
None -> Creating -> Ready -> Allocated -> Destroyed
```

- **Ready** — the pod is up and available to be handed to someone.
- **Allocated** — the pod is serving a match and must **not** be disrupted.
- **Destroyed** — finished, awaiting removal.

That state machine is the entire reason this project can be aggressive about destroying
servers. "Do not terminate an active game" is not a rule the controller has to remember; the
allocated state is what protects the pod.

Install (the CRD comes from OpenKruise, and it is not part of Kubernetes itself):

```bash
$ helm repo add openkruise https://openkruise.io/charts
$ helm repo update
$ helm install kruise openkruise/kruise --namespace kruise-system --create-namespace
$ helm install kruise-game openkruise/kruise-game --namespace kruise-system
```

Verify the CRD exists — note the API group, which trips people up:

```bash
$ kubectl get crd gameserversets.game.kruise.io
```

It is `game.kruise.io`, not `apps.kruise.io`. (That distinction cost real debugging time in
this repository's own verification script, which is why it is called out here.)

### 8.2 KEDA: scale on a queue, not on CPU

Kubernetes' built-in autoscaler scales on CPU and memory. That is wrong for this workload: a
server with an empty queue and a server with a queue of fifty waiting players may use
identical CPU. What you want to scale on is **how many players are waiting**.

**KEDA** (Kubernetes Event-Driven Autoscaling) adds a `ScaledObject` that scales a target
based on an arbitrary metric. Here it reads a Prometheus query:

```yaml
triggers:
  - type: prometheus
    metadata:
      serverAddress: http://prometheus.monitoring:9090
      metricName: bedwars_queue_depth
      query: sum(bedwars_queue_depth{group="solo"})
      threshold: "1"
```

Decoded: "scale so that the summed queue depth for the `solo` group is about 1 per replica".
With `minReplicaCount: 0`, the fleet can scale all the way to zero when nobody is playing —
which is the point of the whole design. `pollingInterval: 10` means KEDA checks every 10
seconds; `cooldownPeriod: 120` means it waits two minutes of quiet before scaling down.

**Roles are deliberately split.** KEDA does the horizontal scaling. The controller's own
`scaleUpOne()` is a *pre-warm hint* used when the queue cannot be served immediately, and its
`ScaleDownPolicy` reclaims idle servers. Two mechanisms, one goal: never make a player wait,
never pay for an idle fleet.

### 8.3 Karpenter: nodes, not pods

KEDA scales *pods*. If the cluster has no node with room, the pods sit `Pending` forever.
**Karpenter** provisions nodes in response to unschedulable pods, choosing instance types and
spot capacity. Its `NodePool` is cluster-scoped and is usually owned by whoever owns the
cluster, which is why the example in `11-keda-scaledobject.yaml` says so. It appears in this
repository as a documented, optional layer — not something the application configures.

At the game-server memory footprint (2 GiB requested, 4 GiB limit), a handful of pods fills a
small node quickly. On a managed cluster without Karpenter or the cluster autoscaler, raising
`maxServers` will simply stop doing anything once nodes are full.

---

## 9. Velocity, the lobby, and mc-router: how players reach a match

### 9.1 The problem

Game servers are ephemeral and have no stable address. A player cannot be asked to type
`bedwars-game-7.bedwars.svc.cluster.local:25565`, and it may not exist thirty seconds later.
Something must sit in front, hold a stable address, and forward each player to the server the
controller picked for them.

### 9.2 Velocity

**Velocity** is a modern Minecraft proxy. It accepts player connections, and — unlike a
vanilla server — can transfer a connected player to a *different* backend server without them
disconnecting. That transfer is what makes "the lobby queued you and now you are in a match"
feel seamless.

This repository ships a Velocity plugin (`BedwarsRecoded-Velocity`) with exactly three jobs:

1. **Land every connecting player on the lobby.** On login the plugin places the player on the
   registered server named by `LOBBY_SERVER`. It does *not* put them into a match. (An earlier
   version did, which meant anyone who logged in while the fleet was scaled to zero had no
   server to be placed on at all.)
2. **Act on a matchmaking request from the lobby.** The lobby cannot move a player off itself —
   only the proxy can — so the lobby sends `bedwars:queue`, and the plugin asks the controller,
   then transfers the player onto the pod it names.
3. **Put finished players back in the lobby** when a game server sends `bedwars:return`.

Two things are deliberately *not* in the proxy: game logic, and a fixed list of servers. Velocity
asks; it does not decide. Game pods are ephemeral and cannot be listed in advance, so the proxy
registers each one the first time the controller names it (see 9.4).

### 9.3 mc-router

**mc-router** is a much smaller tool that routes a *TCP connection* to a backend based on the
hostname the client used. It exists for two reasons:

- Direct connections (`play.example.com` in the client's server list) must land somewhere.
- A single stable address is needed for players who are not going through the Velocity hop.

The mapping is configuration: `*.bedwars.local=velocity:25565` means "anything ending in that
domain goes to Velocity". Both tools take player traffic; mc-router defers to Velocity, and
Velocity defers to the controller. The chain is: **player -> mc-router -> Velocity ->
controller decides -> game pod**.

### 9.4 The lobby: a real server role

The lobby is **not** a metaphor for the proxy. It is a real, self-standing Spigot server — the
same image as a game server, started with `BEDWARS_ROLE=LOBBY` — whose entire job is to hold the
players who have just arrived through the proxy and let them choose what to play.

That is the shape every large network uses (Hypixel, Mineplex): you connect to one address, you
land in a hub with NPCs and signs, you click one, and a moment later you are in a match. When the
match ends you are returned to the hub, ready to queue again. The hub is a *server*, not a menu,
because the queue must never be able to destroy a running game, and because a hub is where you
put the things players look at while they wait.

#### 9.4.1 The player's journey, component by component

| # | What happens | Which component does it |
|---|---|---|
| 1 | The player connects to the network address | **Velocity** (published on 25565) |
| 2 | Velocity places them on the server named by `try` in `velocity.toml` — `lobby` | Velocity |
| 3 | They arrive in the hub world, at its spawn, in adventure mode, unharmed | **Lobby** (`LobbyProtectionListener`) |
| 4 | They right-click a sign, or an NPC named `[bedwars]`, or type `/bw queue` | Lobby (`LobbyQueueListener`) |
| 5 | The lobby sends a plugin message on `bedwars:queue` carrying the player's UUID | Lobby (`ProxyChannel`) |
| 6 | Velocity reads it and POSTs the player to the controller's `/lobby/queue` | Velocity (`ControllerClient`) |
| 7 | The controller answers with a pod address and member list, or `retryAfterMillis` | **Controller** |
| 8 | Velocity registers that pod with itself (if new) and transfers the player onto it | Velocity |
| 9 | The match runs on the pod; when it ends the pod asks for its players back | **Game pod** |
| 10 | Velocity moves those players to the lobby — the loop closes | Velocity |

Nothing in that table is a metaphor: steps 2, 8 and 10 are real `createConnectionRequest`
transfers, and steps 5 and 9 are real plugin messages.

#### 9.4.2 Why one image, two roles

The lobby is the *same image* as a game server (`bedwars-spigot:1.0.0`) run with a different
role. One codebase, one build, two jobs:

| | `GAME` (default) | `LOBBY` |
|---|---|---|
| Hosts BedWars matches | yes — one or more, per `games-per-server` | never |
| Reports capacity to the controller | yes, on every heartbeat | **never** |
| Appears in the controller's registry | yes | no |
| Staged world | the arena template (`Glacier`, or a Slime world) | the hub world (`lobby`) |
| Dragon, shop, stats, scoreboard, arena code | all wired | none of it loaded |
| Extra listeners | game/join/shop/join-sign | hub protection + sign/NPC queue |
| `/bw` subcommands | the full set | `queue`, `join`, `status`, `help` |

The single most important row is the second one. **A lobby that reported capacity would be
handed players as if it could host a match**, because the controller's registry means exactly
"this server can host a game". The role check in the plugin's bootstrap exits before the
reporter is even constructed, so a lobby has no code path that can enrol it as a game server.
That is why it is a role switch and not a configuration convention.

`ServerRole.parse` maps anything unrecognised to `GAME`. Failing towards the old behaviour is
deliberate: a typo in `BEDWARS_ROLE` must not silently turn a game pod into a hub.

#### 9.4.3 The transfer protocol: plugin messages

A backend server cannot move a player to another server — the connection belongs to the proxy.
So the backends *ask*. Both directions use a Minecraft **plugin message** on a namespaced
channel, which is the standard, in-band way for a backend to talk to a proxy:

| Channel | Sent by | Payload | Meaning |
|---|---|---|---|
| `bedwars:queue` | lobby | one player UUID, UTF-8 | "put this player into a match" |
| `bedwars:return` | game pod | comma-separated UUIDs, or empty | "send these players back to the lobby" |

The payloads are plain UTF-8 text rather than a packed binary format so the contract stays
readable in a packet capture or a debug log. An **empty** `bedwars:return` payload means
"everyone on the server that sent this"; a pod hosting several concurrent matches sends the
explicit list so it moves only the players whose match actually ended.

Velocity must have `bungee-plugin-message-channel = true` (it does, in the shipped
`velocity.toml`), and each backend registers the channels as *outgoing* when the plugin enables.

Why not RCON? RCON is an admin console, not a player-routing API: it would mean a second set of
credentials on every pod, a TCP connection to the proxy per transfer, and no natural association
between the message and the player it is about. The plugin-message channel rides the connection
the player already has.

#### 9.4.4 How a lobby gets its world

The lobby world is staged **before the JVM starts**, exactly like an arena, because a Minecraft
server reads its main world at boot and a plugin cannot swap it afterwards. The container's
entrypoint (`deploy/docker/entrypoint.sh`) copies `BEDWARS_TEMPLATE_NAME` out of
`BEDWARS_TEMPLATE_LOCAL_ROOT` (or pulls it from S3) into `/server/world`.

Two deliberate defaults apply to the lobby:

- **No hub map is baked into the image.** If no template named `lobby` is present, the entrypoint
  logs that and the server generates a world. To use your own hub, drop a world directory at
  `deploy/templates/lobby/` (a `level.dat` and region files) and it is staged instead. Nothing
  about the lobby requires a specific map.
- **`BEDWARS_LEVEL_TYPE=flat`.** This is written into `server.properties` by the entrypoint and
  selects a superflat generator: no terrain generation to wait for, and a predictable surface for
  a hub build. Leave it unset on a game pod — there the staged arena decides the world.

#### 9.4.5 Addressability: why `server-id` had to change

The controller hands the proxy a **pod address**, and the proxy dials it. That makes the pod's
identity a networking detail, not just a label:

- The plugin's `server-id` must therefore be a name that **resolves**. It used to default to
  `pod-${HOSTNAME}`, which matches no DNS record in either runtime — the proxy would have been
  told to connect to a host called `pod-bedwars-solo-0`, which does not exist. It is now
  `${HOSTNAME}`, the bare name.
- **In Kubernetes** the pod name resolves through the GameServerSet's headless Service as
  `<pod>.<gameserverset>.<namespace>.svc.cluster.local`, so the manifest supplies the rest via
  `POD_ADDRESS_SUFFIX: ".bedwars-solo.bedwars.svc.cluster.local"`.
- **In Docker** a container name resolves on the shared network by itself, so
  `POD_ADDRESS_SUFFIX` is empty and the Docker provisioner passes each container its own name as
  `BEDWARS_SERVER_ID`.

The proxy registers a pod with itself the first time it sees the address, then reuses it. This is
why ephemeral game servers do not appear in `velocity.toml`: they cannot, because they do not
exist until the controller creates them.

#### 9.4.6 What the lobby blocks, and why

`LobbyProtectionListener` makes the hub behave like a hub, and every rule is scoped to the lobby
world so a server that also hosts matches keeps normal gameplay where it belongs:

| Event | Action | Why |
|---|---|---|
| Block break / place | cancelled, unless the player has `bedwars.lobby.build` | a waiting crowd should not be able to edit (or grief) the hub build, but staff must be able to build it |
| Item drop | cancelled | no item litter in the hub |
| Any damage to a player | cancelled | a hub must never kill anyone — including fall damage and fire |
| Hunger | cancelled | no starvation in a hub |
| Weather turning to rain | cancelled | cosmetic, and a sunny hub looks deliberate |
| Creature spawn | cancelled **unless deliberate** | no night ambushes or lag |

Two of those rows need explanation, because both are the difference between a lobby that works
and one that cannot be set up at all.

**The build exemption.** The protection rules apply to players; the hub still has to be *built*.
A queue sign is a block, so if placement were refused unconditionally the sign could never be put
down on a running server. Staff who hold `bedwars.lobby.build` (operators have it by default —
the node is declared in `plugin.yml`, so the default actually applies) keep normal build rights;
everyone else is refused.

**The spawn exemption.** NPC-based matchmaking is the primary way a Hypixel-style lobby is used,
so an admin placing a queue NPC with a spawn egg, `/summon`, or Citizens must not have it deleted
the instant it appears. The listener therefore allows spawns whose reason is `CUSTOM`, `PLUGIN`,
`COMMAND`, `SPAWNER_EGG`, `DISPENSE_EGG`, `BUILD_*`, `BREEDING` or `CURED`, and cancels the
world's own ambient spawning.

#### 9.4.7 Configuring a lobby

Three keys in `config.yml`, each with an environment override, plus the proxy's own settings:

| Where | Key | Env override | Default | Meaning |
|---|---|---|---|---|
| plugin | `server.role` | `BEDWARS_ROLE` | `GAME` | `LOBBY` makes this process the hub |
| plugin | `lobby.world` | `BEDWARS_LOBBY_WORLD` | `lobby` | the world the protection listener treats as the hub |
| plugin | `lobby.return-to-lobby` | `BEDWARS_RETURN_TO_LOBBY` | `true` | ask the proxy to return finished matches to the lobby |
| container | — | `BEDWARS_TEMPLATE_NAME` | `Glacier` | set to `lobby` on the hub, so the hub world is staged |
| container | — | `BEDWARS_LEVEL_TYPE` | *(unset)* | `flat` for a hub: no terrain generation |
| proxy | `velocity.toml` `[servers]` | — | — | `lobby = "lobby:25565"` — the name players land on |
| proxy | `velocity.toml` `try` | — | — | `["lobby"]` — where a connecting player is placed |
| proxy plugin | — | `LOBBY_SERVER` | `lobby` | which registered server is the hub |
| proxy plugin | — | `POD_ADDRESS_SUFFIX` | `""` | how a pod name becomes a dialable address |
| proxy plugin | — | `QUEUE_RETRY_ATTEMPTS` | `12` | how many times to ask the controller for a slot |
| proxy plugin | — | `QUEUE_RETRY_MILLIS` | `1000` | floor for the controller's `retryAfterMillis` backoff |
| proxy plugin | — | `BEDWARS_API_TOKEN` | `""` | the controller's shared secret; without it every enqueue is refused `401` |
| proxy container | — | `BEDWARS_OFFLINE_MODE` | `false` | **testing only**: skips the Mojang check, so an unauthenticated client can join |

`LOBBY_SERVER` and the `[servers]` entry must agree, or the plugin logs a warning at startup
that the lobby it was told about is not registered with the proxy — the single most common way to
get a network where "nobody can join".

`BEDWARS_API_TOKEN` has to match the controller's value. It is the one setting where a mismatch
is silent: the proxy starts, players see the hub, and every attempt to queue is refused by the
controller. The proxy prints `controller_auth=token` or `controller_auth=none` on its
`proxy initialised` line, so the state is visible at a glance (chapter 21.2).

#### 9.4.8 Testing with an offline client (`BEDWARS_OFFLINE_MODE`)

Velocity verifies every player against Mojang (`online-mode = true` in `deploy/docker/velocity.toml`)
— normally the only authentication in the chain, because the game servers behind it trust the
proxy. That makes it impossible to connect with a client that has no Mojang session, which is
awkward when you are testing on a machine with no account configured, or with a client build that
cannot log in.

`BEDWARS_OFFLINE_MODE=true` removes that step for the proxy:

```bash
# Docker
BEDWARS_OFFLINE_MODE=true ./install.sh          # or edit deploy/compose/.env and restart the proxy

# Kubernetes (Helm)
helm upgrade bedwars deploy/helm/bedwars -n bedwars --reuse-values --set velocity.offlineMode=true
```

The proxy image's entrypoint rewrites `online-mode = false` in `velocity.toml` at start, and says
so in its log:

```
[bedwars] OFFLINE MODE: the proxy will NOT verify players with Mojang.
[bedwars] velocity.toml online-mode=false
```

Three things to understand before you use it:

- **It is a testing switch, not a deployment mode.** With it on, anyone who can reach the proxy
  port can join under any name, including a name that is not theirs. Never enable it on a server
  the public can reach.
- **The game servers are already offline-mode.** `server.properties` inside the game image
  declares `online-mode=false` and the pods trust the proxy's forwarded identity (§21.7 item 6),
  so this switch only changes the proxy. Nothing else needs editing.
- **Undo it by setting the variable back to `false`** (or removing it) and restarting the proxy.
  Nothing is written into the image: the `velocity.toml` inside a running container is rewritten
  at every start, so the switch is not sticky.

`install.sh` asks about it ("Run the proxy in OFFLINE MODE?"), defaults to no, and the
installer's verification step reads the proxy log back to confirm which mode is actually running
rather than trusting the setting.

#### 9.4.9 The lobby in the deployments

Both deployment paths ship a lobby, because a network without a hub cannot place a player:

- **Compose** — `deploy/compose/docker-compose.yml` declares `lobby` and `velocity` behind the
  `network` profile. The lobby publishes 25566 and the game pod 25567 purely for debugging; the
  proxy publishes 25565 and is the only real entry point.
- **Kubernetes** — `deploy/k8s/25-lobby.yaml` is a plain `Deployment` + `Service` (not a
  GameServerSet: a hub is permanent, not ephemeral), with its own `NetworkPolicy` so only
  mc-router and Velocity may connect. The Helm chart's `lobby:` values block renders the same
  pair from `deploy/helm/bedwars/templates/lobby.yaml`.

#### 9.4.10 Verifying the loop

Each step of the chain can be checked on a live stack:

```bash
# 1. The lobby booted as a hub, not as a match host. These two lines are the proof.
$ docker compose logs lobby | grep -E "bedwars_setup|lobby_ready"
[entrypoint] no local template 'lobby' - the server will generate a world
[lobby] bedwars_setup role=LOBBY lobby_world=lobby server_id=lobby return_to_lobby=false \
        controller_reporting=disabled (a lobby must never be routed players as a match host)
[lobby] lobby_ready world=lobby - players arrive from the proxy; signs and NPCs named ...

# 2. The proxy knows where to put a connecting player, and can dial a pod by name.
$ docker compose logs velocity | grep -E "proxy initialised|Registering game pod"

# 3. The lobby is NOT in the controller's registry. This is the assertion that matters:
#    a registered server means "this can host a match".
$ curl -s localhost:8080/infra | python3 -c "import json,sys; print(json.load(sys.stdin)['registeredServers'])"
0
```

If step 3 returns anything but `0` while only the lobby and the proxy are up, the lobby is
misconfigured as a game server and will be handed players it cannot serve.

#### 9.4.11 What a waiting player sees

A player who queued was told "Searching for a match" once and then nothing at all — indistinguishable
from a broken network, which is exactly how it was reported. While a request is in flight the proxy
polls the controller and refreshes an action-bar line above the hotbar:

```
Searching for a match  |  3 in queue  |  2 servers  |  4 match slots free
```

Every number comes from the controller (`GET /lobby/arena-status`), so it is the real queue depth and
the real free capacity, not a guess. It refreshes every two seconds and stops the moment the player is
placed, gives up, leaves, or disconnects. Transitions are announced in chat:

| Moment | Message |
|---|---|
| Queued | `Searching for a solo match - you will be moved automatically when a game frees up.` |
| Capacity found | `Match found - sending you to bedwars-game-1...` |
| Fleet busy, a server booting | the action bar keeps counting, and adds `no free slot yet - starting a server` |
| Gave up (default: ~2 minutes of retries) | `No match was available after a few minutes - please try again.` |

`/bw leave` in the lobby cancels the search **in both places**: the lobby forgets the request and tells
the proxy, which drops the entry on the controller. Leaving locally only - which is what used to
happen - could still move a player into a match they had just cancelled.

Patience is `QUEUE_RETRY_ATTEMPTS` on the proxy (default 30, layered on the controller's own backoff).
It is deliberately generous: waiting a minute for a server to boot is normal.

#### 9.4.12 Operator views without host access (`/bwadmin`)

Diagnosing a stuck queue by reading `docker ps` on the host is not something an admin can do from
inside the game - and the failure they need to tell apart is "nothing happens", for which every cause
looks identical. `/bwadmin` (aliases `/bwa`, `/bwnet`; permission `bedwars.admin`) reads the
controller and answers from inside the world:

| Command | Answers |
|---|---|
| `/bwadmin status` | the fleet, its capacity, the queue, **and a diagnosis line** |
| `/bwadmin servers` | every registered server: arena group, free/capacity slots, idle or hosting |
| `/bwadmin queue` | who is waiting, per arena group |
| `/bwadmin infra` | the controller's own description of how it provisions |

```
[Bedwars] Network status  (auth token)
  provisioner  DOCKER  docker prefix=bedwars-game image=bedwars-spigot:1.0.0 servers[0..2] gamesPerServer=2
  servers  provisioned 2 | registered 2 | free slots 2
  limits  min 0, max 2, 2 matches per server -> 4 slots total
  queue  3 waiting | solo 3
  solo  waiting 3, servers 2, free 2
[Bedwars] 3 waiting with 2 free slot(s): dispatch should be immediate. If it is not, the queue is stuck.
```

That last line is the point of the command. The same numbers mean different things, so it says which:

- **waiting > 0 with free slots** - dispatch should be immediate; if it is not, the queue is stuck.
- **waiting > 0, no free slot, provisioned < registered** - a server is on its way out.
- **waiting > 0, no free slot, fleet at maximum** - raise the maximum or shorten matches.
- **waiting > 0, no free slot, below maximum** - the controller should be starting another server.

`/bw status` in the lobby shows a one-line version of the same figures to any player, so "is this queue
real?" no longer needs an operator at all. The endpoints behind both (`/servers`, `/queue/depth`,
`/lobby/arena-status`, `/infra`) are read-only, and the shared secret is never printed.

### 9.5 What is deliberately absent

The proxy holds **no game logic and no server list**, and the lobby holds **no matchmaking
decision**. The division is: the lobby collects intent, the proxy owns the connection to the
controller, and the controller owns the decision. A lobby restart therefore cannot destroy
anyone's match — it only empties the hub for as long as it takes to come back.

The cost is that anything you want in a lobby must be driven from data. Live player counts on a
sign, or a "26 players in matches" hologram, are not pushed into the hub; they are drawn from the
controller's `/lobby/arena-status`:

```json
{"solo": {"freeSlots": 25, "queued": 0}}
```

The lobby also has no NPC plugin dependency: a queue NPC is any entity whose custom name is
`[bedwars]` (or `[bw]` / `[bwqueue]`), and a queue sign is any sign whose first line is one of
those. A real network would use Citizens or ModelEngine for the skin; the trigger contract — the
part the plugin defines — is the same either way.

---

## 10. The data layer: MySQL, object storage, and Slime worlds

### 10.1 MySQL and HikariCP

Stats must outlive the pod that produced them, so they live in a shared MySQL and are written
at match end.

The plugin talks to MySQL through **JDBC** (Java's database API) and a **connection pool**.
A pool matters: opening a TCP connection and authenticating is expensive, and doing it per
statement would be wasteful and slow. **HikariCP** is a small, fast pool; `pool-size: 4` in
`config.yml` means at most four simultaneous connections per game server.

`HikariCP` and the MySQL driver are **not bundled in the plugin jar**. They are declared in
`plugin.yml`:

```yaml
libraries:
  - "com.zaxxer:HikariCP:6.3.0"
  - "com.mysql:mysql-connector-j:9.3.0"
```

The server downloads them at startup and adds them to the plugin's classpath. This is a
Spigot 26.x / Paper feature, and it is a direct consequence of the 4 MB jar constraint — those
two libraries alone would blow it.

**If the server cannot provide libraries**, the plugin degrades gracefully: persistence is
disabled and the plugin logs that fact. It does not crash. A BedWars match that runs without
stats is a far better failure than a server that will not start.

### 10.2 Migrations

Schema changes are **forward-only** and **idempotent**, applied at startup by
`SchemaMigrator` and tracked in a `schema_version` table. Each migration runs in a
transaction; it is recorded only if every statement succeeded. Re-running is a no-op.

| Rule | Why |
|---|---|
| Append with the **next** version number | Ordering is enforced by `MigrationsTest` |
| **Never** edit an applied migration | Deployed databases will not re-run it, so your edit silently diverges from theirs |
| Prefer `IF NOT EXISTS` guards | A partially-applied environment can recover |
| One logical change per migration | Reviewable, and rollback is "write the opposite migration" |
| Additive only | Dropping a column requires a deprecation migration first |

There are no down-migrations by design. To undo something you add a new migration that
reverses it, so every environment always moves in one direction. Version ranges are reserved:
1–999 for core schema, 1000+ for component extensions.

### 10.3 Object storage and world templates

Arena worlds are **versioned objects** in S3-compatible storage, addressed as
`templates/<name>/<version>.zip`:

```
templates/Glacier/1.0.0.zip
```

The versioning is not decoration. It means a map change can be **canaried**: publish
`Glacier/1.1.0.zip`, point one deployment at it, and leave everyone else on `1.0.0`. It also
means a pod always knows exactly which map it is running, which it reports to the controller.

**MinIO** is used as the in-cluster S3-compatible store so that nothing here requires an AWS
account. The API is the same; in production point `endpoint` at real S3, Ceph, or any other
S3-compatible service. Authentication uses **AWS Signature Version 4** when credentials are
configured, and an empty `access-key` means "public bucket, do not sign".

### 10.4 Slime worlds and AdvancedSlimePaper

Copying a full world directory for every match is expensive in disk I/O and space — a BedWars
arena is not small. **Slime Region Format** (SRF) stores a world as a single compressed file
that can be loaded into memory and instantiated as a new world cheaply. **AdvancedSlimePaper
(ASP)** is the server software that can load them.

The plugin uses the ASP API (`provided` scope — supplied by the server, never shaded). World
templates in production are Slime files; the `LOCAL` source exists as a documented,
non-production fallback that copies a plain directory.

### 10.5 How a world actually reaches a running server — and why this is subtle

A Minecraft server reads its main world **during startup**, before any plugin is enabled.
`server.properties` sets `level-name=world`, and by the time a plugin's `onEnable` runs, that
world is already loaded. **A plugin therefore cannot choose the main world.** Anything that
prepares a world has to run before the JVM does.

That is why this project stages templates in the **entrypoint script** (`deploy/docker/entrypoint.sh`),
not in the plugin:

```
[entrypoint] staging local template 'Glacier' from /templates/Glacier
[entrypoint] arena ready: 12M world at /server/world
```

The entrypoint's logic, in order:

1. If `BEDWARS_TEMPLATE_FORCE` is not `true` and a world already exists — do nothing.
2. Otherwise, per `BEDWARS_TEMPLATE_SOURCE`: `LOCAL` copies `/templates/<name>` into
   `/server/world`; `S3` downloads `templates/<name>/<version>.zip` and unzips it.
3. Remove `session.lock`. A world that was ever opened elsewhere carries a lock file, and a
   server that finds one may refuse to start or may corrupt the world.
4. If the archive has a nested top-level directory, **flatten** it. Zips are made by humans,
   and half of them contain `Glacier/level.dat` instead of `level.dat`.
5. Fail **soft**: if any of this goes wrong, log why and start anyway on a generated world. A
   playable server with the wrong map beats a pod in `CrashLoopBackOff`.

The plugin's own `GameWorldService` still exists and can copy a fresh world **per match** at
runtime — that is the multi-game path, and it is opt-in (`template.enabled` in the plugin
config, or `arena.templateEnabled` in the Helm chart). The two mechanisms are complementary:
the entrypoint prepares the *first* world, the plugin can prepare *subsequent* ones.

---

# Part III — Getting it running

## 11. Requirements

Two lists: the **minimum** to get the software running and prove it works, and the
**recommended** for a deployment that behaves like production. Every number is followed by
*why*, because a requirement without a reason is a rule you cannot safely deviate from.

### 11.1 Minimum requirements

| Component | Minimum | Why this number |
|---|---|---|
| **OS** | 64-bit Linux, or macOS 13+ | The tooling is POSIX shell, Python 3 and Docker, and the containers are Linux. A POSIX host is the requirement; nothing here targets a non-POSIX shell. |
| **CPU** | 2 cores | Maven's reactor and one JVM each want a core. Below two, builds crawl and a single game server starves its own tick loop. |
| **RAM** | 6 GiB | The build (Maven + tests) peaks around 2 GiB. A game server with a real arena needs ~1.5 GiB. One controller is 256 MiB. Below 6 GiB you cannot run a build and a server at once. |
| **Disk** | 12 GiB free | Base images (~1.5 GiB), a built game image (~705 MB), a server jar (~82 MB), Maven's dependency cache (~1 GiB), plus build output and logs. |
| **Java** | JDK 25 (not 21) | `maven.compiler.release` is 25; older JDKs refuse the bytecode. See 4.1. |
| **Maven 3.9+** | yes | The reactor uses features of the 3.9 line. The Docker images pin `maven:3.9-eclipse-temurin-25`. |
| **Docker Engine 20.10+** | for Path A | Multi-stage builds and Compose v2 syntax. |
| **Docker Compose v2** | for Path A | Invoked as `docker compose` (a plugin), not the old `docker-compose` binary. |
| **Python 3.9+** | for the verifiers | `deploy/verify_deploy.py` needs `PyYAML`. Also used by `resolve-server-jar.sh` to query the PaperMC API. |
| **Git** | yes | Building Spigot with BuildTools clones repositories. Also needed for `git` itself, obviously. |

If you only intend to run the **Compose path**, you need: Docker, Java 25, Maven, Python 3,
~6 GiB RAM and ~12 GiB disk. Kubernetes is not required.

### 11.2 Kubernetes-path requirements

| Component | Minimum | Why |
|---|---|---|
| A cluster | any conformant one | minikube locally; EKS/GKE/AKS/K3s in production |
| `kubectl` | matching the cluster (within one minor version) | |
| `helm` 3.12+ | yes | The chart and the OpenKruise install |
| **OpenKruise + kruise-game** | yes, for game servers | Provides the `GameServerSet` CRD. Without it, install with `gameServerSet.enabled=false` for the persistent tier only |
| **KEDA** | recommended | Horizontal scaling on queue depth. Without it the fleet is whatever `replicas` says |
| **Prometheus** | recommended | KEDA's Prometheus trigger reads the controller's metrics |
| Cluster nodes | ≥ 4 vCPU, ≥ 8 GiB per game node | A game pod requests 1 CPU / 2 GiB. Two pods plus system overhead is the practical floor per node |
| **A default StorageClass** | for MySQL/MinIO | Otherwise PersistentVolumeClaims stay `Pending` |

For a **local** cluster, minikube needs the raised limits in chapter 16. The default 2 GiB
allocation will not hold an API server, MySQL, and a Spigot pod at the same time — that is not
a theoretical warning; it is what happens by default.

### 11.3 Verified-versus-next-tier

Two configurations are worth naming explicitly, because they are the ones this repository has
actually been exercised on:

| | Verified | Recommended for real use |
|---|---|---|
| Game pod RAM | 1.5 GiB limit, `MaxRAMPercentage=55` | 2 GiB request / 4 GiB limit, `MaxRAMPercentage=65` |
| Game pod CPU | 500m request / 1 limit | 1 request / 2 limit |
| Cluster | minikube, 3 vCPU / 4 GiB | Managed, ≥4 vCPU / 8 GiB per game node |
| MySQL | In-cluster, 1 GiB volume | Managed MySQL, or in-cluster with backup |
| Object storage | MinIO disabled on the sandbox host (registry auth) | MinIO with a volume, or real S3 |

The verified column is what the manifests and `values-minikube.yaml` use; the recommended
column is what `deploy/k8s/` and `values.yaml` default to. If you are deploying for real, use
the recommended column and do not copy the minikube overrides.

---

## 12. The repository, file by file

```
BedwarsRecoded/
├── pom.xml                          the reactor: modules, versions, the 4 MB jar gate
├── README.md                        the front page
├── server-jars/                     DROP A SERVER JAR HERE to skip compiling Spigot
│   ├── README.md                    what this folder is for
│   └── .gitkeep                     keeps the folder in git while ignoring *.jar
├── BedwarsRecoded-API/              interfaces, records, events. No platform code
├── BedwarsRecoded-Core/             the game: aggregates, arenas, dragons, ranking, persistence
├── BedwarsRecoded-Spigot/           Bukkit adapters, commands, config binding
│   ├── .../spigot/lobby/            hub protection + the sign/NPC queue (LOBBY role)
│   ├── .../spigot/proxy/            the plugin-message channel the backends talk over
│   └── src/main/resources/
│       ├── plugin.yml               plugin metadata + the runtime `libraries` list
│       ├── config.yml               the plugin's configuration
│       └── arena.yml                arena geometry, generators and the shop
├── BedwarsRecoded-Velocity/         the proxy-side plugin: lobby first, then a match
│   └── .../velocity/                login routing, the queue handshake, pod registration
├── BedwarsRecoded-Controller/       matchmaking, capacity, provisioning, HTTP control plane
├── deploy/
│   ├── verify.sh                    tests + the jar size gate
│   ├── verify_deploy.py             structural checks over every asset (118 checks)
│   ├── verify_k8s.sh                renders Helm + Kustomize and schema-validates
│   ├── docker/
│   │   ├── gameserver.Dockerfile    the game-server image (engine is a build arg)
│   │   ├── controller.Dockerfile    the controller image
│   │   ├── velocity.Dockerfile      the proxy image
│   │   ├── velocity.toml            the proxy config: [servers] + try = ["lobby"]
│   │   ├── spigot.yml               bungeecord forwarding - required behind a proxy
│   │   ├── entrypoint.sh            stages the world, then execs the server
│   │   └── resolve-server-jar.sh    supplied jar -> Paper download -> Spigot compile
│   ├── compose/
│   │   ├── docker-compose.yml       the single-host stack
│   │   └── .env.example             variables the Compose file interpolates
│   ├── k8s/                         plain manifests, applied with `kubectl apply -k`
│   ├── helm/bedwars/                the chart (values.yaml + templates/)
│   ├── helm/values-minikube.yaml    overrides for a small local cluster
│   ├── templates/Glacier/           the arena map baked into the game image
│   ├── templates/lobby/             (optional) your hub map, staged in LOBBY role
│   └── tools/                       rcon.py, server-status.py, join-server.sh, jar tests
└── docs/                            this manual and the focused documents beside it
```

The `deploy/k8s/` files are numbered in application order, and the numbers are meaningful:

| File | Contents |
|---|---|
| `00-namespace.yaml` | The `bedwars` namespace |
| `10-gameserverset.yaml` | The `GameServerSet` (starts at `replicas: 0`) |
| `11-keda-scaledobject.yaml` | The KEDA `ScaledObject` + an example Karpenter `NodePool` |
| `12-servicemonitor.yaml` | Prometheus Operator `ServiceMonitor` |
| `13-networkpolicy.yaml` | Default-deny ingress + explicit allows, for game pods and the lobby |
| `14-pdb.yaml` | PodDisruptionBudget for the persistent tier |
| `20-mc-router.yaml` | The hostname router |
| `25-lobby.yaml` | The lobby: Deployment + Service for the hub players land on |
| `30-velocity.yaml` | Velocity Deployment + Service |
| `40-controller.yaml` | Controller Deployment, Service, ServiceAccount, Role, RoleBinding |
| `50-s3-config.yaml` | The `bedwars-s3` ConfigMap and `bedwars-s3-credentials` Secret |
| `51-mysql.yaml` | MySQL StatefulSet + Service |
| `52-minio.yaml` | MinIO Deployment + Service |
| `kustomization.yaml` | Ties the above together for `kubectl apply -k` |

**Ordering caveat worth knowing:** `kubectl apply -k deploy/k8s/` applies all of them at once,
and Kubernetes will not fail if the `GameServerSet` CRD is missing — it will report that it has
no matching kind and move on. Install OpenKruise *first*, then apply. If game pods never
appear, check `kubectl get crd gameserversets.game.kruise.io` before anything else.

### 12.1 The focused documents

This manual is the whole picture. The smaller documents beside it remain useful as quick
references, and they are not duplicates:

| Document | Use it for |
|---|---|
| `docs/SETUP.md` | The short version of chapters 13-16 |
| `docs/ARCHITECTURE.md` | The model, module boundaries and the controller protocol |
| `docs/API.md` | The public API: DTOs, events, services, HTTP endpoints |
| `docs/CONCEPTS.md` | Containers and Kubernetes in one page each, plus a glossary |
| `docs/DEPLOYMENT.md` | Deployment specifics and environment handling |
| `docs/PROVISIONING.md` | The provisioning abstraction and controller security |
| `docs/MIGRATIONS.md` | The migration rules |
| `docs/AUDIT-RECODED.md` | What was audited and repaired, and how it was verified |

---

## 13. Build it, command by command

This chapter is deliberately explicit. Every command is followed by what it does and what you
should see. Run them from the repository root.

### 13.1 Confirm the tools exist

```bash
$ java -version
$ mvn -version
$ docker version
$ git --version
```

What you are checking:

- `java -version` prints **25**. If it prints 21, stop here and fix it (chapter 4.2).
- `mvn -version` prints a Java version too — **the JDK Maven is using**, which is not
  necessarily the one on your `PATH`. Read that line; it is the frequent culprit when a build
  fails on a machine where `java -version` looked correct.
- `docker version` prints both a Client and a Server block. No Server block means the daemon
  is down.
- `git --version` matters because the Spigot build path clones repositories from
  `hub.spigotmc.org`. It is also the only way to obtain the source of this project.

### 13.2 Select the right JDK

```bash
$ export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
$ export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 # Debian/Ubuntu
```

`export` sets the variable for the current shell only. Re-run it in every new terminal, or add
it to your shell profile. The command substitution `$(...)` runs the helper and substitutes its
output, so `JAVA_HOME` becomes the JDK 25 home directory that macOS knows about.

### 13.3 Build and test

```bash
$ mvn clean install
```

Reading the output:

- `clean` deletes `target/`, so this is a genuine from-scratch build.
- `install` compiles, runs tests, packages, and installs into `~/.m2/repository`.
- Each module prints `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`.

The expected totals:

| Module | Tests |
|---|---|
| BedwarsRecoded-API | 5 |
| BedwarsRecoded-Core | 95 |
| BedwarsRecoded-Spigot | 28 |
| BedwarsRecoded-Controller | 61 |
| **Total** | **189** |

`Skipped` must be 0. A skipped test is not a passing test: the Docker integration test once
silently skipped because a `docker version` probe timed out at 15 seconds under load. It is a
45-second timeout now, and the number is logged.

To iterate quickly on code without paying for tests:

```bash
$ mvn clean install -DskipTests
```

### 13.4 The one-command check

```bash
$ ./deploy/verify.sh
```

This runs `mvn -q verify`, then `deploy/verify_deploy.py`, then the JAR size gate, and prints
`ALL VERIFICATIONS PASSED` only if all three succeed. Its output includes:

```
DEPLOY VERIFY: OK (119 checks passed)
    BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-1.0.0-SNAPSHOT.jar = 284366 bytes (budget 4194304)
```

That byte count is the 4 MB constraint, enforced on every run. The size gate uses `wc -c`
rather than `stat`, because GNU `stat -c` and BSD `stat -f` disagree and a gate that errors out
on one platform is worse than no gate at all.

### 13.5 What just got built

```
BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-1.0.0-SNAPSHOT.jar   284,366 bytes  the plugin
BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-*.jar        the controller
BedwarsRecoded-Velocity/target/BedwarsRecoded-Velocity-*.jar            the proxy plugin
```

The plugin jar is what you would drop into `plugins/` on any Spigot 26.x server. Reading its
manifest confirms the API target:

```bash
$ unzip -p BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-1.0.0-SNAPSHOT.jar plugin.yml | head -8
```

`api-version: '26.1'` is the **lowest** API the plugin will load on, while the code is compiled
against 26.3. That combination is intentional: the plugin loads on any 26.x Spigot server, not
only the exact patch version it was built against.

### 13.6 Render and validate everything without a cluster (optional, recommended)

```bash
$ ./deploy/verify_k8s.sh
```

This renders the Helm chart and the Kustomize base into YAML and schema-validates the result
with `kubeconform`. Expected:

```
Summary: 58 resources found in 2 files - Valid: 51, Invalid: 0, Errors: 0, Skipped: 7
K8S VERIFY: OK
```

The seven skipped resources are the custom kinds (`GameServerSet`, `ScaledObject`, `NodePool`)
that kubeconform has no schema for unless you give it one. They are skipped, not ignored: the
deploy verifier checks their shape separately.

### 13.7 Check the jar-resolution logic (optional)

```bash
$ ./deploy/tools/test-resolve-server-jar.sh
```

Thirteen assertions covering the supplied-jar path, the engine-authority rule, the foreign-jar
refusal, and the ambiguous-folder error. It needs bash and Python 3, and does not need Docker.

### 13.8 The guided installer (`install.sh`)

Everything in 13.1–13.7 done by hand, in one command:

```bash
$ ./install.sh
```

It is a plain bash script — no Python, no Node, no package manager — and it runs on macOS's
`/bin/bash` 3.2 as well as any Linux shell. It asks before it does anything, writes a log of
everything it did, and can undo what it created. It is the intended front door for a first
install; the manual's command-by-command chapters remain the reference for what it is doing and
for changing things afterwards.

**What it does, in order**

1. **Preflight, as a report you keep.** Probes and prints the version of every tool it cares
   about — `curl`, `docker` and its daemon, the compose v2 plugin, `kubectl`, whether a
   cluster actually answers, `minikube`, `helm`, `java`, `maven`, `git`, `python3` — plus free
   disk. Each line carries a mark: `ok` (present and usable), `warn` (present but not usable,
   or missing on both paths), or a plain note (`java` and `maven` are *not* needed to run the
   network, because the images carry their own JDK; they are needed to build the plugin here).
   Only a genuinely required tool stops the run, and then only after offering to fix it (below).
   The same list is printed again in the closing summary, because it is what a bug report needs.
2. **Asks.** Deployment mode (Docker or Kubernetes, with the machine's actual state as the
   recommendation, not a guess), then every parameter of that path, then the world files.
   The repository's own defaults are one Enter away; each question validates its answer.
   Questions that only make sense together are explained before they are asked: that one
   *server* is one Spigot process hosting several *matches*, that the minimum is what stays
   warm when nobody is playing, and that the product of the maximum and matches-per-server is
   the capacity — printed back as arithmetic (`10 servers x 25 matches each = 250 matches at
   once`), not left for you to work out.
3. **Copies assets.** Your server jar into `server-jars/` (so the image needs no BuildTools
   compile), your hub world into `deploy/templates/<name>/`, optionally your arena world over
   the bundled one. A directory without a `level.dat` is refused, because that is not a world.
4. **Writes configuration.** `deploy/compose/.env` (mode 600) or
   `deploy/helm/values-install.yaml` — the answers as a file you can edit and reuse.
5. **Builds and starts.** Image builds stream their real output with elapsed time, because a
   spinner over a four-minute compile is a lie.
6. **Waits for readiness**, then **proves it** — not "the container started" but: the controller
   answers `/healthz`; `/infra` reports the provisioner you asked for; the lobby logged
   `role=LOBBY`; the proxy resolved its lobby server; the registry shows no hub masquerading as
   a match host; `/infra`'s `authenticated` flag matches the token decision it just made; and
   the proxy's log reports the authentication mode it is *actually* running, rather than the one
   that was requested.
7. **Prints how to connect and how to undo it**, with the preflight report, the token state and
   an offline-mode warning if either needs your attention.

#### 13.8.1 Missing dependencies

A first run on a machine that has none of this is the common case, so the installer does not stop
at "docker is not installed":

```
==  Missing dependencies ==
  ✖ curl -- required for this path
  ✖ docker -- required for this path
  ✖ docker compose (the v2 plugin) -- required for this path
  ▲ java -- only needed to build the plugin on this machine
  ▲ maven -- only needed to build the plugin on this machine

  this machine               Debian / Ubuntu (apt)

  the installer can install these now with apt-get
  ? Install the missing dependencies automatically? [y]:
```

What is required depends on the path chosen a moment earlier: Docker and Compose are needed on
both (both build their images locally), kubectl only on the Kubernetes path. `java` and `maven`
are listed separately because the *network* does not need them — the images carry their own JDK —
they are only needed to build or test the plugin on this machine. A missing one of those is a
note, not a blocker.

**It uses your distribution's own package manager**, and nothing else:

| Platform | Manager | Docker | Compose v2 | kubectl |
|---|---|---|---|---|
| Debian / Ubuntu | `apt-get` | `docker.io` | `docker-compose-v2`, then the plugin binary | official binary from `dl.k8s.io` |
| Fedora / RHEL | `dnf` | `moby-engine` | `docker-compose` | `kubernetes-client` |
| Arch | `pacman` | `docker` | `docker-compose` | `kubectl` |
| openSUSE | `zypper` | `docker` | `docker-compose` | official binary |
| macOS | Homebrew | `--cask docker` (Docker Desktop) | ships with Docker Desktop | `kubectl` |

Where a distribution does not ship the tool, the installer falls back to the vendor's own
download — the same binary the upstream documentation tells you to fetch — into
`/usr/local/bin` (or `~/.local/bin` when there is no root and no `sudo`): `kubectl` from
`dl.k8s.io`, `helm` from the official installer script, `minikube` from its release bucket, and
the compose v2 plugin from the Compose releases. Everything it runs is printed and logged, and
`--dry-run` prints the commands without running them.

If you decline — or pass `--no-install-deps`, or run unattended, where installing system packages
without being asked would be too invasive — it prints the exact commands for your machine and
stops:

```
  • install them yourself, then re-run this installer:

    curl: apt-get install -y curl
    docker: apt-get install -y docker.io
    docker compose (the v2 plugin): apt-get install -y docker-compose-v2 docker-compose-plugin
    java: apt-get install -y openjdk-25-jdk openjdk-24-jdk openjdk-21-jdk
```

Three things it knows that are easy to get wrong by hand:

- **An installed Docker with a dead daemon is not a missing dependency.** It offers to start the
  daemon (`systemctl start docker`, `service docker start`, or launching Docker Desktop) instead
  of sending you off to install it again.
- **A fresh Docker install does not make this user able to use it.** If the daemon needs `sudo`,
  the installer prints the `usermod -aG docker` line and stops, because group membership only
  applies to a new login — re-running after `newgrp docker` or a re-login is the fix, and saying
  so is better than a confusing "permission denied" ten steps later.
- **On WSL** it says so and points at the Docker Desktop WSL integration, which is far less
  painful than running a daemon inside the distribution.

Two of the questions deserve their own explanation, because both change who can join:

- **"Generate a shared API token for the controller?"** (default: yes.) The controller's
  mutating endpoints are the ones a compromised pod could abuse. Yes generates a 32-character
  token and hands it to the controller, the proxy and every game pod it starts — on Kubernetes
  into a chart-managed `Secret`, on Docker as an environment variable on each container. You
  never copy it anywhere by hand, and it is never printed: even `--dry-run` shows
  `BEDWARS_API_TOKEN=<generated, not shown>`. No leaves those endpoints open, which is the
  documented single-host mode and is called out on screen. See 21.2.
- **"Run the proxy in OFFLINE MODE (testing only)?"** (default: no.) Offline mode drops the
  Mojang check at the proxy so a client with no account can connect — the usual way to test with
  an offline client. It is asked only for stacks that contain the proxy, and it warns loudly,
  because it also lets anyone join as anyone. See 9.4.8.

**Options**

| Flag | Effect |
|---|---|
| `--mode docker\|kubernetes` | Skip the mode question |
| `--yes` | Accept every default; fully unattended |
| `--dry-run` | Print every action, write/build/start nothing |
| `--resume` | Use `.installer/state` from a previous run as the defaults |
| `--install-deps` | Install missing dependencies with the system package manager without asking. Under `--yes` the installer deliberately does **not** install (see below) unless this is passed |
| `--no-install-deps` | Never install anything: report what is missing, print the commands for your distribution, and stop |
| `--teardown` | Remove the stack it created, then stop |
| `--verbose` | Echo each command as it runs |
| `--no-color` | Plain output (also `NO_COLOR=1`) |
| `-h`, `--help` | Usage |

**What it writes, and what it will not touch**

- `.installer/install.log` — every command, with timestamps. The pretty screen is scrollback;
  the log is the record.
- `.installer/state` — what was created, so `--teardown` and `--resume` work. This is why
  teardown is safe: it removes what the installer made, not "everything that looks related".
- `deploy/compose/.env` and `deploy/helm/values-install.yaml` — your answers. Both are
  gitignored; the `.env` is written mode 600 because it holds passwords.
- It never sets an existing password to a new one, never deletes a world, and never removes a
  container it did not create.

**Honest limits**

- The API-token question **is** asked, and defaults to yes. The installer generates a token,
  writes it into the config file (mode 600), and hands it to the controller, the proxy and every
  game pod it starts — on the Kubernetes path into a `Secret` the chart creates, on the Docker
  path as an environment variable on each container. Declining leaves the controller open: the
  documented single-host development mode, and the installer says so on screen rather than
  implying it is safe. See 21.2.
- The plain-manifest Kubernetes path applies the repository's manifests, which are written for
  the `bedwars` namespace and the `solo` arena group. Ask for anything else and the installer
  says so and uses the Helm path's parameterisation instead of deploying somewhere the DNS
  suffix does not point.
- A local cluster cannot pull an image that exists only on your machine. On minikube/kind the
  installer loads the images and then **verifies** they are visible to the cluster
  (`minikube image ls`), because `minikube image load` exits 0 even when it did nothing.
- Object storage is optional; when the default registry refuses the MinIO pull (a common
  locked-down-network symptom, and one this machine has), the installer offers the documented
  Bitnami mirrors and records the choice in `.env` instead of failing half an hour later.

**Testing the installer itself**

`deploy/tools/test-install-interactive.py` drives `install.sh` through a real pty and asserts what
a user cares about: that every answer is honoured, that a nonsense answer is rejected and asked
again rather than accepted, that the review's `[e]dit` really re-runs the wizard, and that
`[a]bort` exits having changed nothing. It runs the installer in `--dry-run`, so it needs no
Docker, no cluster and no network:

```bash
$ deploy/tools/test-install-interactive.py
answered 30 of 30 prompts, installer exit code 0
PASS  the invalid answer was rejected and re-asked
PASS  the corrected answer was accepted
PASS  a non-default menu value was honoured
PASS  the first pass used the chosen stack
PASS  the re-run used the new stack
PASS  the engine choice was honoured
PASS  aborting reported that nothing changed
```

---

## 14. The server jar: Spigot versus Paper

### 14.1 The problem

The plugin runs *inside* a Minecraft server, so the image must contain one. This is where
Minecraft's distribution model makes life difficult.

**Spigot does not distribute binaries.** Since 2014, distributing compiled Spigot jars has been
legally fraught, so the sanctioned way to obtain one is to **build it yourself** with
**BuildTools** — an official tool that downloads the Minecraft server jar, decompiles it,
applies Spigot's patches, and recompiles. It uses `git` and takes anywhere from several minutes
to twenty, and it needs a JDK.

Put naively in a Dockerfile, this produces a genuinely bad outcome: **every image build
recompiles Spigot**, for minutes, on every build, on every machine, for a jar that does not
change. In a Kubernetes cluster that rebuilds images, that is a serious waste.

**Paper does distribute binaries.** PaperMC publishes prebuilt jars through its API, so a Paper
game server needs no compilation at all.

### 14.2 The resolution order

`deploy/docker/resolve-server-jar.sh` implements a strict order, and the whole point is that
**compilation is the last resort**:

| Order | Condition | Action | Cost |
|---|---|---|---|
| 1 | A matching jar is present in `server-jars/` | Copy it verbatim | ~0 |
| 2 | `SERVER_ENGINE=paper` | Download from the PaperMC API | seconds |
| 3 | `SERVER_ENGINE=spigot`, nothing supplied | Run BuildTools | **minutes** |

Two properties of this order matter:

- **The engine is authoritative.** `SERVER_ENGINE` decides which jar is acceptable. If you ask
  for Paper and there is a Spigot jar lying in `server-jars/`, that jar is **ignored**, with a
  log line saying so. This was a real bug: before the fix, a stray Spigot jar silently produced
  a "Paper" image. An image that contains the wrong engine is a bug that only shows up at
  runtime, wearing a confusing costume.
- **A supplied jar is used byte-for-byte.** No repacking, no patching. The image's
  `/server/server.jar` is checked to be identical to what you supplied.

### 14.3 The fast path, and what it is worth

```bash
$ cp /path/to/spigot-26.3.jar server-jars/
$ docker build -f deploy/docker/gameserver.Dockerfile -t bedwars-spigot:1.0.0 .
```

Measured on this repository: **23 seconds**, with this in the build output:

```
[server-jar] using supplied jar /server-jars/spigot-26.3.jar (skipping the Spigot compile entirely)
[server-jar] resolved server.jar - 82M
```

Versus minutes for the compile. That is the difference between a CI pipeline that is pleasant
and one people route around.

Where do you get a jar? Build one once on a machine with a JDK (any machine, once):

```bash
$ mkdir -p ~/buildtools && cd ~/buildtools
$ curl -Lo BuildTools.jar https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar
$ java -jar BuildTools.jar --rev 26.3
$ cp spigot-26.3.jar /path/to/BedwarsRecoded/server-jars/
```

Note `--rev 26.3`: it must match `SPIGOT_REV`, otherwise the jar is for the wrong Minecraft
version and the plugin's `api-version` will not match.

Then commit nothing — `server-jars/*.jar` is in `.gitignore`. The folder is committed (via
`.gitkeep`) but its contents are not, deliberately: an 82 MB binary does not belong in git, and
its licence situation is better handled by whoever runs the build.

### 14.4 Choosing an engine

| | Spigot (default) | Paper (option) |
|---|---|---|
| Jar source | Supplied, else compiled with BuildTools | Downloaded from the PaperMC API |
| Compile needed | Only if no jar is supplied | Never |
| Plugin compatibility | The project's target | Runs the same plugin; no Paper-only API is used |
| When to choose | You need exact Spigot behaviour, or you already have a jar | You want faster builds and Paper's server-side optimisations |

The guided installer asks this question for **every** stack that needs a game image - controller
+ game servers, full network, and everything (chapter 13.8). It used to ask only for two of the
four choices, so picking the player-facing "full network" silently gave you Spigot and never
offered Paper at all.

To build a Paper image by hand:

```bash
$ docker build -f deploy/docker/gameserver.Dockerfile \
    --build-arg SERVER_ENGINE=paper \
    --build-arg PAPER_VERSION=26.3 \
    -t bedwars-spigot:paper .
```

Verify which engine you actually got, rather than trusting the command:

```bash
$ docker run --rm --entrypoint sh bedwars-spigot:paper \
    -c 'unzip -p /server/server.jar META-INF/MANIFEST.MF | grep Main-Class'
Main-Class: io.papermc.paperclip.Main        # Paper
```

For a Spigot jar that line reads `org.bukkit.craftbukkit.Main` instead. The container, the
entrypoint, the probes and the Kubernetes manifests are identical either way: the jar is always
`/server/server.jar`, which is the entire point of the indirection.

### 14.5 Build arguments, in full

| Argument | Default | Meaning |
|---|---|---|
| `SERVER_ENGINE` | `spigot` | Which engine to resolve. Decides which supplied jars are acceptable |
| `SPIGOT_REV` | `26.3` | BuildTools revision. Must match your jar |
| `PAPER_VERSION` | *(empty)* | Paper Minecraft version. Required for the Paper path |
| `PAPER_BUILD` | *(empty)* | Pin a specific Paper build. Empty = the latest for the given channel |
| `PAPER_CHANNEL` | `STABLE` | `STABLE` or `EXPERIMENTAL` |
| `BUILD_TOOLS_URL` | the SpigotMC Jenkins URL | Where BuildTools comes from. Override for a mirror or a pinned copy |

`PAPER_BUILD` and `BUILD_TOOLS_URL` exist for reproducibility: a floating "latest" is fine for
development and unacceptable for a release you intend to rebuild identically in a year.

### 14.6 How the Paper download works

The old PaperMC v2 API is sunset; this uses the current v3 API:

```
https://fill.papermc.io/v3/projects/paper/versions/26.3/builds
```

The resolver asks for a JSON build list and picks the newest build on the requested channel
(or the one named by `PAPER_BUILD`), then downloads its artifact. If the version does not
exist, it fails with a clear message:

```
ERROR: the PaperMC API returned HTTP 404 for version 0.0-nope
```

That message is deliberate. The first version of this script printed a Python traceback and
then handed `curl` an empty URL, producing a second, unrelated error. A build failure should
tell you what you did wrong, once.

---

## 15. Path A — the whole stack with Docker Compose

This path gives you a complete, working platform on one machine. It is the fastest way to
understand the system, and it is genuinely useful beyond development: a single host can serve
a small community perfectly well.

### 15.1 Before you start

You need Docker running, Java 25, Maven and Python 3 (chapter 11.1). You do **not** need
Kubernetes.

### 15.2 Step 1 — configure

```bash
$ cd deploy/compose
$ cp .env.example .env
```

Why: Compose interpolates `${VARIABLES}` from a file named `.env` in the working directory.
Copying the template gives you a starting point with documented defaults. `.env` is gitignored
— it will hold your passwords.

Open it and set what you need. The values that are safe to keep as-is for a local run are
`SPIGOT_REV`, `SERVER_ENGINE` and the database name. The ones you should change before anyone
else can reach the host are `BEDWARS_DB_PASSWORD`, `BEDWARS_DB_ROOT_PASSWORD`,
`BEDWARS_S3_ACCESS_KEY` and `BEDWARS_S3_SECRET_KEY`.

The full list is in chapter 17.4.

### 15.3 Step 2 — decide about the server jar

```bash
$ ls server-jars/
```

An empty folder means the game image will compile Spigot with BuildTools — minutes instead of
seconds, but only once per build. If you have a jar, drop it in (chapter 14.3). If you would
rather use Paper, set `SERVER_ENGINE=paper` and `PAPER_VERSION=26.3` in `.env`.

### 15.4 Step 3 — start the infrastructure and the controller

```bash
$ docker compose up -d mysql minio minio-init controller
```

Every part of that line:

| Part | What it does |
|---|---|
| `docker compose` | The Compose v2 plugin (not the legacy `docker-compose`) |
| `up` | Create and start the named services, plus their dependencies |
| `-d` | Detached: run in the background and return the prompt |
| `mysql minio minio-init controller` | Explicit service list. Without it, Compose starts everything including the game pod |

Compose also reads `docker-compose.yml` from the current directory automatically; there is no
needed `-f` because you `cd`-ed into `deploy/compose`.

What you should see:

```bash
$ docker compose ps
NAME                  SERVICE      STATUS
bedwars-controller-1  controller   Up (healthy)
bedwars-minio-init-1  minio-init   Exited (0)
bedwars-minio-1       minio        Up
bedwars-mysql-1       mysql        Up (healthy)
```

Reading that table: `mysql` waiting for its healthcheck is normal for the first thirty seconds
(a MySQL container initialises its data directory on first start). `minio-init` showing
`Exited (0)` is **correct** — it is a one-shot job that creates the bucket and is supposed to
exit successfully.

Why the controller lists its dependencies implicitly: `depends_on` with
`condition: service_healthy` on MySQL means Compose will not start the controller until MySQL
answers its healthcheck. That is why you can name the services in any order.

### 15.5 Step 4 — confirm the controller is alive

```bash
$ curl -s http://localhost:8080/healthz
ok
```

Now look at what it thinks it is:

```bash
$ curl -s http://localhost:8080/infra | python3 -m json.tool
{
    "provisioner": "DOCKER",
    "description": "docker prefix=bedwars-game image=bedwars-spigot:1.0.0 servers[0..10] ...",
    "servers": 0,
    "registeredServers": 0,
    "freeSlots": 0,
    "minServers": 0,
    "maxServers": 10,
    "gamesPerServer": 25,
    "gameCapacity": 0,
    "authenticated": false
}
```

Read this carefully, because it is the single most informative endpoint in the system:

- `"provisioner": "DOCKER"` — the Compose file set this explicitly. If you ever see
  `"provisioner": "KUBERNETES"` here, the controller is in its default mode and will never
  find capacity on this host. That is the classic mistake on this path.
- `servers: 0` — no game server exists yet. Correct: nothing has been started.
- `gamesPerServer: 25` — the planning figure from `BEDWARS_GAMES_PER_SERVER`.
- `authenticated: false` — no `BEDWARS_API_TOKEN` reached this controller, which is the
  documented single-host development mode. This example is a bare `docker compose up`, where the
  repository's own `.env.example` ships no token; a stack built by `install.sh` reports `true`
  here instead, because it generates one and gives it to every client. Acceptable on a private
  host either way, never on a public one (chapter 21).

Also worth a look, because KEDA would consume exactly this on a cluster:

```bash
$ curl -s http://localhost:8080/metrics | grep '^bedwars_'
```

### 15.6 Step 5 — start one real game server

```bash
$ docker compose --profile game up -d game-pod
```

`--profile game` is required: the `game-pod` service is inside the `game` profile, and Compose
will not start profiled services unless the profile is named.

This is the slow step. The first run builds the game image (seconds with a supplied jar,
minutes without), then boots a real Spigot server inside a container. Watch it:

```bash
$ docker compose logs -f game-pod
```

What a healthy boot looks like, and what each line means:

```
[entrypoint] staging local template 'Glacier' from /templates/Glacier
[entrypoint] arena ready: 12M world at /server/world
[16:26:51] [Server thread/INFO]: bedwars_setup controller_reporting=enabled controller_url=http://controller:8080
[16:26:51] [Server thread/INFO]: bedwars_setup server_id=pod-local-1 arena_group=solo teams=2x2 games_per_server=1 template=Glacier@1.0.0(LOCAL) persistence=mysql json_logs=true
[16:26:51] [Server thread/INFO]: whitelist_ok already off
[16:26:51] [Server thread/INFO]: Done (0.558s)! For help, type "help"
```

Line by line:

1. **`[entrypoint] staging local template`** — the entrypoint script copied the baked arena over
   `/server/world` *before* the JVM started. This must happen first (chapter 10.5).
2. **`arena ready: 12M world`** — the staged world is 12 MB and has a `level.dat`, meaning it is
   a real, loadable world rather than an empty directory.
3. **`bedwars_setup controller_reporting=enabled`** — the plugin started and will report to the
   controller. If this said `disabled`, the plugin could not read its config.
4. **`bedwars_setup server_id=pod-local-1 ...`** — the plugin's own startup summary. `server_id`
   is the identity every report carries; `arena_group=solo` and `teams=2x2` come from
   `config.yml`; `template=Glacier@1.0.0(LOCAL)` is the map and where it came from;
   `persistence=mysql` means the database connection succeeded. This one line answers "is this
   server configured the way I think it is?" — check it before anything else.
5. **`whitelist_ok already off`** — the plugin's whitelist guarantee ran and found nothing to do.
6. **`Done (0.558s)!`** — the server finished booting and is accepting connections.

For contrast, if the map had failed to stage you would see
`WARNING: no arena world staged; the match will run on a generated world` — the pod still runs,
on a procedurally generated world. That is the soft-failure design in action.

### 15.7 Step 6 — confirm registration

```bash
$ curl -s http://localhost:8080/infra | python3 -m json.tool
```

The values that must have changed:

```
"servers": 0,             <- still 0: this is the Docker provisioner's count of servers IT created
"registeredServers": 1,   <- 1: a game server has reported ready
"freeSlots": 25,
"gameCapacity": 0,
```

`servers` and `registeredServers` are different questions and it is important not to confuse
them. `servers` is "how many game servers does the *provisioner* manage" — the controller did
not create this one (you did, with Compose), so it stays 0. `registeredServers` is "how many
servers have reported themselves ready" — that is 1. A server you started by hand registers
exactly like one the controller started, which is what makes this path a useful test.

### 15.8 Step 7 — exercise the queue

Ask the controller for a slot, exactly as a lobby would:

```bash
$ curl -s -X POST http://localhost:8080/lobby/queue \
    -H 'Content-Type: application/json' \
    -d '{"playerId":"11111111-1111-1111-1111-111111111111","playerName":"Tester","priority":0,"arenaGroup":"solo"}'
{"podAddress":"pod-local-1","gameId":null,"members":["11111111-1111-1111-1111-111111111111"],"retryAfterMillis":0}
```

The reply is a `DispatchResult`:

| Field | Meaning |
|---|---|
| `podAddress` | Which server the player was placed on. `pod-local-1` is the container you started |
| `gameId` | The specific match, if one already exists. `null` means a new match will start |
| `members` | Everyone who should be sent to that server — the player, plus teammates |
| `retryAfterMillis` | `0` for success. Non-zero means "no capacity; try again in this many ms" |

A `retryAfterMillis` response is not an error. It is the controller telling the lobby to back
off while it creates capacity. The backoff grows with queue depth, so a thundering herd does
not hammer a controller that has nothing to give.

Now watch the capacity drop, because that is the allocation actually happening:

```bash
$ curl -s http://localhost:8080/lobby/arena-status | python3 -m json.tool
{"solo": {"freeSlots": 24, "queued": 0}}
```

`freeSlots` went from 25 to 24: one slot is consumed by the match the dispatch created. When
that match ends and the plugin reports `/pods/ended`, the slot is released and the number goes
back up. This single loop — report capacity, hand out a slot, release it — is the heart of the
system.

### 15.9 Step 8 — connect a client (optional)

On the plain stack no port is published for players: this is a game server, and the queue is how
players are normally placed on it. For driving a server without a client, see chapter 18.4.

To play the way a player does — connect to the proxy, land in the lobby, click a sign, and be
moved into a match — bring up the `network` profile as well (the lobby and the proxy are in
chapter 9.4, and the commands are in 15.13):

```bash
$ docker compose --profile network up -d --build
```

Then point a Minecraft client at `localhost:25565`: you will land in the lobby world, and the
signs/NPCs there put you into a match.

### 15.10 Inspecting the provisioned containers

The controller labels every container it creates:

```bash
$ docker ps --filter "label=bedwars.provisioned=true"
```

That label is a hard boundary: the Docker provisioner only ever touches containers carrying it,
so unrelated containers on your host are invisible to it. When you want to clean up after
testing:

```bash
$ docker rm -f $(docker ps -aq --filter "label=bedwars.provisioned=true")
```

### 15.11 Shutting down

```bash
$ docker compose --profile game --profile network down      # stop, keep volumes
$ docker compose --profile game --profile network down -v   # stop and delete volumes
```

Name every profile you started. Compose only stops the services of the profiles it is given, so
shutting down with `--profile game` alone leaves the lobby and the proxy running.

`down` removes containers and networks; named volumes are kept unless you pass `-v`. Use `-v`
when you want a genuinely clean slate, such as after changing database credentials — MySQL
initialises its users only on a first, empty start, so stale volumes are a common cause of
"my new password does not work".

### 15.12 What this path does not give you

Be clear-eyed about the boundary, because it matters if you are tempted to run this in
production:

| Missing | Consequence |
|---|---|
| No scheduler | A game container that dies is not restarted |
| No node autoscaling | Everything runs on one machine |
| Docker socket mounted in the controller | Root-equivalent host access — a compromise of the controller is a compromise of the host |
| No authentication by default | Anyone who can reach port 8080 can enqueue players |
| Single point of failure | One host, one MySQL, one controller |

For a home server with friends, several of those are acceptable. For anything public, they are
not, and chapter 16 is the answer.

### 15.13 Bringing up the lobby and the proxy (the `network` profile)

Everything above runs the platform the way the *controller* sees it. To run it the way a *player*
does, add the `network` profile. That starts the two services that face players: the **lobby**
(the hub, chapter 9.4) and the **proxy** that fronts it.

```bash
$ docker compose --profile network up -d --build
```

Give it a minute on the first run: the proxy image is built here. The lobby image is the game
image you may already have.

The Compose file deliberately skips object storage on this path. `minio` and `minio-init` are not
in a profile, and some hosts refuse unauthenticated `quay.io` pulls (a plain `401 UNAUTHORIZED`
from the registry), so if you hit that, name the services you actually need:

```bash
$ docker compose up -d --build mysql controller lobby velocity
```

Then check the three things that prove the hub is really a hub:

```bash
# 1. The lobby booted in LOBBY role and switched controller reporting off.
$ docker compose logs lobby | grep -E "bedwars_setup|lobby_ready"
[entrypoint] no local template 'lobby' - the server will generate a world
[lobby] bedwars_setup role=LOBBY lobby_world=lobby server_id=lobby ...
[lobby] lobby_ready world=lobby - players arrive from the proxy; ...

# 2. The proxy found the lobby it was told about, and can dial a pod by name.
$ docker compose logs velocity | grep -E "proxy initialised|not registered|Registering game pod"

# 3. The lobby is NOT a match host. This is the assertion that matters, and it must be 0.
$ curl -s localhost:8080/infra | python3 -c "import json,sys; print(json.load(sys.stdin)['registeredServers'])"
0
```

`registeredServers` counts servers that have offered capacity. A lobby appearing there means the
controller will hand it players as if it hosted matches — which is precisely the failure the
`LOBBY` role exists to prevent.

Now connect a Minecraft client to `localhost:25565`. You arrive in the lobby world (generated and
flat if you supplied no hub map). Set up the queue, then use it:

1. Join the hub with an operator account. Place a sign and set its **first line** to `[bedwars]`.
   The other three lines are yours — only the first is read.
2. Right-click it. You should be moved into a match: the lobby sends `bedwars:queue`, the proxy
   asks the controller, the controller names a pod, the proxy transfers you.
3. Play the match to its end. When it finishes, the game server sends `bedwars:return` and the
   proxy puts you back in the hub. That is the whole loop of 9.4.1, and the only part of it you
   can verify without a second player is the transfer itself.

A game pod must exist for step 2 to succeed. Either start one by hand
(`docker compose --profile game up -d game-pod`) or let the controller start one — it will, as
soon as the queue request arrives and no server has room.

If you only want the hub without the proxy, `docker compose up -d lobby` is enough to see it boot;
you just cannot queue from it, because nothing can move a player off a backend server except the
proxy.

---

## 16. Path B — Kubernetes

The Kubernetes path is the production shape: game servers are created and destroyed by the
cluster, the fleet scales with the queue, and no component is a single point of failure that
matters.

### 16.1 Two ways to install

| | Plain manifests | Helm chart |
|---|---|---|
| Location | `deploy/k8s/` | `deploy/helm/bedwars/` |
| Install | `kubectl apply -k deploy/k8s/` | `helm install bedwars deploy/helm/bedwars -n bedwars --create-namespace` |
| Configure | Edit the YAML | `-f` values files or `--set` |
| Upgrades | Re-apply | `helm upgrade` |
| Best for | Reading exactly what is deployed; a small fixed setup | Real deployments, multiple environments, templating |

Both render the same system. The Helm chart is the better choice whenever you will change
anything more than once.

### 16.2 Prerequisites, in order

Install in this order. Each step depends on the previous one.

**1. A cluster and a kubeconfig.**

```bash
$ kubectl config current-context
$ kubectl get nodes
```

The **kubeconfig** is a file (`~/.kube/config`) describing how to reach clusters and which
credentials to use. Switching clusters means switching context. `kubectl get nodes` printing a
`Ready` node is the proof you are pointed somewhere real.

For a local cluster:

```bash
$ minikube start --driver=docker --cpus=3 --memory=4096
```

Those flags are not optional on a typical machine. minikube's defaults allocate about 2 GiB,
and a cluster holding an API server, MySQL and a Spigot pod will starve it — the API server
becomes unresponsive and `kubectl` commands hang or return connection errors. This is a
documented symptom, not a guess: it happened during this project's own verification.

**2. OpenKruise and kruise-game** (for game servers):

```bash
$ helm repo add openkruise https://openkruise.io/charts
$ helm repo update
$ helm install kruise openkruise/kruise --namespace kruise-system --create-namespace
$ helm install kruise-game openkruise/kruise-game --namespace kruise-system
$ kubectl get crd gameserversets.game.kruise.io
```

`--create-namespace` does exactly what it says: creates the namespace if it does not exist.
Without it, `helm install` into a missing namespace fails.

The OpenKruise controllers take a minute or two to become ready. Do not apply the
`GameServerSet` before the CRD registers, or Kubernetes will report the kind as unknown.

**3. KEDA and Prometheus** (for autoscaling):

```bash
$ helm repo add kedacore https://kedacore.github.io/charts
$ helm repo update
$ helm install keda kedacore/keda --namespace keda --create-namespace
```

Plus a Prometheus the controller's metrics are scraped into — the kube-prometheus-stack chart
is the usual choice. The `ServiceMonitor` in `12-servicemonitor.yaml` and the chart's own
`serviceMonitor.enabled` assume the Prometheus Operator is installed.

**4. Build and publish the images.** On a real cluster, to a registry:

```bash
$ docker build -f deploy/docker/gameserver.Dockerfile -t ghcr.io/you/bedwars-spigot:1.0.0 .
$ docker build -f deploy/docker/controller.Dockerfile -t ghcr.io/you/bedwars-controller:1.0.0 .
$ docker build -f deploy/docker/velocity.Dockerfile -t ghcr.io/you/bedwars-velocity:1.0.0 .
$ docker push ghcr.io/you/bedwars-spigot:1.0.0        # and the other two
```

On a local cluster, load them into the node instead of pushing anywhere:

```bash
$ minikube image load bedwars-spigot:1.0.0 bedwars-controller:1.0.0 bedwars-velocity:1.0.0
```

**Verify the load actually worked.** `minikube image load` prints a warning and *still exits 0*
when the image does not exist on the host daemon, so a naive `load && echo ok` check passes and
the pod later fails with `ErrImagePull`:

```bash
$ minikube image ls | grep bedwars
```

Also make sure the **tag matches the manifest**. An image tagged `:latest` on the host is not
`:1.0.0` in the cluster.

### 16.3 Step-by-step: the Helm path

**1. Create the namespace and install:**

```bash
$ helm install bedwars deploy/helm/bedwars \
    --namespace bedwars --create-namespace \
    -f deploy/helm/values-minikube.yaml          # omit for a real cluster
```

`helm install <release> <chart>` names this installation. `-f` layers a values file over the
chart's defaults. **Do not** use `values-minikube.yaml` on a real cluster: it disables MinIO
and KEDA and shrinks the game pod to 1.5 GiB, all of which are compromises for a 4 GiB sandbox.

**2. Watch it come up:**

```bash
$ kubectl -n bedwars get pods -w
```

Expected, in order: MySQL becomes `Running` and `Ready`; the controller becomes `Ready`; MinIO
if enabled; Velocity and mc-router; and **zero game pods**, because the GameServerSet starts at
`replicas: 0`. That last one is correct and worth internalising — an idle platform runs no game
servers at all.

**3. Confirm the control plane:**

```bash
$ kubectl -n bedwars port-forward svc/bedwars-controller 8080:8080
```

in one terminal, then in another:

```bash
$ curl -s http://localhost:8080/healthz
$ curl -s http://localhost:8080/infra | python3 -m json.tool
```

`port-forward` opens a tunnel from your machine to the Service and **stays in the foreground**;
closing it or `Ctrl-C` ends the tunnel. It is a debugging tool, not an ingress.

Here `/infra` should say `"provisioner": "KUBERNETES"` and describe the GameServerSet:
`servers[0..50] gamesPerServer=25`.

### 16.4 Step-by-step: the plain-manifest path

```bash
$ kubectl apply -k deploy/k8s/
```

`-k` means **Kustomize**: it reads `deploy/k8s/kustomization.yaml` and applies everything it
lists, in one command. That is why there is no `-f 00-namespace.yaml -f 10-...` list.

Before applying on a real cluster, edit:

- `50-s3-config.yaml` — `endpoint`, `bucket`, and the two `REPLACE_ME` credentials. In
  production use ExternalSecrets or SealedSecrets rather than plaintext in a Secret, because a
  Secret is base64, not encryption.
- `10-gameserverset.yaml` — the S3 endpoint if you keep templates in real S3.

Then apply, in this order if you are doing it by hand rather than with `-k`:

```bash
$ kubectl apply -f deploy/k8s/00-namespace.yaml
$ kubectl apply -f deploy/k8s/50-s3-config.yaml
$ kubectl apply -f deploy/k8s/40-controller.yaml
$ kubectl apply -f deploy/k8s/10-gameserverset.yaml
```

### 16.5 Bringing up a game pod on purpose

The GameServerSet sits at zero replicas. Force one up:

```bash
$ kubectl -n bedwars scale gameserversets bedwars-solo --replicas=1
$ kubectl -n bedwars get pods -l app.kubernetes.io/name=gameserverset -w
```

(The kind is a custom resource, so the resource name is the plural `gameserversets` —
`kubectl get gameserversets` and `kubectl -n bedwars get gss bedwars-solo` both work once the
CRD is installed.)

Watch the pod's log for the same six lines you saw in Compose:

```bash
$ kubectl -n bedwars logs -f bedwars-solo-0 -c gameserver
```

Then confirm registration through the controller:

```bash
$ curl -s http://localhost:8080/infra | python3 -m json.tool
```

`registeredServers` becomes 1 and `freeSlots` 25. Note the timing: the pod's "I am ready"
report lands a moment *after* the process starts, so a query issued the instant the pod goes
`Running` can legitimately read 0. Poll, do not sample once. (This was a real false alarm in
this project's own verification.)

And scale it back down:

```bash
$ kubectl -n bedwars scale gameserversets bedwars-solo --replicas=0
```

### 16.6 Verifying the pod is running the jar you think it is

```bash
$ kubectl -n bedwars exec bedwars-solo-0 -c gameserver -- sha256sum /server/server.jar
$ sha256sum server-jars/spigot-26.3.jar
```

The two digests must match. This is the check that proves the supplied-jar fast path did what
it claimed, end to end, in the cluster — not merely that an image built.

### 16.7 Ports, services, and how players get in

| Service | Type | Port | Purpose |
|---|---|---|---|
| `mc-router` | LoadBalancer (NodePort on minikube) | 25565 | The stable public entry point |
| `velocity` | ClusterIP | 25565 | The proxy; mc-router forwards here |
| `lobby` | ClusterIP | 25565 | The hub every player lands on; Velocity dials it by this name |
| `bedwars-controller` | ClusterIP | 8080 | The control plane |
| game pods | none | 25565 | Reachable only from mc-router/Velocity, enforced by NetworkPolicy |

Game pods have **no Service**. They are reached through a headless Service that OpenKruise
maintains for the GameServerSet, and the NetworkPolicy restricts ingress to mc-router and
Velocity. A player can never dial a game pod directly, which is the intended shape: the queue
is the access control.

The **lobby does have a `Service`**, named `lobby`, and the name is a contract rather than a
convenience: the `velocity.toml` inside the proxy image says `lobby = "lobby:25565"` and
`try = ["lobby"]`, so the proxy resolves that exact name to place every connecting player. Its
own `lobby-ingress` NetworkPolicy allows only mc-router and Velocity, for the same reason game
pods do — the proxy is what authenticates a player, so nothing should reach the hub directly.

That is also how a game pod becomes reachable: the controller names the pod (its pod name, from
`${HOSTNAME}`) and Velocity expands it with `POD_ADDRESS_SUFFIX` into
`<pod>.<gameserverset>.<namespace>.svc.cluster.local`. Change `BEDWARS_GAMESERVERSET` and you
must change `velocity.podAddressSuffix` in the chart's values to match, or the proxy will resolve
nothing and every queue will end with the player left in the hub.

To check the hub is healthy on a cluster:

```bash
$ kubectl -n bedwars get deploy lobby
$ kubectl -n bedwars logs deploy/lobby | grep -E "bedwars_setup|lobby_ready"
$ kubectl -n bedwars port-forward svc/bedwars-controller 8080:8080 &
$ curl -s localhost:8080/infra | grep -o '"registeredServers":[0-9]*'
"registeredServers":0
```

`registeredServers: 0` with only the infrastructure, the hub and the proxy up is correct — and it
is the single most useful thing to check, because a hub that ever appears in that number will be
handed players as if it hosted matches.

### 16.8 Day-two operations

| Task | Command |
|---|---|
| Change a value | `helm upgrade bedwars deploy/helm/bedwars -n bedwars -f myvalues.yaml` |
| See what changed | `helm diff upgrade ...` (the helm-diff plugin) |
| Roll back | `helm rollback bedwars` |
| Scale the fleet manually | `kubectl -n bedwars scale gameserversets bedwars-solo --replicas=N` |
| Read game pod logs | `kubectl -n bedwars logs -f bedwars-solo-0 -c gameserver` |
| Reclaim a stuck pod | `kubectl -n bedwars delete pod bedwars-solo-0` |
| Controller logs | `kubectl -n bedwars logs -f deploy/bedwars-controller` |

Two notes:

- **`helm upgrade` is not `kubectl apply`.** Helm tracks the release and its history, which is
  what makes rollback work. Mixing the two on the same objects causes ownership conflicts.
- **Deleting a game pod is safe by design.** The pod holds no unique state; a replacement
  boots, stages its template and re-registers. That is the entire thesis of the project,
  demonstrated by an operational command you can run without fear.

---

# Part IV — Reference and operation

## 17. Configuration reference

This is the chapter to keep open. Every parameter that exists in this project is here, with
its type, its default, what it does, and when you would change it.

There are **seven** places configuration lives, and knowing which is which saves a great deal
of confusion:

| # | Where | Format | Read by |
|---|---|---|---|
| 1 | `config.yml` | YAML | The plugin, inside each game server |
| 2 | `arena.yml` | YAML | The plugin — arena geometry, generators, shop |
| 3 | `plugin.yml` | YAML | The server, at plugin load time |
| 4 | Environment variables | env | The controller (`ControllerConfig`), and the plugin as overrides |
| 5 | `.env` + `docker-compose.yml` | YAML/env | Docker Compose only |
| 6 | Kubernetes manifests / Helm values | YAML | The cluster |
| 7 | Docker build arguments | build args | The image build |

The layering rule to remember: **the container environment overrides the YAML.** A game server
image contains a `config.yml`, and the controller hands the container environment variables
that take precedence. That is what allows one image to serve every pod with different settings,
and it is why "I changed `config.yml`" is not always the answer.

---

### 17.1 `config.yml` — the plugin configuration

Location: `BedwarsRecoded-Spigot/src/main/resources/config.yml`. Copied to the server's
`plugins/BedwarsRecoded/config.yml` on first run; edit it there, in the image, or override with
the environment variables noted below.

#### Identity

| Key | Type | Default | Meaning |
|---|---|---|---|
| `server-id` | string | `${HOSTNAME}` | The name this server uses in every report to the controller. In Kubernetes this is the pod name, which is also the DNS name the proxy dials — see 9.4.5. |

`${HOSTNAME}` is expanded from the environment. In Kubernetes the Downward API sets `HOSTNAME`
to the pod name, so each pod identifies itself uniquely without configuration. The
`pod-` prefix makes the origin obvious in logs. Change it only if your platform does not set
`HOSTNAME`, or you need a naming convention of your own — but it **must** be unique per server,
because the controller's registry is keyed by it. Two servers with the same id will overwrite
each other in the pool.

#### `arena:` — the match this server hosts

| Key | Type | Default | Meaning |
|---|---|---|---|
| `group` | string | `solo` | Which arena group (game mode) this server serves. |
| `games-per-server` | int | `1` | How many concurrent matches this server may host. |
| `team-count` | int | `2` | Teams per match. |
| `players-per-team` | int | `2` | Players per team. |
| `countdown-seconds` | int | `15` | Countdown before a match starts. |
| `sudden-death-after-seconds` | int | `300` | Time until sudden death begins. |
| `void-y-threshold` | double | `0.0` | Y below which a falling player counts as a void kill. |
| `island-radius` | double | `30.0` | Approximate island radius, for protection and void checks. |
| `bed-protection-radius` | double | `3.0` | Radius around a team's bed protected from enemies. |

**`games-per-server` is the one to think hardest about.** At `1` (the default), the server runs
a single match on its existing world. Above `1`, **each match gets its own world**, copied from
the template, and the server reports the resulting free slots to the controller. That is the
multi-game-per-server topology: more matches per pod means fewer pods, less scheduling churn,
and more memory per pod.

It must agree with the controller's view. The controller plans with
`BEDWARS_GAMES_PER_SERVER` (default 25) and the plugin reports actual capacity from this
setting; when they disagree, the *plugin's* number is what the registry stores, because it
comes from the server that has to do the work. Keep them consistent to avoid confusion in
`/infra`.

**`sudden-death-after-seconds`** is a balance knob with an architectural effect: shorter sudden
death means matches end faster, which means slots free up faster, which means fewer servers are
needed for the same queue depth.

**`bed-protection-radius`** is a gameplay rule enforced by the plugin, not by the world. It is
a distance check, so shrinking the arena without shrinking this would leave beds permanently
safe.

#### `dragon:` — sudden-death dragons

| Key | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | bool | `true` | Master switch for dragons. |
| `base-per-match` | int | `1` | Neutral dragons spawning at sudden death; they attack every team. |
| `health` | double | `300.0` | Dragon health. Default is high so a dragon survives long enough to matter. |
| `spawn-height` | double | `25.0` | How far above the island/centre a dragon spawns. |
| `knockback` | double | `3.0` | Velocity magnitude applied to nearby players. |
| `knockback-radius` | double | `10.0` | Radius of the knockback pulse. |
| `knockback-interval-ticks` | int | `10` | Ticks between knockback pulses (10 ticks = 0.5 s at 20 TPS). |
| `destroy-radius` | int | `3` | Horizontal radius of map destruction around the dragon. |
| `destroy-depth` | int | `40` | How far below the dragon destruction reaches. |
| `destroy-interval-ticks` | int | `20` | Ticks between destruction passes (20 ticks = 1 s). |
| `damage` | double | `0.0` | Heart damage per pulse. `0` = knockback only, no damage. |

The dragons are **not decoration** — they are the mechanism that forces a stalemate to resolve.
A game where two teams camp their beds forever ends badly for everyone; a dragon that eats the
map forces the issue.

Two parameters deserve a warning. **`destroy-depth: 40` with `destroy-radius: 3` is a
substantial amount of terrain per second** — that is the intent, but on a small arena it will
reach the void quickly, ending the match by environment rather than by combat. And
**`knockback-interval-ticks: 10`** means a player near a dragon is pushed twice a second;
combined with `damage`, that becomes lethal fast. Tune these deliberately, not incidentally.

`knockback-interval-ticks` and `destroy-interval-ticks` are in **ticks, not seconds**, because
they are scheduled on the game tick loop. 20 ticks is one second at the standard 20 TPS. If
your server is struggling and running at 10 TPS, these intervals stretch to twice their
wall-clock duration — a subtle coupling worth remembering when performance tuning.

#### `server:` — boot-time guarantees

| Key | Type | Default | Meaning |
|---|---|---|---|
| `role` | string | `"GAME"` | `GAME` is a match host; `LOBBY` makes this process the network hub. See 9.4. |
| `force-whitelist-off` | string | `"OFF"` | `OFF` switches the whitelist off at boot; `LEAVE` never touches it. |

`role` is the switch that decides whether this process registers with the controller as a match
host. It is read once, at boot, and a `LOBBY` process never constructs a reporter at all — so no
later configuration can turn a hub into a game server by accident. Anything unrecognised parses
as `GAME`.

There is real, hard-won reasoning here. A Minecraft server may default `white-list=true`, and an
**empty** whitelist then rejects every player with *"You are not whitelisted on this server!"*.
For a game server whose entire job is to accept the players the controller routes to it, that is
a fatal default. `OFF` guarantees the server is joinable. **The queue is the access control, not
the whitelist.** Choose `LEAVE` only if you have a specific reason and understand that you are
then responsible for whitelist contents yourself.

Overridable with the `BEDWARS_WHITELIST` environment variable. Unknown values are mapped to
`OFF` — failing open, which is correct for a game server.

#### `lobby:` — hub behaviour

Only read when `server.role` is `LOBBY`; the keys are harmless on a game server.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `world` | string | `"lobby"` | The world the hub protection rules apply to. Must match the world the entrypoint staged as the main world (`BEDWARS_TEMPLATE_NAME`). |
| `return-to-lobby` | bool | `true` | When a match ends, ask the proxy to move its players back to the hub. This is what closes the loop; without it players are left on a pod that is about to be deleted. |

`return-to-lobby` needs the proxy. On a server with no proxy the request is simply dropped, which
is why leaving it `true` on a game pod is safe. It is set to `false` on the lobby itself, which
has no matches to return anyone from.

#### `template:` — the arena world

| Key | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | bool | `true` | Whether the plugin participates in template loading at all. |
| `name` | string | `Glacier` | Template name. |
| `version` | string | `1.0.0` | Template version — part of the S3 object key. |
| `source` | string | `LOCAL` | `S3` (production) or `LOCAL` (dev only). |
| `local-path` | string | `templates` | Directory **containing** templates; one sub-directory per name. |
| `s3.endpoint` | string | `http://minio:9000` | S3-compatible endpoint. |
| `s3.bucket` | string | `bedwars-templates` | Bucket holding templates. |
| `s3.region` | string | `us-east-1` | Signing region. |
| `s3.access-key` | string | `""` | Blank = public bucket, no signing. |
| `s3.secret-key` | string | `""` | Paired with the access key. |

**`enabled: false` is a legitimate production setting**, not a degraded one. The container
entrypoint stages the pod's main world *before* the JVM starts (chapter 10.5), so in the
standard single-match-per-pod deployment the plugin has nothing left to do. Turning it off
simply removes template work from the plugin's logs. Turn it **on** when you use the
multi-game path and want a fresh world copied per match.

`local-path` is a **containing** directory, not the template itself: with `name: Glacier`,
the plugin looks in `templates/Glacier/`. Getting that wrong produces "template not found"
for a path that plainly exists.

**Leave `access-key` blank for MinIO in development** — the Compose stack makes the bucket
anonymously readable for exactly this. In production, use a real access key and secret, supplied
as a Kubernetes Secret rather than pasted into this file, because this file lives inside the
image.

#### `persistence:` and `database:`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `persistence.enabled` | bool | `true` | `false` runs entirely without a database. |
| `database.host` | string | `mysql` | Database host. In Kubernetes this is the Service name. |
| `database.port` | int | `3306` | MySQL port. |
| `database.name` | string | `bedwars` | Schema name. |
| `database.username` | string | `bedwars` | User. |
| `database.password` | string | `bedwars` | Password. **Change it.** |
| `database.pool-size` | int | `4` | Maximum JDBC connections from this server. |
| `database.baseline-elo` | int | `1000` | ELO a brand-new player starts at. |

`persistence.enabled: false` is a supported mode: the plugin never opens a JDBC connection and
simply has no stats. It is useful for a throwaway test server, and it is also the automatic
fallback if the server cannot supply the `libraries` (chapter 17.3) — the plugin degrades
rather than crashing.

**`pool-size` is per game server, not fleet-wide.** Four connections × fifty game servers is
two hundred connections, which a default MySQL (`max_connections` 151) will refuse. Size it
against the fleet, or lower it: matches write once, at the end.

#### `controller:` — the control plane

| Key | Type | Default | Meaning |
|---|---|---|---|
| `base-url` | string | `http://bedwars-controller:8080` | Where this server reports. Overridable with `BEDWARS_CONTROLLER_URL`. |
| `heartbeat-seconds` | int | `15` | How often to report liveness. |
| `disable-reporting-after-failures` | int | `5` | Consecutive failures before reporting stops for the session. |
| `failure-log-interval-seconds` | int | `300` | Minimum gap between repeated failure logs. |

The two failure settings are a **console-noise fix with a specific origin**. A server that
cannot reach its controller retries every heartbeat; without these limits, a down controller
fills the log with one connection error every 15 seconds forever, drowning the lines that
matter. The behaviour now:

- the **first** failure is always logged, with its reason — you are never left guessing;
- further failures are logged at most once every `failure-log-interval-seconds` (5 minutes);
- after `disable-reporting-after-failures` in a row (5), reporting stops for the session.

`base-url` must be a name the server can resolve. In a container, `localhost` is the container's
own loopback — the single most common mistake on this path. Use the controller's Service or
Compose alias.

#### `ranking:` and `logging:`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `ranking.k-factor` | int | `32` | ELO K-factor: how much a single match can move your rating. |
| `ranking.leaderboard-refresh-seconds` | int | `60` | How often cached leaderboards are rebuilt. |
| `ranking.leaderboard-page-size` | int | `100` | Rows per leaderboard page. |
| `logging.json` | bool | `true` | One JSON object per gameplay event, or plain text lines. |

`k-factor: 32` is the classic chess value: responsive but not wild. Lower it for a more stable
ladder, raise it for faster convergence.

`logging.json: true` emits one JSON object per gameplay event, carrying `game_id`, `pod_id` and
`player_uuid` — ready for Loki or ELK. Because every pod logs in the same shape, a query can
join a match's events across the servers that hosted its parts. Set `false` for human-readable
lines on a single host.

---

### 17.2 `arena.yml` — geometry, generators and the shop

Location: `BedwarsRecoded-Spigot/src/main/resources/arena.yml`. Loaded from the pod's data
folder if present; otherwise the plugin **derives a default arena from `config.yml`**. In
production, the coordinates come from the Slime template.

```yaml
group:
  id: solo
  team-count: 2
  players-per-team: 2
  countdown-seconds: 15
  sudden-death-after-seconds: 300
  void-y-threshold: 0.0
  island-radius: 30.0
  bed-protection-radius: 3.0
  team-generators: [IRON, GOLD]
```

The `group:` block mirrors the `arena:` keys in `config.yml` (see 17.1 for each), plus:

| Key | Meaning |
|---|---|
| `team-generators` | Which resources each team's own generator produces. `[IRON, GOLD]` is the classic BedWars pair |

#### Teams

```yaml
teams:
  - id: red
    bed:   { x: 8.5,  y: 64, z: 8.5 }
    spawn: { x: 8.5,  y: 66, z: 8.5 }
  - id: blue
    bed:   { x: -8.5, y: 64, z: -8.5 }
    spawn: { x: -8.5, y: 66, z: -8.5 }
```

| Key | Meaning |
|---|---|
| `id` | Team identifier, used in events and results |
| `bed` | Bed block coordinates. Break it and the team stops respawning |
| `spawn` | Where the team's players appear |

Note the **`.5` coordinates**. A Minecraft block occupies an integer position, and `x.5` is the
centre of that block. Placing a spawn at `8.0` puts the player at the block's corner, which
looks and feels wrong. `y: 66` above a bed at `y: 64` is a sensible two blocks of headroom.

Coordinates must match the actual map. If a bed's recorded position is not where the bed is,
players will be able to break it "through" protection or find it unbreakable — a confusing bug
whose cause is a number in this file.

#### Generators

```yaml
generators:
  - id: red-iron
    type: IRON
    tier: I
    x: 10
    y: 64
    z: 10
  - id: center-diamond
    type: DIAMOND
    tier: I
    x: 0
    y: 64
    z: 0
```

| Key | Meaning |
|---|---|
| `id` | Unique generator name |
| `type` | `IRON`, `GOLD`, `DIAMOND`, `EMERALD` |
| `tier` | Roman-numeral tier (`I`, `II`, `III`), controlling output rate |
| `x` / `y` / `z` | Location |

Generators produce resources on a timer; tier sets the rate. Team generators and centre
generators are the same mechanism with different placement, which is why there is no separate
"islands" concept — the map is teams, beds, spawns and generators, and everything else follows.

#### Shop

```yaml
shop:
  id: default
  display-name: "Item Shop"
  categories:
    - id: blocks
      display-name: "Blocks"
      slot: 0
      icon: WHITE_WOOL
      items:
        - id: wool
          display-name: "Wool"
          slot: 0
          material: WHITE_WOOL
          give-amount: 16
          currency: IRON
          amount: 4
```

| Key | Meaning |
|---|---|
| `categories[].slot` | Inventory slot of the category button in the shop menu (0-8 is the top row) |
| `categories[].icon` | Item used as the button |
| `items[].material` | The item given |
| `items[].give-amount` | How many are given |
| `items[].currency` | `IRON`, `GOLD`, `DIAMOND`, `EMERALD` |
| `items[].amount` | Price |

And the combat items add the upgrade semantics:

```yaml
        - id: sword-iron
          material: IRON_SWORD
          permanent: true
          downgradable: true
          upgrade-group: sword
          tier: 2
```

| Key | Meaning |
|---|---|
| `permanent` | Survives death — you keep it after respawning |
| `downgradable` | On death you drop to the previous tier rather than losing everything |
| `upgrade-group` | Items sharing a group are strictly ordered upgrades of one another |
| `tier` | Position within the group (1 is the weakest) |

`upgrade-group` is what lets the shop show "Iron Sword" as an upgrade of "Stone Sword" rather
than as an unrelated item you can buy twice. The `downgradable` rule exists so that losing a
fight is punishing without being demoralising: you lose a tier, not your whole kit.

Shops are **data**, which is why a map variant with different prices or items does not need new
code — only a different `arena.yml` (or, in the Slime path, a different template).

---

### 17.3 `plugin.yml` — plugin metadata and runtime libraries

| Key | Value | Meaning |
|---|---|---|
| `name` | `BedwarsRecoded` | Plugin name; also the folder name under `plugins/` and the config-file name |
| `version` | `${project.version}` | Substituted by Maven at build time |
| `main` | `dev.bedwars.spigot.BedwarsRecodedPlugin` | The entry class the server instantiates |
| `api-version` | `'26.1'` | The **lowest** server API version this plugin loads on |
| `authors`, `description`, `website` | — | Metadata shown by `/plugins` |
| `libraries` | `HikariCP:6.3.0`, `mysql-connector-j:9.3.0` | Dependencies the server downloads at startup |
| `commands.bedwars` | aliases `[bw]` | The root command |

Three details that are easy to get wrong:

**`api-version` is a floor, not a target.** The plugin is *compiled* against Spigot 26.3, but
declares 26.1 so it loads on any 26.x server. Declaring a higher value than a server supports
makes the server refuse to load the plugin at all — a total failure for a cosmetic gain.

**`libraries` is a size strategy, not a convenience.** HikariCP and the MySQL driver would
alone exceed the 4 MB jar budget. Declaring them here has the server fetch them at startup and
add them to the plugin classpath. If a server cannot provide libraries, the plugin disables
persistence and logs it rather than failing to start.

**There is deliberately no command-level permission.** A blanket `permission:` on the root
command blocked `/bw join`, `/bw shop` and `/bw lang` for ordinary players, which is to say it
blocked the game. Only the administrative subcommands (`start`, `stop`, `reload`) are
individually permission-gated, inside `BedwarsCommand`. If you are used to locking down plugin
commands, this is a deliberate exception with a reason.

---

### 17.4 Controller environment variables

The controller reads **only** environment variables (`ControllerConfig.fromEnv()`), so the same
image runs anywhere. Defaults are shown; blank means "no default".

#### Provisioning

| Variable | Default | Meaning |
|---|---|---|
| `BEDWARS_PROVISIONER` | `KUBERNETES` | Backend: `KUBERNETES`, `DOCKER`, `NONE`. |
| `BEDWARS_NAMESPACE` | `bedwars` | Namespace the controller operates in. |
| `BEDWARS_GAMESERVERSET` | `bedwars-solo` | Which GameServerSet to scale. |
| `BEDWARS_MIN_SERVERS` | `2` | Floor for the fleet. `BEDWARS_MIN_REPLICAS` is an accepted alias. |
| `BEDWARS_MAX_SERVERS` | `20` | Ceiling for the fleet. `BEDWARS_MAX_REPLICAS` is an accepted alias. |
| `BEDWARS_GAMES_PER_SERVER` | `25` | Planning figure: matches per server. Capacity = servers × this. |
| `BEDWARS_SCALE_DOWN_ENABLED` | `true` | Master switch for reclaiming idle servers. |
| `BEDWARS_IDLE_MINUTES` | `10` | How long the fleet must be idle before an idle server is reclaimed. |
| `BEDWARS_SERVER_PREFIX` | `bedwars-game` | Container name prefix (Docker backend). |
| `BEDWARS_DOCKER_IMAGE` | `bedwars-recoded-game:latest` | Image the Docker backend runs. |
| `BEDWARS_CONTAINER_MEMORY` | `1536m` | Per-container memory cap (Docker backend). |
| `BEDWARS_ARENA_GROUP` | `solo` | Arena group containers join. |
| `BEDWARS_ROLE` | `GAME` | `LOBBY` makes this process the hub instead of a match host. |
| `BEDWARS_LOBBY_WORLD` | `lobby` | World the hub protection rules apply to (lobby role only). |
| `BEDWARS_RETURN_TO_LOBBY` | `true` | Ask the proxy to return finished matches to the hub. |
| `BEDWARS_LEVEL_TYPE` | *(unset)* | Written into `server.properties` before boot; `flat` for a hub. |
| `BEDWARS_TEMPLATE_NAME` | `Glacier` | Which template the entrypoint stages as the main world — `lobby` on the hub. |
| `BEDWARS_CONTROLLER_ADVERTISE_URL` | `http://host.docker.internal:8080` | URL a **newly created** game server is told to report to. |
| `BEDWARS_DOCKER_NETWORK` | `""` | Docker network game containers join so they can resolve the controller by name. |

**`BEDWARS_PROVISIONER` is the setting most likely to be wrong.** It defaults to `KUBERNETES`
because that is the production backend. Any single-host deployment must set it to `DOCKER`, or
the controller will try to scale a GameServerSet that does not exist and report no capacity
forever.

**`BEDWARS_MIN_SERVERS` vs `BEDWARS_MAX_SERVERS` are the fleet bounds, not a replica count.**
`MIN=2` means the controller will not scale below two servers even when idle, if scale-down is
allowed to act. Setting `MIN=0` (as the Compose stack does) permits scaling to zero.

**`BEDWARS_GAMES_PER_SERVER` is a plan, not a promise.** It appears in `/metrics` as
`bedwars_game_capacity` and in `/infra`. Whether 25 matches actually fit on one server is
decided by that server's CPU and RAM — this number is what the controller *reports*, while the
plugin reports what it *has*.

**Aliases are honoured for compatibility:** `BEDWARS_MIN_REPLICAS` and `BEDWARS_MAX_REPLICAS`
are read if the `_SERVERS` forms are absent. This is why the Compose file uses the `_REPLICAS`
spelling and the Kubernetes manifests use `_SERVERS` — both work, and older configs were not
broken by the rename.

#### HTTP, backoff and security

| Variable | Default | Meaning |
|---|---|---|
| `BEDWARS_HTTP_PORT` | `8080` | The control-plane port. |
| `BEDWARS_PREWARM_THRESHOLD` | `2` | Queue depth at which the controller pre-warms a server. |
| `BEDWARS_BASE_BACKOFF_MS` | `500` | Base `retryAfterMillis` handed to the lobby. |
| `BEDWARS_MAX_BACKOFF_MS` | `10000` | Ceiling for that backoff. |
| `BEDWARS_API_TOKEN` | `""` | Shared secret required on mutating endpoints. Set it on the controller *and* on the proxy and game pods, or they are refused. |

**`BEDWARS_BASE_BACKOFF_MS` / `BEDWARS_MAX_BACKOFF_MS`** control the exponential backoff the
controller returns in `DispatchResult.retryAfterMillis`. The backoff grows with queue depth,
from `base` up to `max` (10 seconds). This is load-shedding: a lobby told to wait 10 seconds
will not hammer the controller while a fleet is being created.

**`BEDWARS_PREWARM_THRESHOLD`** is "if this many players are waiting with no capacity, start a
server now rather than waiting for KEDA". It is a latency optimisation; the queue works without
it.

**`BEDWARS_API_TOKEN`** protects `POST /pods/*` and `POST /lobby/queue`. Clients present it as
`X-Bedwars-Token: <token>`, or as `Authorization: Bearer <token>`. The comparison is
constant-time (`MessageDigest.isEqual`), the token is never logged, and no endpoint returns it
— `/infra` reports only `authenticated: true|false`. Read-only endpoints stay open so
monitoring works without the secret. With no token set, the controller logs a loud warning at
startup and stays open: the documented single-host development mode.

The variable has to reach **both halves** of every conversation: the controller checks it, and
the game server's reporter (`HttpPodReporter`) and the proxy's controller client
(`ControllerClient`) present it. Set it on the controller alone and every report and every
enqueue is refused with `401` — the fleet disappears from the registry, which looks like a
broken network rather than an auth error. Every deployment path wires it for you:

| Path | How the token travels |
| --- | --- |
| `install.sh` (Docker) | `BEDWARS_API_TOKEN` in `deploy/compose/.env`; the controller reads it and passes it to each game container it provisions |
| `install.sh` (Kubernetes) | `controller.apiToken` in the values file; the chart creates the `bedwars-controller-token` Secret and mounts it into the controller, the proxy and the GameServerSet |
| Plain manifests | the workloads reference the Secret as `optional`; create it before applying, or the pods report `401` |

Chapter 21 covers this properly; the short version is **never expose an unauthenticated
controller to a network you do not control.**

---

### 17.5 Compose: `.env` and the stack

`deploy/compose/.env.example` is the template; copy it to `.env`. Compose interpolates
`${VAR:-default}`, so every one of these is optional.

| Variable | Default | Meaning |
|---|---|---|
| `BEDWARS_DB_NAME` | `bedwars` | Database name. |
| `BEDWARS_DB_USER` | `bedwars` | Database user. |
| `BEDWARS_DB_PASSWORD` | `bedwars` | **Change this.** |
| `BEDWARS_DB_ROOT_PASSWORD` | `rootpw` | MySQL root password. **Change this.** |
| `BEDWARS_DB_PORT` | `3306` | Host port MySQL is published on. |
| `BEDWARS_S3_ACCESS_KEY` | `minioadmin` | MinIO root user. |
| `BEDWARS_S3_SECRET_KEY` | `minioadmin` | MinIO root password. |
| `BEDWARS_S3_BUCKET` | `bedwars-templates` | Bucket for arena templates. |
| `BEDWARS_MINIO_IMAGE` | `quay.io/minio/minio:latest` | Override to use a mirror. |
| `BEDWARS_MC_IMAGE` | `quay.io/minio/mc:latest` | The MinIO client used by `minio-init`. |
| `BEDWARS_MIN_SERVERS` | `0` | Servers kept running with nobody playing. |
| `BEDWARS_MAX_SERVERS` | `10` | **The cap.** Pre-warming never goes past this. |
| `BEDWARS_GAMES_PER_SERVER` | `25` | Concurrent matches one server hosts. Capacity = max servers x this. |
| `BEDWARS_ARENA_GROUP` | `solo` | Arena group the local fleet plays; queue requests without a group resolve to it. |
| `SERVER_ENGINE` | `spigot` | Engine for the game-pod image build. |
| `SPIGOT_REV` | `26.3` | BuildTools revision. |
| `PAPER_VERSION` | *(empty)* | Required when `SERVER_ENGINE=paper`. |

The `_IMAGE` overrides exist for a practical reason: some networks cannot pull from `quay.io`
anonymously (this project's own test host returned `401 UNAUTHORIZED` for an anonymous pull).
Point them at a mirror you can reach, or disable MinIO and use external storage.

#### The controller's Compose environment

These live in the `x-controller-env` anchor inside `docker-compose.yml` rather than in `.env`,
because they are properties of the stack rather than of your machine:

| Variable | Value | Why |
|---|---|---|
| `BEDWARS_NAMESPACE` | `local` | Not a real namespace; a label for this environment |
| `BEDWARS_GAMESERVERSET` | `bedwars-solo` | Unused by the Docker backend, kept for parity |
| `BEDWARS_HTTP_PORT` | `8080` | |
| `BEDWARS_MIN_REPLICAS` | `0` | Scale to zero when idle |
| `BEDWARS_MAX_REPLICAS` | `10` | Ceiling for one host |
| `BEDWARS_PREWARM_THRESHOLD` | `2` | |
| `BEDWARS_PROVISIONER` | `DOCKER` | **Required.** The default is KUBERNETES |
| `BEDWARS_DOCKER_IMAGE` | `bedwars-spigot:1.0.0` | The image provisioned containers run |
| `BEDWARS_ARENA_GROUP` | `solo` | |
| `BEDWARS_DOCKER_NETWORK` | `bedwars_bedwars` | The pinned network name |
| `BEDWARS_CONTROLLER_ADVERTISE_URL` | `http://controller:8080` | A **name**, not localhost |

#### The game pod's Compose environment

| Variable | Value | Why |
|---|---|---|
| `BEDWARS_SERVER_ID` | `pod-local-1` | This server's identity in reports |
| `BEDWARS_ARENA_GROUP` | `solo` | |
| `BEDWARS_CONTROLLER_URL` | `http://controller:8080` | Where to report |
| `BEDWARS_TEMPLATE_NAME` / `_VERSION` | `Glacier` / `1.0.0` | Which template |
| `BEDWARS_TEMPLATE_SOURCE` | `LOCAL` | Use the baked map; S3 needs MinIO reachable |
| `BEDWARS_TEMPLATE_S3_ENDPOINT` / `_BUCKET` | `http://minio:9000` / `bedwars-templates` | Used if you switch to `S3` |
| `BEDWARS_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASSWORD` | `mysql` / `3306` / … | Database settings |

Note that the game pod's template source is `LOCAL` even though MinIO is running. The baked map
is used because it makes the stack work without depending on an S3 pull — and because a real map
is what makes `/bw join` land somewhere meaningful.

---

### 17.6 Kubernetes: manifest environment

#### GameServerSet (`10-gameserverset.yaml`)

| Item | Value | Meaning |
|---|---|---|
| `spec.replicas` | `0` | Starts with no game servers. KEDA and the controller scale it |
| `serviceName` | `bedwars-solo` | The headless Service OpenKruise maintains |
| container `name` | `gameserver` | Engine-agnostic on purpose |
| `imagePullPolicy` | `IfNotPresent` | Use a locally loaded image; OpenKruise would otherwise default to `Always` |
| `BEDWARS_ARENA_GROUP` | `solo` | |
| `BEDWARS_TEMPLATE_SOURCE` | `S3` | Production path |
| `BEDWARS_CONTROLLER_URL` | `http://bedwars-controller:8080` | |
| S3 endpoint/bucket | from ConfigMap `bedwars-s3` | `configMapKeyRef` |
| S3 credentials | from Secret `bedwars-s3-credentials` | `secretKeyRef` — never inline |
| `resources.requests` | `cpu: 1`, `memory: 2Gi` | What the scheduler reserves |
| `resources.limits` | `cpu: 2`, `memory: 4Gi` | The ceiling before throttling/OOMKill |
| `readinessProbe` | TCP 25565, delay 20 s, period 5 s | "Accepting players yet?" |
| `livenessProbe` | TCP 25565, delay 60 s, period 15 s, 4 failures | "Wedged and needs a restart?" |
| `preStop` | `sleep 5` | Window for the DRAINING report |
| `terminationGracePeriodSeconds` | `60` | Time for an in-flight match to finish |

#### Controller (`40-controller.yaml`)

`replicas: 1`, plus `BEDWARS_NAMESPACE`, `BEDWARS_GAMESERVERSET`, `BEDWARS_HTTP_PORT`,
`BEDWARS_MIN_REPLICAS: 0`, `BEDWARS_MAX_REPLICAS: 50`, `BEDWARS_PREWARM_THRESHOLD: 2`.

Its requests are small (`200m` CPU, `256Mi`) because the controller only coordinates — it does
no game work. Its limits are `1` CPU and `512Mi`.

The RBAC is worth reading, because it is the minimum viable permission set:

```yaml
rules:
  - apiGroups: ["game.kruise.io"]
    resources: ["gameserversets", "gameserversets/status"]
    verbs: ["get", "list", "watch", "update", "patch"]
  - apiGroups: [""]
    resources: ["pods", "services", "endpoints"]
    verbs: ["get", "list", "watch"]
```

It can read pods/services and **update a GameServerSet's spec** — and nothing else. It cannot
create pods, delete pods, or touch any other namespace. Scale-to-one-replica is a real
limitation (the controller is not highly available), and it is stated rather than hidden.

#### Velocity and mc-router

Velocity: `replicas: 2`, `CONTROLLER_URL`, requests `500m`/`1Gi`, limits `2`/`2Gi`. Two replicas
because the proxy is the front door: losing it logs everyone out.

mc-router: `mapping: "*.bedwars.local=velocity:25565"` and a LoadBalancer Service. On minikube
it is set to `NodePort` because a local cluster has no load-balancer controller.

#### NetworkPolicies (`13-networkpolicy.yaml`)

Three policies, and the shape is the point:

1. **`default-deny-ingress`** — nothing is reachable by default.
2. **`controller-ingress`** — port 8080 from any pod in the namespace.
3. **`game-pod-ingress`** — port 25565 from mc-router or Velocity **only**.

That third policy is what enforces "game servers are not directly reachable". A player can
never dial a game pod; the queue and the proxy are the access path.

---

### 17.7 Helm values

`deploy/helm/bedwars/values.yaml`, overridden with `-f` or `--set`.

| Value | Default | Meaning |
|---|---|---|
| `image.registry` | `""` | Registry prefix (`ghcr.io/you`). Empty = local images |
| `image.tag` | `1.0.0` | Image tag for all three application images |
| `image.pullPolicy` | `IfNotPresent` | |
| `arena.group` | `solo` | Arena group this release serves |
| `arena.templateSource` | `S3` | `S3` or `LOCAL` |
| `arena.templateName` | `Glacier` | |
| `arena.templateVersion` | `1.0.0` | |
| `arena.templateEnabled` | `false` | Whether the **plugin** also copies worlds per match |
| `arena.teamCount` / `playersPerTeam` | `2` / `2` | |
| `gameServerSet.enabled` | `true` | Requires the OpenKruise CRD; `false` installs the persistent tier only |
| `gameServerSet.name` | `bedwars-solo` | |
| `gameServerSet.replicas` | `0` | Start empty |
| `gameServerSet.cpuRequest` / `memoryRequest` | `1` / `2Gi` | |
| `gameServerSet.cpuLimit` / `memoryLimit` | `2` / `4Gi` | |
| `gameServerSet.jvmOptions` | `-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` | **Tune to the limit** |
| `gameServerSet.terminationGracePeriodSeconds` | `60` | |
| `keda.enabled` / `minReplicas` / `maxReplicas` | `true` / `0` / `50` | |
| `keda.pollingInterval` / `cooldownPeriod` | `10` / `120` | Seconds |
| `keda.prometheusAddress` | `http://prometheus.monitoring:9090` | Where KEDA reads metrics |
| `karpenter.enabled` / `nodePool` | `false` / `bedwars-games` | Node autoscaling is usually platform-owned |
| `mcRouter.replicas` / `mapping` / `service.type` | `1` / `*.bedwars.local=velocity:25565` / `LoadBalancer` | |
| `velocity.replicas` | `2` | |
| `controller.replicas` | `1` | |
| `s3.endpoint` / `bucket` / `region` | `http://minio:9000` / `bedwars-templates` / `us-east-1` | |
| `s3.accessKey` / `secretKey` | `minioadmin` / `minioadmin` | **Replace. Prefer an existing Secret** |
| `mysql.enabled` / `image` / `storage` | `true` / `mysql:8.4` / `10Gi` | `enabled: false` for an external database |
| `mysql.database` / `username` / `password` / `rootPassword` | `bedwars` / … | |
| `minio.enabled` / `image` / `storage` | `true` / `quay.io/minio/minio:latest` / `5Gi` | |
| `serviceMonitor.enabled` | `true` | Requires the Prometheus Operator |
| `networkPolicy.enabled` | `true` | |
| `pdb.enabled` | `true` | |

Three values deserve more than a table row:

**`gameServerSet.jvmOptions` must be tuned to `gameServerSet.memoryLimit`.** The heap percentage
is a percentage **of the container limit**. Leaving `MaxRAMPercentage=65` while dropping the
limit to 1.5 GiB gives you a smaller absolute heap — which is correct — but leaving the
percentage at `65` while raising the limit to 8 GiB gives a heap that may not leave room for
everything else. Change them together. See chapter 22.

**`arena.templateEnabled` is `false` by default and that is intentional.** The container
entrypoint has already staged the pod's main world, so the plugin does not need to do anything.
Set it `true` only when you use the multi-game path and want a fresh world copied per match.

**`minio.enabled: false` and `mysql.enabled: false` are the production settings** if you have
managed services. They are not "reduced" modes; they exist so an operator with an existing
database and an existing S3 bucket does not deploy duplicates.

---

### 17.8 Docker build arguments

| Argument | Where | Default | Meaning |
|---|---|---|---|
| `SERVER_ENGINE` | gameserver | `spigot` | `spigot` or `paper` |
| `SPIGOT_REV` | gameserver | `26.3` | BuildTools revision |
| `PAPER_VERSION` | gameserver | *(empty)* | Paper Minecraft version |
| `PAPER_BUILD` | gameserver | *(empty)* | Pin a Paper build |
| `PAPER_CHANNEL` | gameserver | `STABLE` | `STABLE` or `EXPERIMENTAL` |
| `BUILD_TOOLS_URL` | gameserver | SpigotMC Jenkins | Where BuildTools comes from |
| `WITH_DOCKER_CLI` | controller | `false` | Install the docker CLI (needed for the Docker provisioner in a container) |

`WITH_DOCKER_CLI` is off by default so the Kubernetes controller image stays small. Turn it on
only when the controller itself runs in a container and provisions sibling containers — which
is exactly what the Compose stack does.

#### Runtime environment inside the game image

| Variable | Default | Meaning |
|---|---|---|
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` | JVM flags, applied to every JVM in the container |

And the entrypoint's own variables, which are how a pod is told what world to load:

| Variable | Default | Meaning |
|---|---|---|
| `BEDWARS_TEMPLATE_SOURCE` | `LOCAL` | `LOCAL` or `S3` |
| `BEDWARS_TEMPLATE_NAME` | `Glacier` | Template name |
| `BEDWARS_TEMPLATE_VERSION` | `1.0.0` | Object-key version |
| `BEDWARS_TEMPLATE_LOCAL_ROOT` | `/templates` | Where LOCAL templates live in the image |
| `BEDWARS_TEMPLATE_FORCE` | `false` | `true` = re-stage even if a world already exists |
| `BEDWARS_TEMPLATE_S3_ENDPOINT` | — | S3 endpoint |
| `BEDWARS_TEMPLATE_S3_BUCKET` | — | S3 bucket |

`BEDWARS_TEMPLATE_FORCE` is worth knowing. By default, if a world already exists at
`/server/world` the entrypoint does **not** re-stage, which makes a restarted container cheap.
Set it to `true` when you need certainty that a pod starts from the pristine template — for
example when debugging "why does my arena look wrong?".

---

## 18. Running and operating a match

### 18.1 The lifecycle of a match

```
lobby queue request
      |
      v
controller: is a Ready pod available?
      |                       |
     yes                      no
      |                       |
      v                       v
allocate slot          pre-warm + retryAfterMillis
(atomic)                      |
      |                       v
      |                new pod boots, reports /pods/ready
      |                       |
      +-----------+-----------+
                  |
                  v
        player transferred to the game server
                  |
                  v
        countdown (countdown-seconds) -> match begins
                  |
                  v
        sudden death (sudden-death-after-seconds) -> dragons
                  |
                  v
        a team wins, or everyone is eliminated
                  |
                  v
        /pods/ended -> slot released -> pod becomes Ready again
                  |
                  v
        (or) scale-down reclaims an idle pod
```

Three things about this diagram are design decisions rather than mechanics:

- **Allocation is atomic.** A pod is removed from the ready pool the *instant* it is allocated,
  so two simultaneous requests can never be handed the same slot. Duplicate matchmaking cannot
  double-book.
- **"No capacity" is not a failure.** `retryAfterMillis` is the controller saying "wait, I am
  making room". The lobby retries.
- **The pod survives the match, by default.** A finished match releases its slot and the server
  goes back in the pool. The pod dies when the *fleet* shrinks, not when a match ends.

### 18.2 The plugin's own state machine

| Phase | Meaning |
|---|---|
| Waiting | Booted, arena loaded, nobody assigned yet |
| Countdown | Players present, timer running |
| Running | Match in progress |
| Sudden death | Dragons active, map being destroyed |
| Ended | A winner exists; result being persisted and reported |
| Draining | Shutting down; refuses new joins, reports to the controller |

The **draining** phase is what makes pod replacement safe. On `SIGTERM` the shutdown hook
reports `DRAINING`; the controller removes the pod from the pool so a later queue request is
never dispatched to a server that is going away. Then `preStop`'s five seconds elapse, then the
match has up to `terminationGracePeriodSeconds` to finish.

### 18.3 In-game commands

The root command is `/bedwars`, aliased `/bw`.

| Command | Who | What |
|---|---|---|
| `/bw join` | everyone | Join the match on this server |
| `/bw shop` | everyone | Open the item shop |
| `/bw lang` | everyone | Language selection |
| `/bw status` | everyone | Current match state — the main diagnostic |
| `/bw start` | admin | Force the match to start |
| `/bw stop` | admin | Stop the match |
| `/bw reload` | admin | Reload configuration |
| `/bwadmin status` | admin | Network-wide: fleet, capacity, queue, and a diagnosis (chapter 9.4.12) |
| `/bwadmin servers` | admin | Every game server, its arena group, its free match slots |
| `/bwadmin queue` | admin | Who is waiting, per arena group |
| `/bwadmin infra` | admin | How the controller is provisioning |

The gameplay subcommands are open to all players **on purpose**. A blanket permission on the
root command blocked `/bw join` for ordinary players (chapter 17.3); only the three
administrative subcommands are gated.

`/bw status` is the one to reach for first when something looks wrong: it reports the current
phase and match state from inside the server, which tells you whether a problem is "the game
never started" or "the player never arrived".

### 18.4 Driving a server without a client

There is no console stdin available in a container, so two tools exist.

**RCON** — a TCP protocol for sending commands to a running server:

```bash
$ deploy/tools/rcon.py "bw status"
```

`deploy/tools/rcon.py` is a dependency-free Source-RCON client: it authenticates, runs a
command, and prints the reply. This is how the match lifecycle, the dragon phases and the
map-destruction counters were verified during development. It requires RCON to be enabled in
`server.properties` (`enable-rcon=true`, plus a port and password).

**Server List Ping** — the protocol a Minecraft client uses to show a server in its list:

```bash
$ deploy/tools/server-status.py localhost 25565
```

Answers "is it up, what version, how many players" without authenticating or joining. Useful as
an external health check and as a cheap assertion in a script.

**A real client**, for anything gameplay-related:

```bash
$ deploy/tools/join-server.sh            # localhost:25565 -> the running game server
$ NAMESPACE=bedwars deploy/tools/join-server.sh 25570
```

The script port-forwards a game pod so a client can reach it. Under Compose the port is already
published, so connect straight to `localhost:25565`. In production players never dial a pod
directly — they arrive through Velocity.

### 18.5 Reading the logs

| Log line | Means |
|---|---|
| `[entrypoint] staging local template 'Glacier'` | The world is being staged before boot |
| `[entrypoint] arena ready: 12M world at /server/world` | A real world is in place |
| `[entrypoint] WARNING: no arena world staged` | Staging failed; a generated world will be used |
| `bedwars_setup ...` | The plugin's startup summary — the single most useful line |
| `whitelist_ok already off` | The whitelist guarantee ran |
| `Server ... READY for group solo (capacity N matches)` | The controller registered a server |
| `Server ... capacity report: N free match slots` | A running server reported free capacity |
| `Match ended on ...; slot released` | A slot came back to the pool |
| `Server ... removed from the pool` | A draining server left the pool |
| `Rejected unauthenticated request to ...` | A token is configured and a caller lacked it |

The controller's `Server ... READY` line and the plugin's `bedwars_setup` line together prove
the loop is closed: the server knows where to report, and the controller accepted it.

### 18.6 Metrics

`/metrics` is Prometheus text exposition:

| Metric | Type | Meaning |
|---|---|---|
| `bedwars_queue_depth{group}` | gauge | Players waiting, per arena group |
| `bedwars_free_slots{group}` | gauge | Free match slots available right now |
| `bedwars_servers` | gauge | Servers the provisioner manages |
| `bedwars_idle_servers` | gauge | Servers with no match running |
| `bedwars_game_capacity` | gauge | Total concurrent game slots |

`bedwars_game_capacity` is `servers × gamesPerServer` and is the fleet's theoretical ceiling.
`bedwars_free_slots` is what is actually available at this moment. A large gap between them is
healthy — it means matches are running.

---

## 19. Verification: what each tool proves

This project ships its verification, and each piece is worth understanding separately, because
"We ran the tests" is not one claim.

### 19.1 The test suite

```bash
$ mvn clean install
```

189 tests: API 5, Core 95, Spigot 28, Controller 61. Zero skipped — a skipped test is a test
that did not run, and the Docker integration test once skipped silently for exactly that reason
(a 15-second `docker version` probe timing out under load; it is 45 seconds now and the wait is
logged).

What the layers prove:

| Layer | Proves |
|---|---|
| Core (95) | The game rules are right, with an injected clock. Beds, eliminations, sudden death, ELO |
| Spigot (28) | The Bukkit adapters wire those rules correctly; no Paper-only API is used |
| API (5) | The wire contract holds |
| Controller (61) | Matchmaking, atomic allocation, scale-down policy, the HTTP surface, jar resolution |

### 19.2 `deploy/verify_deploy.py` — 88 structural checks

```bash
$ python3 deploy/verify_deploy.py
DEPLOY VERIFY: OK (119 checks passed)
```

It reads *your actual files* and asserts the deployment contract: that every pod declares
requests and limits, that the manifests name `gameserver.Dockerfile` and not the removed
`spigot.Dockerfile`, that no standalone/deployment-mode remnants exist anywhere, that the server
jar resolution order is implemented, that secrets are references rather than literals. It runs
without a cluster, which is what makes it useful in CI.

It needs Python 3 and `PyYAML`.

### 19.3 `deploy/verify_k8s.sh` — render and validate

```bash
$ bash deploy/verify_k8s.sh
Summary: 58 resources found in 2 files - Valid: 51, Invalid: 0, Errors: 0, Skipped: 7
K8S VERIFY: OK
```

It renders the Helm chart and the Kustomize base into plain YAML, then schema-validates with
`kubeconform`. This catches the class of error that `kubectl apply` will happily accept on a
cluster that then misbehaves: a typo'd field name, a wrong type, an invalid port.

The 7 skipped resources are the custom kinds (`GameServerSet`, `ScaledObject`, `NodePool`) for
which kubeconform has no bundled schema. They are checked structurally by
`verify_deploy.py` instead.

Requirements: `helm`, `kubectl`, `kubeconform` on `PATH` (overridable with the `HELM`,
`KUBECTL`, `KUBECONFORM` environment variables).

### 19.4 `deploy/tools/test-resolve-server-jar.sh` — 13 assertions

```bash
$ bash deploy/tools/test-resolve-server-jar.sh
resolve-server-jar: 13 passed, 0 failed
```

This one tests a *decision procedure*, which is why it deserves its own place. It asserts:

- a supplied jar is used, and nothing is compiled;
- `SERVER_ENGINE=paper` with a Spigot jar present **ignores** the Spigot jar (the bug that
  silently shipped a "Paper" image containing Spigot);
- an ambiguous `server-jars/` containing two engines fails rather than guessing;
- an unknown Paper version fails with a clean message instead of a traceback and a second,
  unrelated error.

Two real bugs were found by writing these assertions, both in error paths that a happy-path
test would never reach. That is the argument for testing the failure branches.

### 19.5 What has been proven live, and how

Claims in this manual that rest on a real execution rather than on reading code:

| Claim | How it was proven |
|---|---|
| The image contains a real Spigot server | Built and booted; `Done (N s)!` from the server |
| The supplied-jar fast path works | 23-second image build, then `sha256sum` of `/server/server.jar` compared to the supplied file — identical |
| The Paper path produces Paper | `Main-Class: io.papermc.paperclip.Main` read from the built image |
| A game server registers with the controller (Docker) | `/infra` showing `registeredServers: 1`, `freeSlots: 25` |
| A game server registers with the controller (Kubernetes) | Same, through a port-forward, with the pod running from a GameServerSet |
| The queue allocates a slot | `POST /lobby/queue` returning the pod address, then `freeSlots` dropping 25 -> 24 |
| The arena is staged at boot | `[entrypoint] arena ready: 12M world` |
| The whitelist guarantee applies | `whitelist_ok already off` |
| Pods are not over-provisioned | One dispatch produced one container (it once produced thirteen) |
| **A queued player is actually placed in a match** | `deploy/tools/test_dispatch_live.py` against a running controller: a forged registration under `solo`, then the proxy's own request body on `/lobby/queue` with no group, returned `podAddress` (it once returned nothing, forever) |
| **The fleet stops at the configured maximum** | The same run kept asking for capacity while counting containers: peak 2 against a configured `BEDWARS_MAX_SERVERS=2` — and the pre-warm path started real `bedwars-game-N` containers that booted and registered under `solo` before dispatching to them |
| **A timed-out request does not leak into the queue** | Three capacity-less requests in a row, then `/queue/depth` = 0 (the depth is what pre-warming scales on) |
| **The controller receives the configured capacity** | `/infra` reporting `maxServers: 2, gamesPerServer: 2` from `.env`, and the startup line `servers[0..2] gamesPerServer=2` |
| The Kubernetes pod runs the right jar | `sha256sum` inside the pod matched the supplied jar |

### 19.6 What has *not* been proven live

Stated plainly, and repeated in Appendix E: the S3/MinIO template path has not been exercised
end to end in this environment (the MinIO image cannot be pulled anonymously here, and both
live runs used the baked `LOCAL` template). A Velocity player-transfer hop and a Slime world
swap have not been driven by a real client, and a full multi-player match has not been played
to completion on a cluster. Everything in those paths is unit-tested and structurally verified;
they are not the same thing as proven.

---

## 20. Troubleshooting

Organised by symptom, because that is how you will arrive here.

### 20.1 Build failures

**`invalid target release: 25`**
Maven is using a JDK older than 25. `java -version` and Maven's JDK can differ — read the JDK
listed by `mvn -version` and fix `JAVA_HOME` (chapter 13.2).

**`mvn: command not found`**
Maven is not installed or not on `PATH`. Install 3.9+ and re-open the shell.

**A test fails only on a machine with load**
The Docker integration test probes `docker version` with a timeout and marks itself skipped if
the probe fails. Under load it once exceeded 15 seconds. It is 45 seconds now. If it still
skips, the daemon is genuinely unreachable — check `docker version` by hand.

**Build succeeds but the jar is missing**
`mvn clean install` at the root builds all modules. If you ran `mvn package` in a subdirectory
without `-am`, dependencies may be absent. Use `-pl <module> -am`.

### 20.2 Compose

**`/infra` says `"provisioner": "KUBERNETES"` and capacity is always 0**
`BEDWARS_PROVISIONER` is not set to `DOCKER`. This is the most common misconfiguration on this
path. It is set in the `x-controller-env` anchor of the Compose file; if you copied the file or
supplied your own environment, check it survived.

**The controller cannot start game containers (`docker: not found`)**
The controller image was built without the docker CLI. The Compose stack passes
`WITH_DOCKER_CLI: "true"`; check that build argument is present.

**Game containers start but never register**
The controller's advertise URL is wrong. It must be a name both containers can resolve
(`http://controller:8080`), because the controller passes it to the new container. `localhost`
inside the controller is the controller's own loopback, and the new game container will fail to
report.

**Players queue and are never moved, while containers keep multiplying**
Two separate causes produced this, and both are fixed. If you are on an older checkout, upgrade.

*Nothing was ever dispatched.* The proxy asked for arena group `any` while servers registered
under their real group (`solo`), so no registered server ever matched a waiting player even though
free capacity existed. Run `/bwadmin status`: if it shows waiting players **and** free slots, that
was the bug. The controller now treats `any` (and an absent or blank group) as "whatever this
network runs".

*More servers than the maximum.* The installer asked for min/max/games-per-server and wrote them
into `.env`, but under names the Compose controller never read, so it silently ran on its own
default of **10** regardless of what you configured. `BEDWARS_MAX_SERVERS`, `BEDWARS_MIN_SERVERS`
and `BEDWARS_GAMES_PER_SERVER` are now the names on both sides (chapter 17.5). Confirm what the
controller actually believes with `/bwadmin status`: the `limits` line must show your numbers. A
fleet that grows past your maximum means that line is showing something else.

Both are visible from in-game: `/bwadmin status`, `/bwadmin servers` (does anything show as idle
with free slots?), `/bwadmin queue`.

**`docker compose up` fails pulling `quay.io/minio/*` (401 UNAUTHORIZED)**
Some networks cannot pull these anonymously. Either point `BEDWARS_MINIO_IMAGE` and
`BEDWARS_MC_IMAGE` at a mirror you can reach, or run the stack without MinIO and use the baked
`LOCAL` template (chapter 17.5).

**MySQL credentials "do not work" after changing them**
MySQL initialises users only on a **first, empty** start. Stale volume, stale password. Use
`docker compose down -v` to get a genuinely clean slate.

**The game pod logs `WARNING: no arena world staged`**
The entrypoint could not find or unpack the template. Check `BEDWARS_TEMPLATE_SOURCE` and the
name; under `LOCAL`, confirm `/templates/<name>/level.dat` exists in the image. The server still
starts, on a generated world — the log says so explicitly.

### 20.3 Kubernetes

**`error: unable to recognize ...: no matches for kind "GameServerSet"`**
The OpenKruise CRD is not installed. Install OpenKruise and kruise-game first (chapter 8.1).
Note that `kubectl apply -k` does not fail loudly for this — it reports the unknown kind and
continues, so the platform comes up with no game servers and no obvious error.

**A pod is stuck in `ImagePullBackOff`**
Either the image is not in the cluster, or the manifest does not say `IfNotPresent`. Verify the
image first:

```bash
$ minikube image ls | grep bedwars
```

`minikube image load` prints a warning and **still exits 0** if the image is not on the host
daemon — so an `&& echo ok` check lies to you. Also check the tag: an image tagged `:latest` on
the host is not `:1.0.0` in the cluster.

**The API server becomes unresponsive; `kubectl` commands hang**
The cluster is out of memory. minikube's default allocation (~2 GiB) cannot hold an API server,
MySQL and a Spigot pod. Restart with more:

```bash
$ minikube stop
$ minikube start --cpus=3 --memory=4096
```

**A game pod is `OOMKilled`**
The heap is too large for the limit, or the limit is too small for a real arena. A 1.5 GiB pod
was OOMKilled loading a real arena during this project. Fix the limit **and** the heap
percentage together (chapter 22).

**Players cannot reach a game pod**
That is the intended default. `game-pod-ingress` allows 25565 from mc-router and Velocity only.
In production, players arrive through the proxy; for direct testing, port-forward
(`deploy/tools/join-server.sh`).

**Game pods never appear despite a queue**
Check, in order: is KEDA installed and is its Prometheus trigger reachable? Is the
`GameServerSet` at replicas > 0 (`kubectl -n bedwars get gameserversets`)? Is the
`ServiceMonitor` actually scraped, so `bedwars_queue_depth` exists in Prometheus? KEDA scaling
a metric that does not exist does nothing, quietly.

**`/infra` shows `registeredServers: 0` right after a pod becomes Ready**
Likely a timing artefact, not a fault: the pod's ready report lands a moment after the process
starts. Poll for a few seconds before concluding anything. (This produced a real false alarm
during verification.)

### 20.4 Gameplay and the plugin

**Players are rejected with "You are not whitelisted on this server!"**
`server.force-whitelist-off` is `LEAVE` and the server's whitelist is on and empty. Set it to
`OFF` (the default), or add your players. The queue is supposed to be the access control.

**The server starts but the game never begins**
Check `/bw status`. If the phase never leaves Waiting, no players were assigned — a controller
or queue problem, not a game problem.

**Heavy repeated connection errors to the controller in the log**
Expected behaviour is: one full error, then at most one summary per
`failure-log-interval-seconds`, then silence after `disable-reporting-after-failures`. If you
see a flood, those two settings were raised or removed.

**The arena looks wrong / players fall through the world**
The template was not staged (see the entrypoint warning) or `arena.yml` coordinates disagree
with the map. Force a re-stage with `BEDWARS_TEMPLATE_FORCE=true` and check the `bedwars_setup`
line to confirm which template the server believes it is running.

**Beds are unbreakable, or breakable through protection**
`bed-protection-radius` and the `teams[].bed` coordinates disagree. It is a distance check
against a recorded position; if the record is wrong, the behaviour is wrong.

---

## 21. Security

Security is not a chapter bolted on at the end; several decisions in this project are security
decisions that happen to also be performance or architecture decisions. This chapter collects
them, then covers the operational practices.

### 21.1 The threat model, stated

Be explicit about what is being defended against, because "is this secure?" is unanswerable
otherwise.

| Asset | Threat | Mitigation in this project |
|---|---|---|
| The controller's mutating API | Anyone on the network enqueues players or fakes pod reports | `BEDWARS_API_TOKEN`, constant-time compare, never logged |
| The host, via the controller | A compromised controller with the Docker socket is root on the host | Documented as local-development-only; Kubernetes path avoids it |
| Game pods | Direct connections bypassing the queue | NetworkPolicy: 25565 from mc-router/Velocity only |
| Database credentials | Leakage through logs, images or git | Environment variables / Kubernetes Secrets; `.env` gitignored |
| S3 credentials | Same | `secretKeyRef`, not literals; `REPLACE_ME` placeholders in the example |
| The plugin jar | A bundled driver copied into every deployment | Libraries fetched at runtime from declared coordinates |

### 21.2 The controller token

Set `BEDWARS_API_TOKEN` to require authentication on every mutating endpoint
(`POST /pods/*`, `POST /lobby/queue`). Clients present it as `X-Bedwars-Token: <token>` or
`Authorization: Bearer <token>`.

Properties, as implemented:

- **Constant-time comparison** (`MessageDigest.isEqual`). An attacker cannot learn the token
  byte-by-byte from response timing.
- **Never logged.** Not at startup, not on failure. A rejected request logs the path, not the
  presented secret.
- **Never returned.** `/infra` reports only `authenticated: true|false`.
- **Read-only endpoints stay open** (`/healthz`, `/metrics`, `/queue/depth`,
  `/lobby/arena-status`, `/infra`) so monitoring does not need the secret. This is a considered
  trade-off: it means an attacker who reaches the port can learn your fleet's size. That is
  usually acceptable; if it is not, put those endpoints behind your own ingress rules.
- **Blank token = open, with a loud warning.** The controller logs
  `Controller HTTP listening on :8080 WITHOUT authentication. Set BEDWARS_API_TOKEN before
  exposing this controller to other machines.` This is the documented single-host development
  mode.

**Never commit the token.** Supply it as an environment variable or a Kubernetes Secret. And
note the rotation consequence: the token is read once at startup, so rotating it means
restarting the controller and every game server that reports to it.

**Both halves are implemented.** The controller checks the header on every mutating request, and
every shipped client presents it:

| Client | Where | What it sends |
| --- | --- | --- |
| Game server reporter | `HttpPodReporter` (`/pods/*`) | `X-Bedwars-Token` when a token is configured, and no header at all when it is not |
| Velocity proxy | `ControllerClient` (`/lobby/queue`) | the same header |
| Provisioner | `DockerProvisioner` | passes `BEDWARS_API_TOKEN` into each container it starts, so a pod can report from its first heartbeat |
| Chart | `bedwars-controller-token` Secret | mounted into the controller, the proxy and the GameServerSet via `secretKeyRef` with `optional: true` |

A blank token means **no header at all**, not an empty one: the controller treats a presented
empty credential as a failed match, so the blank case has to be genuinely absent. Three tests
hold that down — `HttpPodReporterTest` reads the header off a real HTTP server,
`DockerProvisionerTest` asserts the `-e BEDWARS_API_TOKEN=` pair is present when set and absent
when blank, and the controller's own auth tests cover the `401` path.

The failure mode to know is a **mismatch**, which is indistinguishable from a broken network
until you look: reports come back `401`, the registry stays empty, queues are refused. Each
client logs which state it is in — a game server prints `controller_auth=token|none` in its
`bedwars_setup` line and the proxy prints `controller_auth=token|none` when it initialises — so
a mismatch is one line per client to find. `install.sh` asserts the state after the stack is up
(`/infra`'s `authenticated` flag) rather than assuming it.

### 21.3 Credential handling rules

Three rules, and they are absolute in this project:

1. **Credential values are never written into logs** or returned by any endpoint.
2. **Credential values are never committed.** `.env` is gitignored; the Kubernetes example
   uses `REPLACE_ME`; the database password in `config.yml` is a development default that the
   manual tells you to change.
3. **Secrets are references, not literals.** Kubernetes manifests use `secretKeyRef`;
   Compose interpolates from `.env`.

A plain Kubernetes `Secret` is **base64, not encryption**. Anyone with read access to the
namespace can decode it. For real clusters use ExternalSecrets, SealedSecrets, or a KMS-backed
provider — the example file says so where it would otherwise be tempting to paste a real
key.

`server.properties` inside the game image declares `online-mode=false`. That is deliberate:
game servers sit behind Velocity, which authenticates players, and a pod with online-mode on
would reject the already-authenticated connection. The consequence is that a game pod must
**never** be reachable from the internet directly — which is exactly what the NetworkPolicies
enforce. Do not remove them without understanding this.

### 21.4 The Docker socket

The Compose stack mounts `/var/run/docker.sock` into the controller so it can start sibling
containers. **That is root-equivalent access to the host.** Anyone who can execute code in that
container can start a privileged container and own the machine.

It is acceptable for local development and unacceptable in production, and both the Compose
file and this manual say so. The Kubernetes path avoids it entirely: the controller talks to
the Kubernetes API with a ServiceAccount scoped to exactly two resource types, and cannot
create pods at all (chapter 17.6).

### 21.5 Least privilege in the cluster

The controller's Role is deliberately tiny:

| It can | It cannot |
|---|---|
| read pods, services, endpoints | create, delete or exec into pods |
| read and update `gameserversets` and their status | touch Deployments, Secrets, or other namespaces |
| | escalate its own permissions (no RBAC write) |

If you need the controller to do more, add the permission explicitly and knowingly. The value
of a narrow Role is that a compromised controller is a compromised *matchmaker*, not a
compromised cluster.

### 21.6 Network posture

- **Default-deny ingress** in the namespace, then explicit allows. New services are unreachable
  until someone says otherwise, which is the correct default.
- **Game pods are not service-exposed.** No Service selects them for player traffic, and the
  NetworkPolicy restricts ingress to the proxy and the router.
- **The control plane is ClusterIP** in Kubernetes. It is not exposed by default; use
  `port-forward` to reach it. That is deliberate, and it is why `port-forward` appears
  throughout chapter 16.
- **MySQL and MinIO are cluster-internal.** No LoadBalancer, no NodePort.

### 21.7 What you must do before exposing this to the internet

A checklist, in order of importance:

1. **Set `BEDWARS_API_TOKEN` on the controller *and* on everything that reports to it** (see
   21.2). `install.sh` does all of it: it generates the token, writes it into the config and
   hands it to the pods and the proxy. By hand it is four places — controller, Velocity, the game
   pods and anything provisioned earlier — and a client that lacks it is refused with `401`,
   which looks like the fleet vanishing rather than an auth error. Confirm it with `/infra`
   (`"authenticated": true`) and with the `controller_auth=` field in a game server's
   `bedwars_setup` line. §21.6 covers the ClusterIP-only posture that protects the read-only
   endpoints.
2. **Change every default password**: MySQL user and root, MinIO/S3 keys, the database password
   in `config.yml`.
3. **Do not mount the Docker socket** in production. Use the Kubernetes path.
4. **Do not run the Compose path on a public host.** Use Kubernetes (chapter 16), and put the
   control plane behind your ingress rules.
5. **Use real secrets management** rather than plaintext Secrets.
6. **Keep `online-mode=true` on Velocity** — it authenticates players, and it is the only
   authentication in the chain. `BEDWARS_OFFLINE_MODE=true` (§9.4.8) deliberately turns it off
   for testing and must never reach a public host: it lets anyone join as anyone.
7. **Verify the NetworkPolicies are actually enforced.** Some CNI plugins ignore them
   silently. Test by trying to reach a game pod from a pod that should not be able to.
8. **Restrict who can `kubectl exec`.** Anyone who can exec into a game pod can read its
   environment, which includes the database and S3 credentials.
9. **Back up MySQL.** It is the only state in the system that matters.
10. **Pin image digests** rather than trusting movable tags.

---

## 22. Capacity and performance

### 22.1 The capacity equation

```
total game slots = servers × games-per-server
```

Both terms are configuration:

| Term | Configured by | Where it is reported |
|---|---|---|
| `servers` | `BEDWARS_MIN_SERVERS` / `BEDWARS_MAX_SERVERS`, KEDA, the platform | `bedwars_servers` |
| `games-per-server` | `BEDWARS_GAMES_PER_SERVER` (plan) and `arena.games-per-server` (actual) | `bedwars_game_capacity` |

The equation is a **planning** figure, and the distinction matters. The controller reports
`servers × gamesPerServer`; what actually fits on a host is decided by that host's CPU and RAM.
With `games-per-server: 25` and 50 servers, the ceiling is 1,250 concurrent matches — a number
nobody should believe until they have measured. What the number *is* good for is reasoning about
the fleet: doubling the servers or the games-per-server doubles the plan, and both are one
setting.

### 22.2 What a Minecraft server actually costs

A Minecraft server is unusual: it is **single-threaded for game logic** and its cost is
dominated by **tick time**. Twenty ticks per second is the target; every player, entity,
block-change and plugin task must be processed in that budget. Miss it and the server is
"lagging" — players rubber-band, blocks break slowly, and eventually the server disconnects
people.

The consequences are unintuitive:

- **CPU cores beyond ~2-4 barely help one server.** Main-thread work cannot be parallelised;
  extra cores serve only world loading, Netty (networking) and the garbage collector. This is
  the single most important performance fact in this document.
- **Therefore you scale out, not up.** Many modest servers beat one large one — which happens
  to be exactly what this architecture does. The design and the hardware reality agree.
- **Memory scales with players, loaded chunks and worlds**, in that order. `view-distance` and
  `simulation-distance` are the largest levers.
- **The heap is not the whole story.** Metaspace, thread stacks, Netty's direct buffers and the
  JVM itself live *outside* the heap, inside the same container limit.

The image therefore pre-seeds:

```
view-distance=6
simulation-distance=4
max-players=40
spawn-protection=0
```

| Setting | Why |
|---|---|
| `view-distance=6` | How far players can see. Each step is roughly linear in world loading and a large chunk of memory |
| `simulation-distance=4` | How far the server *ticks* entities. Cheaper than view distance, and the first thing to lower |
| `max-players=40` | A conservative per-server ceiling. Under the Velocity/queue model, more players per pod is rarely the goal |
| `spawn-protection=0` | Vanilla spawn protection would fight the game: a BedWars arena is not a spawn area |
| `white-list=false` | Stated explicitly (chapter 17.1) |

### 22.3 Sizing the game pod

The image's default JVM flags:

```
-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
```

| Flag | Reasoning |
|---|---|
| `MaxRAMPercentage=65` | `-Xmx` as a *percentage of the container limit*, so the flag stays correct when you resize the pod. 65 % leaves ~35 % for metaspace, direct buffers, thread stacks and the JVM |
| `UseG1GC` | G1 is the safer default in a container. ZGC's native overhead does not fit a small limit well — a 1.5 GiB pod OOMKilled loading a real arena with it |
| `ExitOnOutOfMemoryError` | A pod that limps after an OOM is worse than one that dies and is replaced. In this architecture a dead pod is a *replaced* pod |

Worked example, the default production pod:

```
memory limit    4 GiB
heap            ~2.6 GiB   (65%)
non-heap        ~1.4 GiB   metaspace + Netty direct buffers + thread stacks + JVM
```

And the tight, verified configuration:

```
memory limit    1.5 GiB
MaxRAMPercentage 55        <- lowered deliberately, not left at 65
heap            ~0.8 GiB
```

That second line is the interesting one: **when you shrink the container, shrink the
percentage too.** The percentage is of the limit, so the absolute non-heap allowance shrinks
with it, and non-heap needs do not shrink to match.

`deploy/helm/values-minikube.yaml` and `deploy/k8s/10-gameserverset.yaml` show both
configurations side by side — the small one because a 4 GiB node cannot afford two 4 GiB pods,
the large one because that is what a real match needs.

### 22.4 How many matches fit on one server?

Honest answer: it depends, and you must measure. What can be said with confidence:

| Factor | Effect |
|---|---|
| `games-per-server` | Each extra match means another loaded world. Worlds are the dominant memory cost |
| Players per match | Tick time and memory both scale with players |
| `simulation-distance` | The cheapest large win; lower it first |
| Map destructibility | The dragons destroy terrain, and destroyed chunks cost memory and tick time |
| Match length | Longer matches mean more concurrent worlds |

The verified configuration runs `games-per-server: 1` — one match per pod — which is the simple,
predictable, and safest starting point. Raising it is a real capacity decision: it reduces pod
churn and scheduling overhead, and increases per-pod blast radius (one OOM kills several
matches).

**Practical method:** start at 1, measure tick time under load with a realistic number of
players, then raise it one at a time, watching `bedwars_free_slots` and the container's memory.
Do not plan from this table alone.

### 22.5 Scaling behaviour, and the three loops

| Loop | Driven by | Timescale | Purpose |
|---|---|---|---|
| KEDA | Prometheus `bedwars_queue_depth` | `pollingInterval: 10s`, `cooldownPeriod: 120s` | Horizontal scaling on real demand |
| Controller pre-warm | `BEDWARS_PREWARM_THRESHOLD` | immediately on queue | Latency: start a server *before* KEDA decides |
| Controller scale-down | `BEDWARS_IDLE_MINUTES` | evaluated every 60 s | Cost: reclaim idle servers |

They are complementary rather than redundant, and they have different failure modes. KEDA is
the right long-term shape but its 10-second poll plus a 120-second cooldown is slow for a
player who just clicked "play". The pre-warm covers that gap. Scale-down is what makes an idle
fleet free.

**The pre-warm guard.** The controller will not pre-warm more than once per 30-second window,
and it counts a *started-but-not-yet-registered* server as in-flight. Both matter: without
them, a burst of queue requests each triggered their own pre-warm, and one dispatch produced
**thirteen containers**. The guard reduced that to one, verified live. If you see a fleet
overshoot its queue depth, this is the mechanism to look at.

**Scale-down never kills a live match.** The policy reclaims a server only when scale-down is
enabled, no queue has anyone waiting, an *idle* server exists, the fleet is above
`BEDWARS_MIN_SERVERS`, and the fleet has been continuously idle for `BEDWARS_IDLE_MINUTES`. An
allocated server is not in the ready pool at all — the state machine itself is the guarantee
(chapter 8.1).

### 22.6 KEDA tuning

| Setting | Value | Meaning | Change it when |
|---|---|---|---|
| `minReplicaCount` | `0` | Scale to zero when idle | You need zero-latency joins and will pay for a warm fleet |
| `maxReplicaCount` | `50` | Ceiling | Your node fleet can hold more |
| `pollingInterval` | `10` | Seconds between metric checks | You want faster reaction and can afford the API load |
| `cooldownPeriod` | `120` | Seconds of calm before scaling down | Pods are being churned up and down (too low) |
| `threshold` | `"1"` | Queue depth per replica | One pod should serve several waiting players |

The `threshold` is the one to think about. At `"1"`, one server is created per queued player —
aggressive. If one server hosts 25 matches, a threshold closer to your realistic match-fill rate
avoids over-provisioning. (The controller's own pre-warm is already a conservative hint; KEDA's
threshold is the blunt instrument.)

### 22.7 The cost of the architecture

Be honest about what this design costs, not only what it saves:

| Cost | Why it is worth it |
|---|---|
| More moving parts than one big server | The parts scale independently and fail independently |
| Pod start latency | A cold pod must boot a JVM and load a world. Mitigated by pre-warm and by keeping the fleet warm with `minServers` |
| More memory per player than a shared server | Isolation and instant reset, which is the product |
| A controller is a new failure mode | It is stateless and cheap to restart; the queue is authoritative |
| Templates in object storage add a dependency at boot | The soft-failure path means no template still yields a playable server |

The trade is deliberate: you pay in orchestration complexity to buy instant resets, per-match
isolation, and a fleet that scales to zero.

---

# Part V — Appendices

## Appendix A — Glossary

| Term | Definition |
|---|---|
| **AdvancedSlimePaper (ASP)** | A server implementation that can load Slime Region Format worlds |
| **API version** | In `plugin.yml`, the *lowest* server API the plugin loads on |
| **BuildTools** | Spigot's official tool for building a Spigot jar from source |
| **Bukkit** | The original Minecraft plugin API |
| **Compose / Compose file** | A YAML description of a set of containers, run with `docker compose` |
| **Controller** | This project's matchmaking and capacity control plane |
| **CRD** | Custom Resource Definition — a new object kind added to Kubernetes |
| **Dispatch result** | The controller's answer to a queue request: a pod address, members, or a retry delay |
| **Drain / DRAINING** | A server leaving the pool gracefully, refusing new joins |
| **ELO** | The rating system used for player skill |
| **GameServerSet** | An OpenKruise workload type for game servers, with per-replica lifecycle states |
| **games-per-server** | How many concurrent matches one host runs |
| **Healthcheck** | A command or probe a runtime uses to judge liveness |
| **HikariCP** | A JDBC connection pool |
| **JDBC** | Java's database API |
| **JDK / JRE** | Development kit (compiler + runtime) / runtime only |
| **KEDA** | Kubernetes Event-Driven Autoscaling — scales on arbitrary metrics |
| **Karpenter** | Provisions cluster *nodes* in response to unschedulable pods |
| **kubeconfig** | The file describing how to reach clusters and with what credentials |
| **Kustomize** | Renders a directory of manifests (`kubectl apply -k`) |
| **mc-router** | Routes Minecraft TCP connections by hostname |
| **MinIO** | S3-compatible object storage |
| **Namespace** | A scope for names and access control in Kubernetes |
| **OOMKilled** | A container killed for exceeding its memory limit |
| **OpenKruise** | Kubernetes extensions from Alibaba, including GameServerSet |
| **Paper** | A Spigot fork that publishes prebuilt jars |
| **Pod** | The smallest deployable unit in Kubernetes — one or more containers |
| **Pre-warm** | Starting a server before it is strictly needed, to reduce join latency |
| **Probe** | A readiness or liveness check Kubernetes runs against a container |
| **Reactor** | Maven's multi-module build |
| **RCON** | A TCP protocol for running commands on a game server |
| **Request / limit** | The resources a pod is guaranteed / the ceiling it may not exceed |
| **RSP (Server List Ping)** | The protocol a client uses to display a server in its list |
| **Scale-down / scale-up** | Reclaiming idle servers / adding servers |
| **Service** | A stable virtual IP and DNS name for a set of pods |
| **Slime Region Format (SRF)** | A compressed single-file world format, cheap to load |
| **Spigot** | The server implementation this plugin targets |
| **Sudden death** | The endgame phase where dragons spawn and the map is destroyed |
| **Template** | A versioned arena world in object storage |
| **Tick** | 1/20th of a second — the Minecraft server's unit of time |
| **TPS** | Ticks per second; 20 is ideal, below 15 is painful |
| **Velocity** | A modern Minecraft proxy that can transfer players between servers |
| **Whitelist** | A server-side player allow-list; here, deliberately off |

## Appendix B — Ports and endpoints

| Port | Protocol | Where | Purpose |
|---|---|---|---|
| 25565 | TCP | Minecraft servers, Velocity, mc-router | The Minecraft game protocol |
| 8080 | TCP | Controller | The control plane HTTP API |
| 3306 | TCP | MySQL | Database |
| 9000 | TCP | MinIO | S3 API |
| 9001 | TCP | MinIO | Web console |
| 25575 | TCP | Minecraft servers | RCON (when enabled) |

### Controller endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/pods/ready` | yes | A server reports it can host matches |
| POST | `/pods/capacity` | yes | A running server reports free slots |
| POST | `/pods/started` | yes | A match started |
| POST | `/pods/heartbeat` | yes | Liveness, TPS, phase |
| POST | `/pods/ended` | yes | A match ended; the slot is released |
| POST | `/pods/draining` | yes | The server is leaving the pool |
| POST | `/lobby/queue` | yes | Request a slot |
| GET | `/healthz` | no | `ok` |
| GET | `/metrics` | no | Prometheus metrics |
| GET | `/queue/depth` | no | Per-group queue depth |
| GET | `/lobby/arena-status` | no | Per-group free slots and queued count |
| GET | `/infra` | no | Provisioner description and capacity |

## Appendix C — Environment variable index

**Controller**

`BEDWARS_PROVISIONER`, `BEDWARS_NAMESPACE`, `BEDWARS_GAMESERVERSET`, `BEDWARS_HTTP_PORT`,
`BEDWARS_MIN_SERVERS` / `BEDWARS_MIN_REPLICAS`, `BEDWARS_MAX_SERVERS` /
`BEDWARS_MAX_REPLICAS`, `BEDWARS_GAMES_PER_SERVER`, `BEDWARS_SCALE_DOWN_ENABLED`,
`BEDWARS_IDLE_MINUTES`, `BEDWARS_SERVER_PREFIX`, `BEDWARS_DOCKER_IMAGE`,
`BEDWARS_CONTAINER_MEMORY`, `BEDWARS_ARENA_GROUP`, `BEDWARS_CONTROLLER_ADVERTISE_URL`,
`BEDWARS_DOCKER_NETWORK`, `BEDWARS_PREWARM_THRESHOLD`, `BEDWARS_BASE_BACKOFF_MS`,
`BEDWARS_MAX_BACKOFF_MS`, `BEDWARS_API_TOKEN`

**Plugin** (override the YAML)

`BEDWARS_SERVER_ID`, `BEDWARS_ARENA_GROUP`, `BEDWARS_CONTROLLER_URL`, `BEDWARS_WHITELIST` —
plus, in practice, the template and database variables the container is given.

**Entrypoint**

`BEDWARS_TEMPLATE_SOURCE`, `BEDWARS_TEMPLATE_NAME`, `BEDWARS_TEMPLATE_VERSION`,
`BEDWARS_TEMPLATE_LOCAL_ROOT`, `BEDWARS_TEMPLATE_FORCE`, `BEDWARS_TEMPLATE_S3_ENDPOINT`,
`BEDWARS_TEMPLATE_S3_BUCKET`, `BEDWARS_TEMPLATE_S3_ACCESS_KEY`, `BEDWARS_TEMPLATE_S3_SECRET_KEY`,
`SERVER_DIR`

**Database and templates** (consumed by the plugin config/env)

`BEDWARS_DB_HOST`, `BEDWARS_DB_PORT`, `BEDWARS_DB_NAME`, `BEDWARS_DB_USER`,
`BEDWARS_DB_PASSWORD`

**JVM**

`JAVA_HOME`, `JAVA_TOOL_OPTIONS`

## Appendix D — Frequently asked questions

**Do I have to use Kubernetes?**
No. Docker Compose is a complete path and is fine for a single host. Kubernetes is what you want
for a real, public, multi-node deployment.

**Can I use Paper instead of Spigot?**
Yes — `SERVER_ENGINE=paper`. The plugin runs on either; Paper is a deployment option, never a
code dependency.

**Why is the plugin only 271 KB?**
Because the heavy libraries (HikariCP, the MySQL driver) are declared in `plugin.yml` and
downloaded by the server at startup rather than bundled. The 4 MB budget is enforced on every
build.

**Do game servers really get destroyed?**
Yes, and that is the point. A server hosting a match is "allocated" and will not be disturbed;
when the fleet is idle and the fleet is above `BEDWARS_MIN_SERVERS`, an idle server is
reclaimed. Nothing of value lives on it.

**Where do stats live?**
In shared MySQL, written at match end. A pod holds no unique data, which is why destroying one
is free.

**What happens if the controller is down?**
Game servers keep playing and keep retrying with throttled logging (one full error, then at
most one every 5 minutes, then silence). Players already in a match are unaffected. New
matchmaking stops. This is the correct failure mode: the game is not coupled to the control
plane.

**How does a pod get its map?**
The entrypoint stages it before the JVM starts, because the server reads its main world during
startup and a plugin cannot change it afterwards (chapter 10.5).

**Why `online-mode=false` on the game servers?**
Because Velocity authenticates players and forwards already-authenticated connections. It is
safe only while game pods are unreachable directly, which is what the NetworkPolicies enforce.

**Can I connect with a client that is not logged into a Minecraft account?**
Only with `BEDWARS_OFFLINE_MODE=true` on the proxy, which is a testing switch: it drops the
Mojang check entirely, so any name can be used. See §9.4.8. The game servers are already
offline-mode; this only affects the proxy, and it is off by default.

**How many matches fit on one server?**
Measure it. The default is 1 per pod — predictable and safe. Everything you need to reason about
it is in chapter 22.

**Can I run this without MinIO?**
Yes. Point `s3.endpoint` at real S3 (or any S3-compatible store), or use
`templateSource=LOCAL` with a map baked into the image.

**What is `server-jars/` for?**
Drop a prebuilt Spigot jar there and the image build skips compiling Spigot entirely (minutes
-> 23 seconds). The jars are gitignored.

**Why did my queue request return `retryAfterMillis` instead of a server?**
There was no capacity at that instant. That is backoff, not an error; the controller is starting
a server. The value grows with queue depth, up to `BEDWARS_MAX_BACKOFF_MS`.

**Can two players get the same slot?**
No. Allocation removes a pod from the ready pool atomically, so a slot can only be handed out
once.

**Why does `/infra` show `servers: 0` while `registeredServers: 1`?**
Different questions. `servers` is what the provisioner manages; `registeredServers` is what has
reported ready. A server you started by hand registers but is not provisioner-managed.

## Appendix E — Known limitations

Stated plainly. None of these are hidden or implied elsewhere; they are collected here so a
reader can decide whether they matter for their use.

**Not verified live in this environment**

| Item | Status |
|---|---|
| The S3/MinIO template path | Implemented and structurally verified; not exercised end to end, because the MinIO image cannot be pulled anonymously on this host (quay.io returns 401). Both live runs used the baked `LOCAL` template |
| The click-to-match transfer | The lobby boots in `LOBBY` role, the proxy finds it, and the hub is provably absent from the controller's registry — all three were verified live. What has not been driven is the final hop: a real client right-clicking a sign and being moved onto a pod, which needs a Minecraft client |
| Slime/AdvancedSlimePaper world swap | The adapter exists and is compiled against the ASP API; a live Slime load has not been performed |
| A full multi-player match on a cluster | Match logic is verified via RCON and the Core suite; a complete 2v2 game has not been played on Kubernetes |
| KEDA scaling an actual fleet | The `ScaledObject` renders and validates; it has not been observed scaling under real queue load |

**Design limitations**

| Item | Consequence |
|---|---|
| The controller runs at 1 replica | Not highly available. It is stateless and cheap to restart, but a restart is a brief interruption in matchmaking |
| The pre-warm guard is a fixed 30-second window | Not configurable. It prevents stampedes; it is not adaptive |
| The Kubernetes controller Role cannot create pods | It scales GameServerSets only. KEDA or a manual scale must drive replica counts |
| `games-per-server > 1` is less exercised than `1` | The multi-match-per-pod path exists and is unit-tested; the single-match path is the verified one |
| Pre-warm threshold and backoff are global, not per-group | One arena group's queue depth influences the fleet as a whole |
| MinIO and MySQL in the chart are development-grade | Single replica, no backup automation. Use managed services in production |
| No authentication on read-only endpoints | `/infra` reveals fleet size to anyone who can reach the port |
| The token travels as an environment variable | Anyone who can read a pod spec or run `docker inspect` on a game container can read it. Kubernetes Secrets are base64, not encryption; 21.3 has the handling rules |
| The lobby has one replica | A hub restart briefly empties the network's landing point. It cannot destroy a match, but players arriving during the restart are told the lobby is unavailable |
| A queue NPC needs no NPC plugin, and gets no skin | Any entity named `[bedwars]` works; making it look like a character needs Citizens or ModelEngine, which the plugin deliberately does not depend on |
| The lobby's default world is generated | No hub map is baked into the image. Supply one at `deploy/templates/lobby/` for a real hub build (see 9.4.4) |

**Operational notes**

- The game-server image is named `bedwars-spigot` even when it contains Paper. The name is
  historical; the engine is a build argument.
- `deploy/k8s/` and the Helm chart express the same system; if you edit one, edit the other, or
  pick one and stay there.
- The deployment tooling is POSIX shell, Python 3 and Docker, and the containers are Linux.
  That is a requirement, not a preference: every script here assumes a POSIX host.

---

*End of manual. For the focused documents — setup, architecture, the public API, provisioning,
and migrations — see the other files in `docs/`.*
