#!/usr/bin/env bash
# =============================================================================
#  Resolve the Minecraft server jar for the BedwarsRecoded game-server image
# =============================================================================
#  Preference order - the first hit wins, and later steps never run:
#
#    1. a jar supplied in server-jars/        -> copied as-is, NOTHING is compiled
#    2. SERVER_ENGINE=paper                   -> downloaded from PaperMC (no compile)
#    3. SERVER_ENGINE=spigot (the default)    -> compiled with the official BuildTools
#
#  The engine is authoritative: with SERVER_ENGINE=paper, a spigot-*.jar in the folder
#  is ignored (it is named for the other engine) and Paper is fetched instead. A lone
#  jar under any other name is treated as deliberate and used for either engine.
#
#  So the slow Spigot build happens only when the engine is spigot AND no jar was
#  supplied. Supplying a jar is the fast, reproducible path: the image build becomes
#  a copy instead of a multi-minute decompile-and-patch.
#
#  Usage:  resolve-server-jar.sh <output-jar-path>
#
#  Environment:
#    SERVER_ENGINE     spigot (default) | paper
#    VENDOR_DIR        folder scanned for a supplied jar   (default /server-jars)
#    BUILD_DIR         scratch dir for the BuildTools compile (default: a temp dir)
#    SPIGOT_REV        Minecraft revision to compile       (default 26.3)
#    BUILD_TOOLS_URL   BuildTools download URL
#    PAPER_VERSION     Paper version to fetch              (default: SPIGOT_REV)
#    PAPER_BUILD       Paper build number                  (default: newest allowed)
#    PAPER_CHANNEL     preferred Paper channel             (default STABLE)
# =============================================================================
set -euo pipefail

OUT="${1:?usage: resolve-server-jar.sh <output-jar-path>}"
ENGINE="${SERVER_ENGINE:-spigot}"
VENDOR_DIR="${VENDOR_DIR:-/server-jars}"
SPIGOT_REV="${SPIGOT_REV:-26.3}"
BUILD_TOOLS_URL="${BUILD_TOOLS_URL:-https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar}"
PAPER_VERSION="${PAPER_VERSION:-$SPIGOT_REV}"
PAPER_BUILD="${PAPER_BUILD:-}"
PAPER_CHANNEL="${PAPER_CHANNEL:-STABLE}"

log() { echo "[server-jar] $*"; }

# -----------------------------------------------------------------------------
# 1. A jar the operator supplied. Deterministic order so a rebuild does not
#    silently switch jars: exact revision, then the conventional name, then a
#    lone jar; anything ambiguous is an error rather than a guess.
# -----------------------------------------------------------------------------
VENDORED_JAR=""

# The engine is authoritative: a jar named for the OTHER engine is ignored rather
# than silently used. Asking for paper while a spigot-*.jar sits in the folder must
# produce Paper, not a Spigot image with a misleading name.
find_vendored() {
    local engine="$1"
    [ -d "$VENDOR_DIR" ] || return 1
    local candidate
    if [ "$engine" = "paper" ]; then
        local preferred=("$VENDOR_DIR/paper-$PAPER_VERSION.jar" "$VENDOR_DIR/paper.jar" "$VENDOR_DIR/server.jar")
        local foreign='spigot-*.jar'
    else
        local preferred=("$VENDOR_DIR/spigot-$SPIGOT_REV.jar" "$VENDOR_DIR/spigot.jar" "$VENDOR_DIR/server.jar")
        local foreign='paper-*.jar'
    fi
    for candidate in "${preferred[@]}"; do
        if [ -f "$candidate" ]; then
            VENDORED_JAR="$candidate"
            return 0
        fi
    done
    # A lone jar under any other name is taken as deliberate, as long as it does not
    # advertise itself as the engine that was NOT selected.
    local jars=()
    while IFS= read -r found; do
        case "$(basename "$found")" in
            $foreign) log "ignoring $(basename "$found"): engine=$engine" ;;
            *) jars+=("$found") ;;
        esac
    done < <(find "$VENDOR_DIR" -maxdepth 1 -type f -name '*.jar' | sort)
    if [ "${#jars[@]}" -eq 1 ]; then
        VENDORED_JAR="${jars[0]}"
        return 0
    fi
    if [ "${#jars[@]}" -gt 1 ]; then
        log "ERROR: $VENDOR_DIR holds ${#jars[@]} jars usable for engine=$engine and none is named for it."
        log "       Name one (paper-$PAPER_VERSION.jar / spigot-$SPIGOT_REV.jar), or remove the others:"
        printf '[server-jar]        %s\n' "${jars[@]}" >&2
        exit 2
    fi
    return 1
}

