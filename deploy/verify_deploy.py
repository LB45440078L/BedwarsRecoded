#!/usr/bin/env python3
"""Structural verifier for the BedwarsRecoded deployment assets.

Runs without Docker/kubectl: it parses every YAML manifest and the compose file
and asserts the invariants the platform relies on. Exits non-zero on any problem
so it can gate CI.

Usage:  python3 deploy/verify_deploy.py
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("FAIL: PyYAML is required (pip install pyyaml)")
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
K8S = ROOT / "deploy" / "k8s"
COMPOSE = ROOT / "deploy" / "compose" / "docker-compose.yml"
DOCKER = ROOT / "deploy" / "docker"

errors: list[str] = []
checks = 0


def check(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        errors.append(message)


def load_all(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [doc for doc in yaml.safe_load_all(handle) if doc is not None]


def verify_k8s() -> None:
    docs: list[dict] = []
    for manifest in sorted(K8S.glob("*.yaml")):
        try:
            docs.extend(load_all(manifest))
        except yaml.YAMLError as exc:
            errors.append(f"{manifest.name}: invalid YAML: {exc}")
    check(len(docs) > 0, "no Kubernetes documents parsed")

    kinds = {d.get("kind") for d in docs}
    for required in [
        "Namespace", "GameServerSet", "ScaledObject", "Deployment",
        "ServiceAccount", "Role", "RoleBinding", "NetworkPolicy",
        "PodDisruptionBudget", "StatefulSet", "ConfigMap", "Secret",
        "ServiceMonitor",
    ]:
        check(required in kinds, f"k8s: missing kind {required}")

    # Every resource except cluster-scoped ones must be in the bedwars namespace.
    for doc in docs:
        kind = doc.get("kind")
        if kind in {"Namespace", "NodePool", "ClusterRole", "ClusterRoleBinding", "Kustomization"}:
            continue
        check(doc.get("metadata", {}).get("namespace") == "bedwars",
              f"k8s: {kind}/{doc.get('metadata', {}).get('name')} not in namespace bedwars")

    # Game pods must declare strict resources (hard constraint #5).
    gameserversets = [d for d in docs if d.get("kind") == "GameServerSet"]
    check(len(gameserversets) == 1, "expected exactly one GameServerSet")
    for gss in gameserversets:
        spec = gss["spec"]["gameServerTemplate"]["spec"]
        for container in spec["containers"]:
            res = container.get("resources", {})
            check("requests" in res and "limits" in res,
                  "GameServerSet container must declare requests and limits")
            check(res["limits"].get("memory") == "4Gi",
                  "GameServerSet memory limit should be 4Gi")
        check("readinessProbe" in spec["containers"][0], "game pod needs a readinessProbe")
        check("livenessProbe" in spec["containers"][0], "game pod needs a livenessProbe")

    # The controller must expose /metrics for the KEDA trigger to be valid.
    controller_deploy = next(
        (d for d in docs if d.get("kind") == "Deployment"
         and d.get("metadata", {}).get("name") == "bedwars-controller"), None)
    check(controller_deploy is not None, "controller Deployment missing")
    if controller_deploy:
        annotations = controller_deploy["spec"]["template"]["metadata"].get("annotations", {})
        check(annotations.get("prometheus.io/scrape") == "true",
              "controller should be annotated for Prometheus scraping")
    check(any(d.get("kind") == "ServiceMonitor" for d in docs),
          "ServiceMonitor for the controller is required")

    # RBAC must grant the controller GameServerSet write access.
    roles = [d for d in docs if d.get("kind") == "Role"
             and d.get("metadata", {}).get("name") == "bedwars-controller"]
    check(len(roles) == 1, "controller Role missing")
    if roles:
        verbs = set()
        for rule in roles[0].get("rules", []):
            if "gameserversets" in rule.get("resources", []):
                verbs.update(rule.get("verbs", []))
        check({"update", "patch"} <= verbs, "controller Role must allow update/patch on gameserversets")


def verify_compose() -> None:
    check(COMPOSE.exists(), "docker-compose.yml missing")
    if not COMPOSE.exists():
        return
    try:
        compose = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        errors.append(f"compose: invalid YAML: {exc}")
        return
    services = compose.get("services", {})
    for required in ["mysql", "minio", "minio-init", "controller", "lobby", "velocity"]:
        check(required in services, f"compose: missing service {required}")
    check("healthcheck" in services.get("mysql", {}), "compose: mysql needs a healthcheck")
    check("game-pod" in services and "game" in services["game-pod"].get("profiles", []),
          "compose: game-pod must be behind the 'game' profile")
    for name in ("controller", "game-pod", "velocity", "lobby"):
        build = services.get(name, {}).get("build", {})
        dockerfile = ROOT / build.get("dockerfile", "")
        check(dockerfile.exists(), f"compose: {name} dockerfile {build.get('dockerfile')} not found")


def verify_dockerfiles() -> None:
    for name in ("gameserver", "velocity", "controller"):
        path = DOCKER / f"{name}.Dockerfile"
        check(path.exists(), f"docker: {name}.Dockerfile missing")
        if path.exists():
            text = path.read_text(encoding="utf-8")
            check("FROM" in text, f"docker: {name}.Dockerfile has no FROM")
            check("ENTRYPOINT" in text or "CMD" in text,
                  f"docker: {name}.Dockerfile has no ENTRYPOINT/CMD")


def verify_server_jar_resolution() -> None:
    """The game-server image must prefer a supplied jar and only compile Spigot as a
    last resort. A regression here is expensive: it silently turns every image build
    back into a multi-minute Spigot compile."""
    gameserver = DOCKER / "gameserver.Dockerfile"
    resolver = DOCKER / "resolve-server-jar.sh"
    vendor = ROOT / "server-jars"

    check(resolver.exists(), "docker: resolve-server-jar.sh missing")
    check((vendor / "README.md").exists(), "server-jars/README.md missing (the default jar folder)")
    check(not (DOCKER / "spigot.Dockerfile").exists(),
          "docker: spigot.Dockerfile should have been replaced by gameserver.Dockerfile")

    if not gameserver.exists() or not resolver.exists():
        return

    text = gameserver.read_text(encoding="utf-8")
    for arg in ("SERVER_ENGINE", "SPIGOT_REV", "PAPER_VERSION"):
        check(f"ARG {arg}" in text, f"gameserver.Dockerfile must declare ARG {arg}")
    check("COPY server-jars" in text, "gameserver.Dockerfile must copy server-jars/ into the build")
    check("resolve-server-jar.sh" in text, "gameserver.Dockerfile must use resolve-server-jar.sh")
    check("/server/server.jar" in text,
          "gameserver.Dockerfile must place the resolved jar at /server/server.jar")

    script = resolver.read_text(encoding="utf-8")
    check("find_vendored" in script and "compile_spigot" in script,
          "resolve-server-jar.sh must be able to prefer a supplied jar over compiling")
    check("fill.papermc.io" in script,
          "resolve-server-jar.sh must download Paper from the current PaperMC API")
    check("api.papermc.io" not in script,
          "resolve-server-jar.sh must not use the sunset PaperMC v2 API")

    entrypoint = (DOCKER / "entrypoint.sh").read_text(encoding="utf-8")
    check("server.jar" in entrypoint, "entrypoint.sh must launch /server/server.jar")
    check("spigot.jar" not in entrypoint,
          "entrypoint.sh must not hard-code a spigot.jar name (the engine is a build arg)")


def verify_no_standalone_mode() -> None:
    """The plugin runs only as a managed game server. The standalone/AUTO deployment
    mode was removed; a reappearing `deployment:` block would mean dead config paths
    that no code reads."""
    config = ROOT / "BedwarsRecoded-Spigot" / "src" / "main" / "resources" / "config.yml"
    if not config.exists():
        errors.append("plugin config.yml missing")
        return
    text = config.read_text(encoding="utf-8")
    check("\ndeployment:" not in text, "plugin config.yml must not declare a `deployment:` block")
    check("STANDALONE" not in text, "plugin config.yml must not mention STANDALONE mode")
    check("controller:" in text, "plugin config.yml must declare the `controller:` block")
    check("disable-reporting-after-failures" in text,
          "reporting knobs belong under `controller:` now that `deployment:` is gone")


def verify_installer() -> None:
    """The installer is the front door, and it is three thousand lines of bash that
    nothing else type-checks.

    It must parse; it must stay inside bash 3.2, because macOS still ships 3.2 and the
    guided flow is meant to run there as well as on Ubuntu/WSL; and every flag it
    accepts has to be described in its own --help.
    """
    installer = ROOT / "install.sh"
    if not installer.exists():
        errors.append("install.sh is missing")
        return
    text = installer.read_text(encoding="utf-8")

    parsed = subprocess.run(["bash", "-n", str(installer)], capture_output=True, text=True)
    check(parsed.returncode == 0, f"install.sh does not parse: {parsed.stderr.strip()[:200]}")

    # Only real code: the comments deliberately name the features the script avoids,
    # and a guard that trips on its own documentation is worse than no guard.
    code = "\n".join(line for line in text.split("\n") if not line.lstrip().startswith("#"))

    for feature, pattern in [
        ("associative arrays", r"declare\s+-A"),
        ("case-modifying expansion (${v,,} / ${v^^})", r"\$\{[A-Za-z_][A-Za-z0-9_]*(,,|\^\^)"),
        ("mapfile/readarray", r"\b(mapfile|readarray)\b"),
        ("&>> redirection", r"&>>"),
    ]:
        check(re.search(pattern, code) is None,
              f"install.sh uses {feature}, which bash 3.2 does not have")

    # Flags are easy to add and easy to forget to document; a user who cannot find a
    # flag does not have it.
    for flag in ["--dry-run", "--yes", "--mode", "--resume", "--teardown",
                 "--install-deps", "--no-install-deps", "--verbose", "--no-color", "--help"]:
        check(text.count(flag) >= 2,
              f"install.sh accepts {flag} but does not describe it in --help")

    # The dependency offer is the difference between "it told me what was missing and
    # closed" and "it offered to fix it": guard the pieces that make it work.
    check("ensure_dependencies" in text, "install.sh no longer offers to install dependencies")
    check("detect_platform" in text, "install.sh no longer detects the platform")
    for family in ["debian", "fedora", "arch", "suse", "macos"]:
        check(f"{family})" in text, f"install.sh has no package-manager mapping for {family}")


def verify_lobby() -> None:
    """The lobby is a first-class server ROLE, not a coincidence of configuration.

    The chain a player walks is: connect to the proxy -> land in a dedicated hub ->
    queue -> play a match -> be returned to the hub. Each assertion below guards a
    specific way that chain breaks silently:

      * a lobby that reports capacity is handed players as if it hosted a game;
      * a proxy with no `try` server has nowhere to put a connecting player;
      * a game pod the proxy cannot address by name is unreachable once started;
      * a backend that has not enabled forwarding refuses the proxy's handshake.
    """
    config = ROOT / "BedwarsRecoded-Spigot" / "src" / "main" / "resources" / "config.yml"
    text = config.read_text(encoding="utf-8")
    check("role:" in text, "plugin config.yml must expose the server `role:` (GAME|LOBBY)")
    check("pod-${HOSTNAME}" not in text,
          "server-id must be the bare hostname: it is the name the proxy dials, and a "
          "`pod-` prefix resolves to nothing in either Docker or Kubernetes")
    check("return-to-lobby" in text, "config.yml must expose the return-to-lobby switch")

    # A permission checked in code but not declared here is FALSE for everyone, operators
    # included: the hub would be unbuildable and admin subcommands unusable by its owner.
    plugin_yml = (ROOT / "BedwarsRecoded-Spigot" / "src" / "main" / "resources" / "plugin.yml")
    yml = plugin_yml.read_text(encoding="utf-8")
    for node in ("bedwars.lobby.build", "bedwars.admin"):
        check(f"{node}:" in yml, f"plugin.yml must declare the {node} permission node")

    k8s_text = "\n".join(p.read_text(encoding="utf-8") for p in sorted(K8S.glob("*.yaml")))
    check("name: lobby" in k8s_text, "k8s: no lobby Deployment/Service")
    check('value: "LOBBY"' in k8s_text,
          "k8s: the lobby must set BEDWARS_ROLE=LOBBY, or it reports itself as a match host")
    check("lobby-ingress" in k8s_text, "k8s: the lobby needs its own NetworkPolicy")
    check("POD_ADDRESS_SUFFIX" in k8s_text,
          "k8s: velocity needs POD_ADDRESS_SUFFIX, or it cannot dial an ephemeral pod by name")
    # A GameServerSet must NOT pin BEDWARS_SERVER_ID: the plugin's fallback is ${HOSTNAME},
    # which in Kubernetes is the pod name -- exactly the DNS label POD_ADDRESS_SUFFIX
    # completes. Pinning another value silently makes the pod undialable by the proxy.
    gss = ROOT / "deploy" / "k8s" / "10-gameserverset.yaml"
    if gss.exists():
        body = gss.read_text(encoding="utf-8")
        check("BEDWARS_SERVER_ID" not in body,
              "the GameServerSet must not pin BEDWARS_SERVER_ID: the pod name is the address "
              "the proxy dials, and it comes from ${HOSTNAME}")
        # The DNS suffix is built from the GameServerSet's name, so the two drift apart
        # silently: rename the set and the proxy resolves a host that no longer exists.
        for doc in load_all(gss):
            if doc.get("kind") == "GameServerSet":
                name = doc["metadata"]["name"]
                check(f".{name}." in k8s_text,
                      f"POD_ADDRESS_SUFFIX must be built from the GameServerSet name "
                      f"('{name}'), or the proxy cannot resolve a pod")

    toml_path = DOCKER / "velocity.toml"
    check(toml_path.exists(), "docker: velocity.toml missing (the proxy has no configuration)")
    if toml_path.exists():
        toml = toml_path.read_text(encoding="utf-8")
        check('try = ["lobby"]' in toml,
              'velocity.toml must set try = ["lobby"]: without it a connecting player has '
              "no server to be placed on")
        check('lobby = "lobby:25565"' in toml,
              "velocity.toml must register the lobby under the name the plugin transfers to")
        check("bungee-plugin-message-channel = true" in toml,
              "velocity.toml must enable the plugin-message channel the lobby speaks over")

    spigot = DOCKER / "spigot.yml"
    check(spigot.exists(), "docker: spigot.yml missing (backends cannot accept proxy joins)")
    if spigot.exists():
        check("bungeecord: true" in spigot.read_text(encoding="utf-8"),
              "spigot.yml must enable bungeecord forwarding to match the proxy's legacy mode")
    for dockerfile, shipped in (("gameserver", "spigot.yml"), ("velocity", "velocity.toml")):
        path = DOCKER / f"{dockerfile}.Dockerfile"
        if path.exists():
            check(shipped in path.read_text(encoding="utf-8"),
                  f"{dockerfile}.Dockerfile must ship {shipped}")

    compose_text = COMPOSE.read_text(encoding="utf-8")
    check('BEDWARS_ROLE: "LOBBY"' in compose_text,
          "compose: the lobby service must run in LOBBY role")
    #  The reported failure: the installer asked for a maximum and wrote it into .env, and the
    #  Compose controller never read that name, so the cap was silently the controller's own
    #  default. A configured limit that nothing consumes is worse than no limit at all.
    for env, why in (("BEDWARS_MIN_SERVERS", "the floor of the fleet"),
                     ("BEDWARS_MAX_SERVERS", "the cap that stops endless pre-warming"),
                     ("BEDWARS_GAMES_PER_SERVER", "matches per server, half of the capacity"),
                     ("BEDWARS_ARENA_GROUP", "the group a queueless request resolves to")):
        check(env in compose_text, f"compose: the controller must be given {env} ({why})")
    installer_text = (ROOT / "install.sh").read_text(encoding="utf-8")
    for env in ("BEDWARS_MIN_SERVERS", "BEDWARS_MAX_SERVERS", "BEDWARS_GAMES_PER_SERVER",
                "BEDWARS_ARENA_GROUP"):
        check(env in installer_text,
              f"install.sh: the installer must write {env} into .env (names must match Compose)")
    #  Every stack whose controller can start a game server needs that image built.
    check("game-pod" in installer_text and "network" in installer_text,
          "install.sh: the network stack must build the game image the controller provisions from")
    check("LOBBY_SERVER" in compose_text,
          "compose: velocity must be told which registered server is the lobby")

    #  The controller resolves a request that names no group to its own arena group, while
    #  the game pods register under theirs. If the two disagree, no registered server can
    #  ever satisfy a queued player and nobody is ever placed - the Docker path had exactly
    #  this bug. Pin the two sides together on both deployment paths.
    def env_of(text: str, name: str):
        match = re.search(r"-\s*name:\s*" + name + r"\s*\n\s*value:\s*\"?([^\"\n]+)\"?", text)
        return match.group(1).strip() if match else None

    k8s_dir = ROOT / "deploy" / "k8s"
    k8s_pods = (k8s_dir / "10-gameserverset.yaml").read_text(encoding="utf-8")
    k8s_ctl = (k8s_dir / "40-controller.yaml").read_text(encoding="utf-8")
    pods_group = env_of(k8s_pods, "BEDWARS_ARENA_GROUP")
    ctl_group = env_of(k8s_ctl, "BEDWARS_ARENA_GROUP")
    check(pods_group is not None, "k8s: game pods must declare BEDWARS_ARENA_GROUP")
    check(ctl_group is not None,
          "k8s: the controller must be told BEDWARS_ARENA_GROUP (a blank request resolves to it)")
    check(pods_group == ctl_group,
          f"k8s: controller group ({ctl_group}) must equal the pods' group ({pods_group})")

    helm = ROOT / "deploy" / "helm" / "bedwars"
    check((helm / "templates" / "lobby.yaml").exists(), "helm: no lobby template")
    helm_gs = (helm / "templates" / "gameserverset.yaml").read_text(encoding="utf-8")
    helm_ctl = (helm / "templates" / "controller.yaml").read_text(encoding="utf-8")
    check(".Values.arena.group" in helm_gs, "helm: game pods must take BEDWARS_ARENA_GROUP from arena.group")
    check(".Values.arena.group" in helm_ctl,
          "helm: the controller must take BEDWARS_ARENA_GROUP from the same value")
    values = helm / "values.yaml"
    if values.exists():
        body = values.read_text(encoding="utf-8")
        check("podAddressSuffix" in body, "helm: velocity values must carry podAddressSuffix")
        check("\nlobby:" in body, "helm: values must carry a lobby block")


def main() -> int:
    verify_k8s()
    verify_compose()
    verify_dockerfiles()
    verify_server_jar_resolution()
    verify_no_standalone_mode()
    verify_lobby()
    verify_installer()
    if errors:
        print(f"DEPLOY VERIFY: {len(errors)} problem(s) across {checks} checks")
        for err in errors:
            print(f"  - {err}")
        return 1
    print(f"DEPLOY VERIFY: OK ({checks} checks passed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())