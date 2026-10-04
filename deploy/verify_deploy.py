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
    for name in ("spigot", "velocity", "controller"):
        path = DOCKER / f"{name}.Dockerfile"
        check(path.exists(), f"docker: {name}.Dockerfile missing")
        if path.exists():
            text = path.read_text(encoding="utf-8")
            check("FROM" in text, f"docker: {name}.Dockerfile has no FROM")
            check("ENTRYPOINT" in text or "CMD" in text,
                  f"docker: {name}.Dockerfile has no ENTRYPOINT/CMD")


def main() -> int:
    verify_k8s()
    verify_compose()
    verify_dockerfiles()
    if errors:
        print(f"DEPLOY VERIFY: {len(errors)} problem(s) across {checks} checks")
        for err in errors:
            print(f"  - {err}")
        return 1
    print(f"DEPLOY VERIFY: OK ({checks} checks passed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())