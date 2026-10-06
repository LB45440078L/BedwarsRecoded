#!/usr/bin/env bash
# join-server.sh - expose a game server's Minecraft port on THIS machine so a
# Minecraft client can connect to it.
#
# Needs only `kubectl` on PATH: kubectl does the tunnelling itself (it talks to the
# API server and forwards a local port to the pod). Nothing platform-specific here.
#
# Usage:
#   deploy/tools/join-server.sh              # localhost:25565 -> the ready game pod
#   deploy/tools/join-server.sh 25570        # use a different local port
#   NAMESPACE=bedwars deploy/tools/join-server.sh
#
# Then point your Minecraft client at  localhost:<port>  (version 26.3, the server's).
#
# Why a port-forward and not a LoadBalancer: a game pod is one match, created on
# demand and destroyed when the match ends. Giving every pod a stable public address
# would contradict "pods are cattle" - in production players arrive through
# mc-router/Velocity instead. For local testing a port-forward is the honest,
# zero-config way in.
set -euo pipefail

NAMESPACE="${NAMESPACE:-bedwars}"
LOCAL_PORT="${1:-25565}"
POD_PORT="${POD_PORT:-25565}"
# Set by the GameServerSet template in deploy/k8s/10-gameserverset.yaml.
POD_SELECTOR="${POD_SELECTOR:-app.kubernetes.io/name=gameserverset}"
KUBECTL="${KUBECTL:-kubectl}"

if ! command -v "${KUBECTL%% *}" >/dev/null 2>&1 && ! ${KUBECTL} version --client >/dev/null 2>&1; then
  echo "kubectl not found. Install it, or set KUBECTL to a full command." >&2
  exit 1
fi

echo "==> looking for a ready game pod in namespace ${NAMESPACE}"
POD="$(${KUBECTL} -n "${NAMESPACE}" get pods \
        -l "${POD_SELECTOR}" \
        --field-selector=status.phase=Running \
        -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"

if [ -z "${POD}" ]; then
  echo "No running game pod found." >&2
  echo >&2
  echo "Start one first, e.g.:" >&2
  echo "  kubectl -n ${NAMESPACE} scale gameserversets bedwars-solo --replicas=1" >&2
  echo "  kubectl -n ${NAMESPACE} get pods -w      # wait for 1/1 Running" >&2
  exit 1
fi

echo "==> forwarding localhost:${LOCAL_PORT} -> ${POD}:${POD_PORT}"
echo
echo "    ---------------------------------------------------------------"
echo "    Join in Minecraft (version 26.3):  localhost:${LOCAL_PORT}"
echo "    Stop the forward with Ctrl-C (the pod keeps running)."
echo "    ---------------------------------------------------------------"
echo

# --address 0.0.0.0 makes the port reachable from other hosts and from a client
# running in a different virtualisation layer than kubectl.
exec ${KUBECTL} -n "${NAMESPACE}" port-forward --address 0.0.0.0 "pod/${POD}" "${LOCAL_PORT}:${POD_PORT}"
