#!/usr/bin/env python3
"""Structural verifier for the BedwarsRecoded deployment assets.

Runs without Docker/kubectl: it parses every YAML manifest and the compose file
and asserts the invariants the platform relies on. Exits non-zero on any problem
so it can gate CI.

Usage:  python3 deploy/verify_deploy.py
"""
from __future__ import annotations

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
    for required in ["mysql", "minio", "minio-init", "controller"]:
        check(required in services, f"compose: missing service {required}")
    check("healthcheck" in services.get("mysql", {}), "compose: mysql needs a healthcheck")
    check("game-pod" in services and "game" in services["game-pod"].get("profiles", []),
          "compose: game-pod must be behind the 'game' profile")
    for name in ("controller", "game-pod"):
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


def main() -> int:
    verify_k8s()
    verify_compose()
    verify_dockerfiles()
    verify_server_jar_resolution()
    verify_no_standalone_mode()
    if errors:
        print(f"DEPLOY VERIFY: {len(errors)} problem(s) across {checks} checks")
        for err in errors:
            print(f"  - {err}")
        return 1
    print(f"DEPLOY VERIFY: OK ({checks} checks passed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())