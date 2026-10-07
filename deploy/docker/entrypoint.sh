#!/usr/bin/env bash
# =============================================================================
#  BedwarsRecoded game pod - entrypoint
# =============================================================================
#  A pod's MAIN world IS the arena. One pod runs exactly one match, so there is no
#  reason to generate a throwaway world and then load the arena into it: the arena
#  simply *is* the world.
#
#  This has to happen here rather than in the plugin, because the server reads its
#  main world (server.properties `level-name`) during startup - by the time a plugin
#  enables, the world is already loaded. Staging the template before boot is the
#  correct path on Spigot; the plugin's own GameWorldService copies a fresh world per
#  match at runtime for the multi-game path.
#
#  Environment:
#    BEDWARS_TEMPLATE_SOURCE        LOCAL (baked into the image) | S3
#    BEDWARS_TEMPLATE_NAME          template/world name, e.g. Glacier
#    BEDWARS_TEMPLATE_VERSION       version, used for the S3 object key
#    BEDWARS_TEMPLATE_LOCAL_ROOT    where LOCAL templates live (default /templates)
#    BEDWARS_TEMPLATE_FORCE         true = always re-stage, even if a world exists
# =============================================================================
set -euo pipefail

SERVER_DIR="${SERVER_DIR:-/server}"
WORLD_DIR="$SERVER_DIR/world"
SOURCE="${BEDWARS_TEMPLATE_SOURCE:-LOCAL}"
NAME="${BEDWARS_TEMPLATE_NAME:-Glacier}"
VERSION="${BEDWARS_TEMPLATE_VERSION:-1.0.0}"
LOCAL_ROOT="${BEDWARS_TEMPLATE_LOCAL_ROOT:-/templates}"
FORCE="${BEDWARS_TEMPLATE_FORCE:-false}"

# What the staged world is called depends on the role: a game pod stages an arena, the lobby
# stages a hub. Only the log wording differs, but a hub warning that "the match will run on a
# generated world" is nonsense, and misleading log lines cost debugging time.
if [ "${BEDWARS_ROLE:-GAME}" = "LOBBY" ]; then
    ROLE_LABEL="hub"
else
    ROLE_LABEL="arena"
fi

log() { echo "[entrypoint] $*"; }

# Optional world generator, applied before the server starts.
#
# A lobby usually wants a flat world (BEDWARS_LEVEL_TYPE=flat) so it does not spend
# time generating terrain around a hub build. A game pod leaves this unset and the
# staged template decides the world.
if [ -n "${BEDWARS_LEVEL_TYPE:-}" ] && [ -f "$SERVER_DIR/server.properties" ]; then
    if grep -q '^level-type=' "$SERVER_DIR/server.properties"; then
        sed -i "s/^level-type=.*/level-type=${BEDWARS_LEVEL_TYPE}/" "$SERVER_DIR/server.properties"
    else
        printf 'level-type=%s\n' "${BEDWARS_LEVEL_TYPE}" >> "$SERVER_DIR/server.properties"
    fi
    log "world generator set to '${BEDWARS_LEVEL_TYPE}'"
fi

stage_local() {
    local src="$LOCAL_ROOT/$NAME"
    if [ ! -d "$src" ]; then
        log "no local template at '$src' - starting with the server's existing world"
        return 1
    fi
    # A directory that is not a world must not be staged. `deploy/templates/lobby/` ships with
    # only a README until an operator drops a hub map in, and copying that over the world
    # directory would leave a stray file in (and the server generating around) it.
    if [ ! -f "$src/level.dat" ]; then
        log "local template directory '$src' holds no world (no level.dat) - ignoring it"
        return 1
    fi
    log "staging local template '$NAME' from $src"
    rm -rf "$WORLD_DIR"
    mkdir -p "$WORLD_DIR"
    cp -a "$src/." "$WORLD_DIR/"
    # A downloaded world carries a lock from wherever it was last opened.
    rm -f "$WORLD_DIR/session.lock"
    return 0
}

stage_s3() {
    local endpoint="${BEDWARS_TEMPLATE_S3_ENDPOINT:-}"
    local bucket="${BEDWARS_TEMPLATE_S3_BUCKET:-}"
    if [ -z "$endpoint" ] || [ -z "$bucket" ]; then
        log "S3 template source selected but endpoint/bucket are unset"
        return 1
    fi
    local url="$endpoint/$bucket/templates/$NAME/$VERSION.zip"
    log "fetching template $url"
    if ! curl -fsSL --max-time 180 "$url" -o /tmp/world.zip; then
        log "template download failed - starting with the server's existing world"
        return 1
    fi
    rm -rf "$WORLD_DIR"
    mkdir -p "$WORLD_DIR"
    if ! unzip -qo /tmp/world.zip -d "$WORLD_DIR"; then
        log "template archive is not a usable zip"
        return 1
    fi
    # Archives may or may not carry a single top-level directory.
    if [ ! -f "$WORLD_DIR/level.dat" ]; then
        local nested
        nested="$(find "$WORLD_DIR" -maxdepth 2 -name level.dat -printf '%h\n' 2>/dev/null | head -1 || true)"
        if [ -n "$nested" ] && [ "$nested" != "$WORLD_DIR" ]; then
            log "flattening nested world directory $nested"
            shopt -s dotglob
            mv "$nested"/* "$WORLD_DIR"/
            shopt -u dotglob
        fi
    fi
    rm -f "$WORLD_DIR/session.lock"
    return 0
}

if [ -f "$WORLD_DIR/level.dat" ] && [ "$FORCE" != "true" ]; then
    log "a world is already present at $WORLD_DIR - not re-staging (BEDWARS_TEMPLATE_FORCE=true to override)"
else
    case "$SOURCE" in
        LOCAL) stage_local || true ;;
        S3)    stage_s3    || true ;;
        *)     log "unknown template source '$SOURCE' - using the server's existing world" ;;
    esac
fi

if [ -f "$WORLD_DIR/level.dat" ]; then
    log "${ROLE_LABEL} world ready: $(du -sh "$WORLD_DIR" 2>/dev/null | cut -f1) at $WORLD_DIR"
else
    log "WARNING: no ${ROLE_LABEL} world staged; the server will run on a generated world"
fi

exec java -jar "$SERVER_DIR/server.jar" --nogui
