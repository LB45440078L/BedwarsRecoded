#!/usr/bin/env bash
# Run a Windows tool (docker.exe, kubectl.exe, helm.exe, minikube.exe, ...) from
# WSL with a usable Windows PATH.
#
# Why this exists: in this WSL distro the Windows user PATH is not inherited by
# Windows processes (cmd.exe sees only C:\WINDOWS\system32). That breaks:
#   - docker.exe, which needs docker-credential-desktop on %PATH%;
#   - minikube --driver=docker, which looks up `docker` on %PATH%.
# We therefore regenerate a small .bat with the Docker Desktop bin prepended and
# execute it, which sidesteps cmd.exe's nested-quote handling entirely.
#
# Usage:
#   deploy/tools/winrun.sh docker.exe build -t app:1 .
#   deploy/tools/winrun.sh '"C:\Program Files\Kubernetes\Minikube\minikube.exe"' version
#
# The command runs in the Windows form of the current directory, so run it from a
# /mnt/c/... path when the tool needs to read files.
set -euo pipefail

DOCKER_BIN='C:\Users\thevi\AppData\Local\Programs\DockerDesktop\resources\bin'
HELM_BIN='C:\Users\thevi\AppData\Local\Microsoft\WinGet\Packages\Helm.Helm_Microsoft.Winget.Source_8wekyb3d8bbwe\windows-amd64'
KUBECONFORM_BIN='C:\Users\thevi\AppData\Local\Microsoft\WinGet\Packages\YannHamon.kubeconform_Microsoft.Winget.Source_8wekyb3d8bbwe'
MINIKUBE_BIN='C:\Program Files\Kubernetes\Minikube'
EXTRA_BIN="${DOCKER_BIN};${HELM_BIN};${KUBECONFORM_BIN};${MINIKUBE_BIN}"
WORKDIR_WIN="${BEDWARS_WIN_WORKDIR:-C:\Users\thevi\bedwars-k8s}"
BAT_LINUX="/mnt/c/Users/thevi/bedwars-k8s/.winrun.bat"

if [ "$#" -eq 0 ]; then
  echo "usage: winrun.sh <windows-exe> [args...]" >&2
  exit 2
fi

# Translate a leading /mnt/<drive>/... argument to its Windows form.
translate() {
  case "$1" in
    /mnt/[a-z]/*) printf '%s' "$(printf '%s' "$1" | sed -E 's#^/mnt/([a-z])/#\U\1:\\#; s#/#\\#g')" ;;
    *) printf '%s' "$1" ;;
  esac
}

mkdir -p "$WORKDIR_WIN"
{
  echo '@echo off'
  echo "set \"PATH=${DOCKER_BIN};${EXTRA_BIN};%PATH%\""
  printf '%s' "$(translate "$1")"
  shift
  for arg in "$@"; do
    printf ' %s' "$(translate "$arg")"
  done
  echo
} > "$BAT_LINUX"

exec /mnt/c/Windows/System32/cmd.exe /c "$WORKDIR_WIN\\.winrun.bat"
