#!/bin/sh
# Velocity entrypoint for the BedwarsRecoded proxy.
#
# Exists for one reason: online-mode has to be flippable at run time so the network can
# be brought up in OFFLINE MODE for local testing. The mode is not a build-time choice,
# because the same image must be able to serve a real (online) deployment.
#
# Offline mode means the proxy stops verifying sessions with Mojang: any client can
# connect under any name, and no account is required. It is a TESTING switch. The game
# servers behind the proxy already run offline (they trust the proxy's forwarded
# identity), so this only affects the proxy itself.
set -e

TOML=/proxy/velocity.toml

if [ ! -f "$TOML" ]; then
    echo "[bedwars] $TOML is missing" >&2
    exit 1
fi

# The value is set on EVERY start, not only when turning offline mode on. A container
# that once ran offline keeps the rewritten file through a plain `docker restart`, so
# setting it one way only would leave the effective mode disagreeing with the variable.
if [ "${BEDWARS_OFFLINE_MODE:-false}" = "true" ]; then
    MODE=false
else
    MODE=true
fi
sed -i "s/^online-mode = .*/online-mode = $MODE/" "$TOML"

if [ "$MODE" = "false" ]; then
    echo "[bedwars] ============================================================"
    echo "[bedwars] OFFLINE MODE: the proxy will NOT verify players with Mojang."
    echo "[bedwars] Anyone who can reach this port may join under any name."
    echo "[bedwars] Use this for local testing only - never for a public server."
    echo "[bedwars] ============================================================"
else
    echo "[bedwars] online mode: players are authenticated with Mojang"
fi

# The effective value, for the installer's verification step and for bug reports.
echo "[bedwars] velocity.toml online-mode=$MODE"

exec java ${JAVA_OPTS:-} -jar velocity.jar
