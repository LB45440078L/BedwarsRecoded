#!/usr/bin/env bash
# One command to verify the whole project: unit/integration tests + deployment assets.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Building and testing (mvn verify)"
if [ -f "$HOME/.local/tools/env.sh" ]; then
  # shellcheck disable=SC1090
  source "$HOME/.local/tools/env.sh"
fi
mvn -q verify

echo
echo "==> Verifying deployment assets (K8s + compose + Dockerfiles)"
python3 deploy/verify_deploy.py

echo
echo "==> Plugin JAR size"
JAR="$(ls BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
  echo "    FAIL: no plugin jar found - did the build run?"
  exit 1
fi
# `wc -c` rather than `stat`: GNU stat's -c and BSD/macOS stat's -f disagree, so the
# old `stat -c%s` made this gate error out on macOS instead of checking anything.
SIZE="$(wc -c <"$JAR" | tr -d ' ')"
echo "    $JAR = $SIZE bytes (budget 4194304)"
if [ "$SIZE" -gt 4194304 ]; then
  echo "    FAIL: plugin JAR exceeds 4 MB"
  exit 1
fi

echo
echo "ALL VERIFICATIONS PASSED"