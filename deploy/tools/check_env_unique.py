#!/usr/bin/env python3
"""Fail if any rendered container declares the same environment variable twice.

Kubernetes accepts a duplicate `env` name silently (the last one wins), and neither
`helm lint` nor kubeconform looks for it. The symptom is a service that quietly runs
with the wrong value - for example a proxy presenting a different shared secret than
the controller expects, which just looks like "auth is broken".

Used by deploy/verify_k8s.sh on the rendered chart and the Kustomize base.

Usage:
    check_env_unique.py <rendered.yaml> [more.yaml ...]

Exit codes: 0 = clean, 1 = duplicates found, 2 = usage/parse error.
Requires PyYAML; exits 0 with a SKIP line when it is not installed.
"""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - environment dependent
    print("SKIP: PyYAML not installed; duplicate-env check not run.")
    sys.exit(0)


def containers_of(doc: dict):
    """Yield (path, container) for every container in a workload document."""
    kind = doc.get("kind")
    spec = (doc.get("spec") or {})
    # A GameServerSet keeps its pod template one level down, like a Deployment.
    template = spec.get("template") or {}
    pod_spec = (template.get("spec") or {})
    name = (doc.get("metadata") or {}).get("name") or "?"

    if kind in {"Deployment", "StatefulSet", "DaemonSet", "Job", "GameServerSet"}:
        for c in pod_spec.get("containers") or []:
            yield f"{kind}/{name}/container/{c.get('name')}", c
        for c in pod_spec.get("initContainers") or []:
            yield f"{kind}/{name}/initContainer/{c.get('name')}", c
    elif kind == "CronJob":
        job_spec = ((spec.get("jobTemplate") or {}).get("spec") or {})
        pod_spec = ((job_spec.get("template") or {}).get("spec") or {})
        for c in pod_spec.get("containers") or []:
            yield f"{kind}/{name}/container/{c.get('name')}", c


def check(path: Path) -> list[str]:
    problems: list[str] = []
    text = path.read_text(encoding="utf-8")
    for doc in yaml.safe_load_all(text):
        if not isinstance(doc, dict):
            continue
        for where, container in containers_of(doc):
            names = [e.get("name") for e in (container.get("env") or [])]
            for name, count in Counter(names).items():
                if count > 1:
                    problems.append(f"{path.name}: {where} declares env {name} {count} times")
    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    problems: list[str] = []
    checked = 0
    for arg in argv[1:]:
        p = Path(arg)
        if not p.exists():
            print(f"ERROR: {p} does not exist", file=sys.stderr)
            return 2
        try:
            problems += check(p)
        except yaml.YAMLError as e:
            print(f"ERROR: {p} is not valid YAML: {e}", file=sys.stderr)
            return 2
        checked += 1

    if problems:
        print("DUPLICATE ENV NAMES:")
        for p in problems:
            print(f"  {p}")
        return 1
    print(f"==> no duplicate env names across {checked} rendered file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