# -----------------------------------------------------------------------------
# 2. Paper publishes prebuilt jars, so this path never compiles anything.
#    The v3 API returns builds newest-first; STABLE is preferred when available.
# -----------------------------------------------------------------------------
paper_artifact() {
    python3 - "$PAPER_VERSION" "$PAPER_BUILD" "$PAPER_CHANNEL" <<'PY'
import json
import sys
import urllib.request

version, wanted_build, channel = sys.argv[1], sys.argv[2], sys.argv[3]
url = f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds"
request = urllib.request.Request(url, headers={"User-Agent": "bedwarsrecoded-image/1.0"})
with urllib.request.urlopen(request, timeout=60) as response:
    payload = json.load(response)
builds = payload if isinstance(payload, list) else payload.get("builds", [])
if not builds:
    sys.exit(f"no Paper builds published for {version}")
if wanted_build:
    builds = [b for b in builds if str(b.get("id")) == str(wanted_build)]
    if not builds:
        sys.exit(f"Paper {version} has no build {wanted_build}")
else:
    preferred = [b for b in builds if str(b.get("channel", "")).upper() == channel.upper()]
    if preferred:
        builds = preferred
artifact = builds[0]["downloads"]["server:default"]
print(artifact["url"], artifact.get("checksums", {}).get("sha256", ""), artifact.get("name", "server.jar"))
PY
}

download_paper() {
    local url sha name
    read -r url sha name <<<"$(paper_artifact)"
    log "downloading Paper $PAPER_VERSION ($name) - no compilation needed"
    curl -fsSL --retry 3 --max-time 900 "$url" -o "$OUT"
    if [ -n "$sha" ]; then
        echo "$sha  $OUT" | sha256sum -c - >/dev/null || {
            log "ERROR: Paper download failed its sha256 check"
            exit 3
        }
        log "sha256 verified"
    fi
    return 0
}

# -----------------------------------------------------------------------------
# 3. The fallback: compile Spigot with the official BuildTools. Slow (it
#    decompiles and patches the vanilla server), so it runs only when there is
#    genuinely nothing else to use.
# -----------------------------------------------------------------------------
compile_spigot() {
    local workdir="${BUILD_DIR:-$(mktemp -d)}"
    mkdir -p "$workdir"
    cd "$workdir"
    log "no jar supplied and engine=spigot: compiling Spigot $SPIGOT_REV with BuildTools"
    log "this takes minutes - supply server-jars/spigot-$SPIGOT_REV.jar to skip it"
    curl -fsSL --retry 3 --max-time 300 -o BuildTools.jar "$BUILD_TOOLS_URL"
    git config --global --add safe.directory "$workdir" 2>/dev/null || true
    java -jar BuildTools.jar --rev "$SPIGOT_REV" --compile spigot
    local built
    built="$(find "$workdir" -maxdepth 1 -name "spigot-$SPIGOT_REV*.jar" | head -1)"
    if [ -z "$built" ]; then
        built="$(find "$workdir" -maxdepth 1 -name 'spigot-*.jar' | head -1)"
    fi
    if [ -z "$built" ]; then
        log "ERROR: BuildTools finished but produced no spigot jar"
        exit 4
    fi
    cp "$built" "$OUT"
    log "compiled $built"
}

mkdir -p "$(dirname "$OUT")"

if find_vendored "$ENGINE"; then
    log "using supplied jar $VENDORED_JAR (skipping the Spigot compile entirely)"
    cp "$VENDORED_JAR" "$OUT"
else
    case "$ENGINE" in
        paper) download_paper ;;
        spigot) compile_spigot ;;
        *) log "ERROR: unknown SERVER_ENGINE '$ENGINE' (expected spigot or paper)"; exit 2 ;;
    esac
fi

[ -s "$OUT" ] || { log "ERROR: $OUT is empty"; exit 5; }
log "resolved $(basename "$OUT") - $(du -h "$OUT" | cut -f1)"