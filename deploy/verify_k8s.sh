#!/usr/bin/env bash
# Render the Helm chart and the Kustomize base, then schema-validate every
# manifest with kubeconform. Custom resources without published schemas
# (GameServerSet, ScaledObject, ServiceMonitor, NodePool) are skipped.
#
# Tools are taken from HELM / KUBECTL / KUBECONFORM when set, otherwise from
# PATH. On this Windows-hosted WSL setup they are reached through
# deploy/tools/winrun.sh, e.g.:
#   HELM="deploy/tools/winrun.sh helm.exe" \
#   KUBECTL="deploy/tools/winrun.sh kubectl.exe" \
#   KUBECONFORM="deploy/tools/winrun.sh kubeconform.exe" \
#   deploy/verify_k8s.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

HELM="${HELM:-helm}"
KUBECTL="${KUBECTL:-kubectl}"
KUBECONFORM="${KUBECONFORM:-kubeconform}"

if ! $HELM version >/dev/null 2>&1; then
  echo "SKIP: helm not available; install helm or set HELM (e.g. 'deploy/tools/winrun.sh helm.exe')."
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
# Windows-hosted tools cannot read WSL paths; set BEDWARS_K8S_TMP to a /mnt/c
# directory when using deploy/tools/winrun.sh.
trap '[ -n "$CLEANUP" ] && rm -rf "$CLEANUP"' EXIT

echo "==> helm lint"
$HELM lint deploy/helm/bedwars

echo "==> helm template"
$HELM template bedwars deploy/helm/bedwars > "$OUT/helm.yaml"

echo "==> kubectl kustomize"
$KUBECTL kustomize deploy/k8s > "$OUT/kustomize.yaml"

echo "==> kubeconform"
$KUBECONFORM -strict -summary -ignore-missing-schemas "$OUT/helm.yaml" "$OUT/kustomize.yaml"

echo "K8S VERIFY: OK"
