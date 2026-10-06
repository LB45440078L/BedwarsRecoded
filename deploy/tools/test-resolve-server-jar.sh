#!/usr/bin/env bash
# =============================================================================
#  Verifies the game-server image resolves its jar the cheap way
# =============================================================================
#  The point of resolve-server-jar.sh is that the slow Spigot compile happens ONLY
#  when there is no other option. This exercises each branch directly, so a change
#  that quietly reintroduces a per-build compile fails here instead of in CI minutes.
#
#  Usage:  deploy/tools/test-resolve-server-jar.sh          # includes a Paper download
#          SKIP_NETWORK=true deploy/tools/test-resolve-server-jar.sh
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RESOLVER="$HERE/../docker/resolve-server-jar.sh"
UNREACHABLE_BUILD_TOOLS="http://127.0.0.1:1/never-download-this"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok() { echo "  PASS  $*"; pass=$((pass + 1)); }
no() { echo "  FAIL  $*"; fail=$((fail + 1)); }

[ -x "$RESOLVER" ] || { echo "resolver not executable: $RESOLVER"; exit 2; }

echo "== supplied jar wins, and nothing is compiled =="
mkdir -p "$TMP/vendor"
printf 'FAKE-SPIGOT-JAR-CONTENT' >"$TMP/vendor/spigot-26.3.jar"
if VENDOR_DIR="$TMP/vendor" SPIGOT_REV=26.3 BUILD_TOOLS_URL="$UNREACHABLE_BUILD_TOOLS" \
    "$RESOLVER" "$TMP/out-supplied.jar" >"$TMP/log-supplied" 2>&1; then
    if cmp -s "$TMP/vendor/spigot-26.3.jar" "$TMP/out-supplied.jar"; then
        ok "supplied jar copied byte-for-byte"
    else
        no "supplied jar was not copied verbatim"
    fi
    if grep -q "skipping the Spigot compile" "$TMP/log-supplied"; then
        ok "logged that the compile was skipped"
    else
        no "did not report skipping the compile"
    fi
    if grep -q "compiling Spigot" "$TMP/log-supplied"; then
        no "compiled even though a jar was supplied"
    else
        ok "BuildTools never ran"
    fi
else
    no "resolver failed with a supplied jar: $(tail -3 "$TMP/log-supplied")"
fi

echo "== no jar supplied: it falls through to compiling (proved by the URL being unreachable) =="
mkdir -p "$TMP/empty"
if VENDOR_DIR="$TMP/empty" SERVER_ENGINE=spigot SPIGOT_REV=26.3 BUILD_TOOLS_URL="$UNREACHABLE_BUILD_TOOLS" \
    "$RESOLVER" "$TMP/out-compile.jar" >"$TMP/log-compile" 2>&1; then
    no "expected the compile path to be attempted, but the resolver reported success"
else
    if grep -q "compiling Spigot" "$TMP/log-compile"; then
        ok "reached the compile path only because no jar was available"
    else
        no "did not report attempting the compile: $(tail -3 "$TMP/log-compile")"
    fi
fi

echo "== an ambiguous jar folder is refused rather than guessed =="
mkdir -p "$TMP/ambiguous"
printf 'a' >"$TMP/ambiguous/first.jar"
printf 'b' >"$TMP/ambiguous/second.jar"
VENDOR_DIR="$TMP/ambiguous" SPIGOT_REV=26.3 BUILD_TOOLS_URL="$UNREACHABLE_BUILD_TOOLS" \
    "$RESOLVER" "$TMP/out-ambiguous.jar" >"$TMP/log-ambiguous" 2>&1
rc=$?
if [ "$rc" -eq 2 ]; then
    ok "refused an ambiguous server-jars/ (exit 2)"
else
    no "expected exit 2 for an ambiguous jar folder, got $rc"
fi

echo "== engine=paper ignores a supplied spigot jar (the engine is authoritative) =="
mkdir -p "$TMP/mixed"
printf 'SPIGOT-JAR-CONTENT' >"$TMP/mixed/spigot-26.3.jar"
if VENDOR_DIR="$TMP/mixed" SERVER_ENGINE=paper PAPER_VERSION="0.0-not-a-real-version" \
    BUILD_TOOLS_URL="$UNREACHABLE_BUILD_TOOLS" "$RESOLVER" "$TMP/out-mixed.jar" \
    >"$TMP/log-mixed" 2>&1; then
    no "expected the paper path to fail on a bogus Paper version"
else
    if grep -q "using supplied jar" "$TMP/log-mixed"; then
        no "used the spigot jar even though SERVER_ENGINE=paper"
    else
        ok "did not use the spigot jar"
    fi
    if grep -q "ignoring spigot-26.3.jar" "$TMP/log-mixed"; then
        ok "logged that the other engine's jar was ignored"
    else
        no "did not explain why the spigot jar was skipped"
    fi
    if grep -q "could not resolve a Paper" "$TMP/log-mixed"; then
        ok "reported the Paper lookup failure clearly"
    else
        no "did not report a clear reason for the Paper failure"
    fi
    if grep -q "Traceback" "$TMP/log-mixed"; then
        no "leaked a Python traceback instead of a message"
    else
        ok "no Python traceback leaked"
    fi
    if grep -q "blank argument" "$TMP/log-mixed"; then
        no "carried on to curl with an empty URL"
    else
        ok "did not attempt a download with an empty URL"
    fi
fi

echo "== paper: downloaded, never compiled =="
if [ "${SKIP_NETWORK:-false}" = "true" ]; then
    echo "  SKIP  SKIP_NETWORK=true"
else
    if VENDOR_DIR="$TMP/empty" SERVER_ENGINE=paper PAPER_VERSION="${PAPER_VERSION:-26.3}" \
        BUILD_TOOLS_URL="$UNREACHABLE_BUILD_TOOLS" "$RESOLVER" "$TMP/out-paper.jar" \
        >"$TMP/log-paper" 2>&1; then
        size="$(wc -c <"$TMP/out-paper.jar" | tr -d ' ')"
        if [ "$size" -gt 1000000 ]; then
            ok "downloaded a Paper jar ($size bytes)"
        else
            no "Paper jar is suspiciously small ($size bytes)"
        fi
        if grep -q "compiling Spigot" "$TMP/log-paper"; then
            no "the paper engine compiled something"
        else
            ok "nothing was compiled"
        fi
        if grep -q "sha256 verified" "$TMP/log-paper"; then
            ok "download checksum verified"
        else
            no "download checksum was not verified"
        fi
    else
        no "paper download failed: $(tail -3 "$TMP/log-paper")"
    fi
fi

echo
echo "resolve-server-jar: $pass passed, $fail failed"
[ "$fail" -eq 0 ]