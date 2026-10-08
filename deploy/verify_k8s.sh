#!/usr/bin/env bash
# Render the Helm chart and the Kustomize base, then schema-validate every
# manifest with kubeconform. Custom resources without published schemas
# (GameServerSet, ScaledObject, ServiceMonitor, NodePool) are skipped.
#
# Tools are taken from HELM / KUBECTL / KUBECONFORM when set, otherwise from PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

HELM="${HELM:-helm}"
KUBECTL="${KUBECTL:-kubectl}"
KUBECONFORM="${KUBECONFORM:-kubeconform}"

if ! $HELM version >/dev/null 2>&1; then
  echo "SKIP: helm not available; install helm or set HELM."
  exit 0
fi
if ! $KUBECTL version --client >/dev/null 2>&1; then
  echo "SKIP: kubectl not available; install kubectl or set KUBECTL."
  exit 0
fi
if ! $KUBECONFORM -v >/dev/null 2>&1; then
  echo "SKIP: kubeconform not available; install kubeconform or set KUBECONFORM."
  exit 0
fi

OUT="${BEDWARS_K8S_TMP:-}"
CLEANUP=""
if [ -z "$OUT" ]; then
  OUT="$(mktemp -d)"
  CLEANUP="$OUT"
else
  mkdir -p "$OUT"
fi
# Override the scratch directory with BEDWARS_K8S_TMP if the default temp dir is
# unsuitable (read-only, or on a small filesystem).
trap '[ -n "$CLEANUP" ] && rm -rf "$CLEANUP"' EXIT

echo "==> helm lint"
$HELM lint deploy/helm/bedwars

echo "==> helm template"
$HELM template bedwars deploy/helm/bedwars > "$OUT/helm.yaml"

echo "==> kubectl kustomize"
$KUBECTL kustomize deploy/k8s > "$OUT/kustomize.yaml"

echo "==> kubeconform"
$KUBECONFORM -strict -summary -ignore-missing-schemas "$OUT/helm.yaml" "$OUT/kustomize.yaml"

# kubeconform validates schemas, not semantics: a container listing the same env name
# twice is valid YAML and a valid schema, and Kubernetes silently keeps the last one.
echo "==> duplicate env names"
python3 "$ROOT/deploy/tools/check_env_unique.py" "$OUT/helm.yaml" "$OUT/kustomize.yaml"

echo "K8S VERIFY: OK"
