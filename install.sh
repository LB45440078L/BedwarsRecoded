#!/usr/bin/env bash
# =============================================================================
#  BedwarsRecoded -- guided installer
# =============================================================================
#
#  One script that takes a fresh checkout to a running network: it checks the
#  machine, asks what you want, writes the configuration, copies your world
#  files into place, builds the images, deploys, and then proves what it built
#  actually came up.
#
#  Usage:
#      ./install.sh                    interactive (recommended)
#      ./install.sh --mode docker      skip the mode question
#      ./install.sh --yes              accept every default (unattended)
#      ./install.sh --dry-run          show every action, change nothing
#      ./install.sh --teardown         remove what a previous run installed
#      ./install.sh --help             all options
#
#  Compatibility: bash 3.2 or newer (the version Apple still ships), on Linux or
#  macOS. Deliberately avoids bash 4+ features -- associative arrays, lowercase
#  expansion, mapfile, namerefs -- so it runs on the oldest bash anyone has.
#
#  Safety: the only thing ever deleted is the stack this script created; every
#  action is logged to .installer/install.log; --dry-run prints commands instead
#  of running them; Ctrl-C aborts with the terminal restored.
# =============================================================================

set -Eeuo pipefail

# ---------------------------------------------------------------------------
#  Constants
# ---------------------------------------------------------------------------
INSTALLER_VERSION="1.0.0"
INSTALLER_NAME="BedwarsRecoded guided installer"

SCRIPT_PATH=""
SCRIPT_DIR=""
ROOT=""
STATE_DIR=""
LOG_FILE=""
STATE_FILE=""

OPT_MODE=""
OPT_YES="0"
OPT_DRY_RUN="0"
OPT_VERBOSE="0"
OPT_NO_COLOR="0"
OPT_TEARDOWN="0"
OPT_RESUME="0"

# Every decision the wizard collects. The defaults are what the repository
# already ships, so pressing Enter through the wizard reproduces the documented
# development stack exactly.
CFG_MODE=""
CFG_ENGINE="spigot"
CFG_SPIGOT_REV="26.3"
CFG_PAPER_VERSION="26.3"
CFG_STACK="network"
CFG_DB_NAME="bedwars"
CFG_DB_USER="bedwars"
CFG_DB_PASSWORD=""
CFG_DB_ROOT_PASSWORD=""
CFG_DB_PORT="3306"
CFG_CONTROLLER_PORT="8080"
CFG_MIN_SERVERS="0"
CFG_MAX_SERVERS="10"
CFG_GAMES_PER_SERVER="25"
CFG_ARENA_GROUP="solo"
CFG_TEMPLATE_SOURCE="LOCAL"
CFG_OBJECT_STORAGE="no"
CFG_S3_BUCKET="bedwars-templates"
CFG_S3_ACCESS_KEY="minioadmin"
CFG_S3_SECRET_KEY="minioadmin"
CFG_MINIO_IMAGE=""
CFG_MC_IMAGE=""
KRUISE_PRESENT="0"
CFG_API_TOKEN=""
#  TESTING ONLY. 'yes' runs the proxy in offline mode (Velocity online-mode=false):
#  clients are not checked against Mojang, so an unauthenticated client can join.
CFG_OFFLINE_MODE="no"
CFG_DEBUG_PORTS="no"
CFG_HUB_WORLD_SRC=""
CFG_HUB_WORLD_NAME="lobby"
CFG_ARENA_WORLD_SRC=""
CFG_SERVER_JAR_NOTE="compiled from source (Spigot via BuildTools)"
CFG_SERVER_JAR_SRC=""
CFG_NAMESPACE="bedwars"
CFG_RELEASE="bedwars"
CFG_K8S_INSTALL_MODE="helm"
CFG_K8S_PROVISION_CLUSTER="no"
CFG_MINIKUBE_CPUS="3"
CFG_MINIKUBE_MEMORY="4096"
CFG_MINIKUBE_DRIVER="docker"
CFG_K8S_LOAD_IMAGES="yes"
CFG_INSTALL_OPENKRUISE="yes"
CFG_USE_IN_CLUSTER_DB="yes"
CFG_EXT_DB_HOST=""
CFG_EXT_DB_NAME="bedwars"
CFG_EXT_DB_USER="bedwars"
CFG_EXT_DB_PASSWORD=""

# ---------------------------------------------------------------------------
#  Logging -- the log is independent of the pretty output, so when the screen
#  shows a spinner the raw line is still recoverable afterwards.
# ---------------------------------------------------------------------------
log_line() {
    [ -n "$LOG_FILE" ] || return 0
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*" >>"$LOG_FILE"
}

log_stream() {
    [ -n "$LOG_FILE" ] || return 0
    while IFS= read -r _line; do
        log_line "    $_line"
    done
}

# ---------------------------------------------------------------------------
#  Colour and terminal detection
# ---------------------------------------------------------------------------
UI_INTERACTIVE="0"
UI_WIDTH="80"
UI_UNICODE="1"
UI_EMOJI="1"
UI_TRUE="0"
C_OFF=""; C_BOLD=""; C_DIM=""; C_ACCENT=""; C_OK=""; C_WARN=""; C_ERR=""; C_MUTED=""

ui_init() {
    [ -t 1 ] && [ -t 0 ] && UI_INTERACTIVE="1" || true
    case "${TERM:-dumb}" in dumb|"") UI_INTERACTIVE="0" ;; esac
    [ "$OPT_NO_COLOR" = "1" ] && UI_INTERACTIVE="0" || true
    [ "${NO_COLOR:-}" != "" ] && UI_INTERACTIVE="0" || true

    local cols=""
    [ "$UI_INTERACTIVE" = "1" ] && cols=$(tput cols 2>/dev/null || true) || true
    case "$cols" in ''|*[!0-9]*) cols=80 ;; esac
    UI_WIDTH="$cols"
    [ "$UI_WIDTH" -gt 96 ] && UI_WIDTH=96 || true
    [ "$UI_WIDTH" -lt 40 ] && UI_WIDTH=40 || true

    # Braille and box-drawing characters are multi-byte; a non-UTF-8 locale
    # renders them as mojibake, so fall back to ASCII rather than print garbage.
    case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
        *UTF-8*|*UTF8*|*utf8*|*utf-8*) UI_UNICODE="1" ;;
        *) UI_UNICODE="0" ;;
    esac
    [ "$UI_INTERACTIVE" = "0" ] && UI_UNICODE="0" || true
    # Emoji live outside the Latin range and a terminal without UTF-8 turns them into
    # mojibake, so they ride on the same condition as box-drawing. Kept as its own
    # switch because a terminal can draw boxes and still lack an emoji font.
    if [ "$UI_UNICODE" = "1" ]; then UI_EMOJI="1"; else UI_EMOJI="0"; fi

    if [ "$UI_INTERACTIVE" = "1" ]; then
        case "${COLORTERM:-}" in
            truecolor|24bit)
                UI_TRUE="1"
                C_ACCENT=$(printf '\033[38;2;56;189;248m')
                C_OK=$(printf '\033[38;2;74;222;128m')
                C_WARN=$(printf '\033[38;2;250;204;21m')
                C_ERR=$(printf '\033[38;2;248;113;113m')
                C_MUTED=$(printf '\033[38;2;148;163;184m')
                ;;
            *)
                C_ACCENT=$(printf '\033[38;5;39m')
                C_OK=$(printf '\033[38;5;42m')
                C_WARN=$(printf '\033[38;5;220m')
                C_ERR=$(printf '\033[38;5;203m')
                C_MUTED=$(printf '\033[38;5;245m')
                ;;
        esac
        C_BOLD=$(printf '\033[1m')
        C_DIM=$(printf '\033[2m')
        C_OFF=$(printf '\033[0m')
    fi
}

# ---------------------------------------------------------------------------
#  UI primitives
# ---------------------------------------------------------------------------
ui_blank() { printf '\n'; }

#  Prints an emoji when the terminal can render one, nothing otherwise. Never left as
#  the last statement of a function: under `set -e` a failing test would end the script.
em() {
    if [ "$UI_EMOJI" = "1" ]; then
        printf '%s' "$1"
    fi
    return 0
}

#  Preflight/test result line, and the same line recorded for the final summary.
#  $1 = mark (ok|warn|err|info), $2 = label, $3 = detail
record_env() {
    local mark="$1" label="$2" detail="${3:-}"
    ENV_ROWS="${ENV_ROWS}${mark}|${label}|${detail}
"
    case "$mark" in
        ok)   ui_ok "$label${detail:+ -- $detail}" ;;
        warn) ui_warn "$label${detail:+ -- $detail}" ;;
        err)  ui_err "$label${detail:+ -- $detail}"; ENV_FAILED="1" ;;
        *)    ui_info "$label${detail:+ -- $detail}" ;;
    esac
}

ui_rule() {
    local ch="-" i=0 line=""
    [ "$UI_UNICODE" = "1" ] && ch="─" || true
    while [ "$i" -lt "$UI_WIDTH" ]; do line="${line}${ch}"; i=$((i+1)); done
    printf '%s%s%s\n' "$C_DIM" "$line" "$C_OFF"
}

ui_banner() {
    printf '\n'
    if [ "$UI_INTERACTIVE" != "1" ] || [ "$UI_WIDTH" -lt 62 ] || [ "$UI_UNICODE" = "0" ]; then
        printf '  %sBedwarsRecoded%s -- guided installer %s\n' "$C_BOLD$C_ACCENT" "$C_OFF" "$INSTALLER_VERSION"
        printf '  %sa disposable Minecraft network%s\n' "$C_MUTED" "$C_OFF"
        return 0
    fi
    local g1="$C_ACCENT" g2="$C_ACCENT"
    [ "$UI_TRUE" = "1" ] && g2=$(printf '\033[38;2;139;92;246m') || true
    printf '%s ██████╗ ███████╗██████╗ ██╗    ██╗ █████╗ ██████╗ ███████╗%s\n' "$g1" "$C_OFF"
    printf '%s ██╔══██╗██╔════╝██╔══██╗██║    ██║██╔══██╗██╔══██╗██╔════╝%s\n' "$g1" "$C_OFF"
    printf '%s ██████╔╝█████╗  ██║  ██║██║ █╗ ██║███████║██████╔╝███████╗%s\n' "$g2" "$C_OFF"
    printf '%s ██╔══██╗██╔══╝  ██║  ██║██║███╗██║██╔══██║██╔══██╗╚════██║%s\n' "$g2" "$C_OFF"
    printf '%s ██████╔╝███████╗██████╔╝╚███╔███╔╝██║  ██║██║  ██║███████║%s\n' "$g2" "$C_OFF"
    printf '%s ╚═════╝ ╚══════╝╚═════╝  ╚══╝╚══╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝%s\n' "$g2" "$C_OFF"
    printf '   %sguided installer %s%s\n' "$C_MUTED" "$INSTALLER_VERSION" "$C_OFF"
}

ui_section() {
    local title="$1" pad i=0 fill=""
    if [ "$UI_UNICODE" != "1" ]; then
        printf '\n%s== %s ==%s\n' "$C_ACCENT" "$title" "$C_OFF"
        return 0
    fi
    pad=$(( UI_WIDTH - ${#title} - 5 ))
    [ "$pad" -lt 1 ] && pad=1 || true
    while [ "$i" -lt "$pad" ]; do fill="${fill}─"; i=$((i+1)); done
    printf '\n%s╭─%s %s%s%s %s%s╮%s\n' "$C_DIM" "$C_OFF" "$C_BOLD" "$C_ACCENT$title" "$C_OFF" "$C_DIM$fill" "$C_DIM" "$C_OFF"
}

ui_ok()   { printf '  %s✔%s %s\n' "$C_OK" "$C_OFF" "$1"; }
ui_warn() { printf '  %s▲%s %s\n' "$C_WARN" "$C_OFF" "$1"; }
ui_err()  { printf '  %s✖%s %s\n' "$C_ERR" "$C_OFF" "$1"; }
ui_info() { printf '  %s•%s %s\n' "$C_ACCENT" "$C_OFF" "$1"; }
ui_hint() { printf '    %s%s%s\n' "$C_MUTED" "$1" "$C_OFF"; }
ui_kv()   { printf '  %s%-26s%s %s\n' "$C_MUTED" "$1" "$C_OFF" "$2"; }

#  A real progress bar. `cur` of `total`, drawn in place.
ui_bar() {
    local cur="$1" total="$2" label="${3:-}"
    [ "$total" -le 0 ] && total=1 || true
    [ "$cur" -gt "$total" ] && cur="$total" || true
    local width=28 pct filled empty i=0 bar=""
    pct=$(( cur * 100 / total ))
    filled=$(( cur * width / total ))
    empty=$(( width - filled ))
    while [ "$i" -lt "$filled" ]; do bar="${bar}█"; i=$((i+1)); done
    i=0
    while [ "$i" -lt "$empty" ]; do bar="${bar}░"; i=$((i+1)); done
    if [ "$UI_INTERACTIVE" = "1" ]; then
        printf '\r  %s%s%s %3d%% %s%s\033[K' "$C_ACCENT" "$bar" "$C_OFF" "$pct" "$label" "$C_OFF"
    else
        # Unattended: one line per 25% so a log stays readable.
        case "$pct" in 25|50|75|100) printf '  [%3d%%] %s\n' "$pct" "$label" ;; esac
    fi
}

ui_bar_done() { [ "$UI_INTERACTIVE" = "1" ] && printf '\n'; return 0; }

#  Spinner. ASCII frames on purpose: multibyte frames can be split by bash 3.2's
#  substring expansion, and a half-rendered glyph looks like a bug.
SPIN_PID=""
SPIN_MSG=""
spin_start() {
    SPIN_MSG="$1"
    if [ "$UI_INTERACTIVE" != "1" ]; then
        printf '  %s ...\n' "$SPIN_MSG"
        return 0
    fi
    printf '\033[?25l'
    (
        i=0
        while :; do
            case $(( i % 4 )) in
                0) f='|' ;; 1) f='/' ;; 2) f='-' ;; *) f='\' ;;
            esac
            printf '\r  %s%s%s %s%s\033[K' "$C_ACCENT" "$f" "$C_OFF" "$SPIN_MSG" "$C_OFF"
            i=$(( i + 1 ))
            sleep 0.12
        done
    ) &
    SPIN_PID=$!
}

spin_stop() {
    if [ -n "$SPIN_PID" ]; then
        kill "$SPIN_PID" 2>/dev/null || true
        wait "$SPIN_PID" 2>/dev/null || true
        SPIN_PID=""
    fi
    if [ "$UI_INTERACTIVE" = "1" ]; then
        printf '\r\033[K\033[?25h'
    fi
}

# ---------------------------------------------------------------------------
#  Failure handling. An installer that dies silently is worse than one that
#  refuses to start, so every abort prints what failed, where, and the tail of
#  the output, then tells you where the full log is.
# ---------------------------------------------------------------------------
LAST_FAILURE=""

fail() {
    # fail <message> [hint]
    local msg="$1" hint="${2:-}"
    spin_stop || true
    printf '\n'
    printf '  %s╭──────────────────────────────────────────────╮%s\n' "$C_ERR" "$C_OFF"
    printf '  %s│%s %s%sinstaller stopped%s                          %s│%s\n' "$C_ERR" "$C_OFF" "$C_BOLD" "$C_ERR" "$C_OFF" "$C_ERR" "$C_OFF"
    printf '  %s╰──────────────────────────────────────────────╯%s\n' "$C_ERR" "$C_OFF"
    printf '\n'
    ui_err "$msg"
    [ -n "$hint" ] && ui_hint "$hint" || true
    if [ -n "$LAST_FAILURE" ] && [ -f "$LAST_FAILURE" ]; then
        printf '\n'
        ui_hint "last output:"
        while IFS= read -r _l; do ui_hint "  $_l"; done < <(tail -n 15 "$LAST_FAILURE" 2>/dev/null || true)
    fi
    printf '\n'
    ui_hint "full log: $LOG_FILE"
    exit 1
}

on_err() {
    local rc="$1" line="$2" cmd="$3"
    spin_stop || true
    fail "unexpected failure (exit $rc) at line $line: $cmd" \
         "This is a bug in the installer, not necessarily in your setup. The log has the full trace."
}

on_signal() {
    spin_stop || true
    printf '\n'
    ui_warn "cancelled"
    ui_hint "nothing was left half-written: configuration files are only replaced once complete"
    ui_hint "if the stack was already starting, tear it down with: $SCRIPT_PATH --teardown"
    exit 130
}

cleanup_ui() {
    printf '\033[?25h' 2>/dev/null || true
}

# ---------------------------------------------------------------------------
#  Steps -- the progress state the user sees across the whole run
# ---------------------------------------------------------------------------
STEP_N=0
STEP_TOTAL=0

step_total() { STEP_TOTAL="$1"; STEP_N=0; }

#  A section that is not part of the numbered execution: the wizard and the
#  preflight are conversational, and numbering them would make [3/10] mean
#  nothing by the time the real steps start.
phase() { ui_section "$1"; }

step_begin() {
    STEP_N=$(( STEP_N + 1 ))
    ui_section "[$STEP_N/$STEP_TOTAL] $1"
}

# ---------------------------------------------------------------------------
#  Running commands
# ---------------------------------------------------------------------------
#  run_cmd <label> <command...>
#  Shows a spinner while it runs, keeps the output in a log, and on failure
#  prints the tail. Under --dry-run it prints the command and does nothing.
run_cmd() {
    local label="$1"; shift
    if [ "$OPT_DRY_RUN" = "1" ]; then
        printf '  %s[dry-run]%s %s\n' "$C_MUTED" "$C_OFF" "$*"
        log_line "DRY-RUN: $*"
        return 0
    fi
    log_line "RUN: $*"
    LAST_FAILURE="$STATE_DIR/last-command.log"
    local rc=0
    spin_start "$label"
    "$@" >"$LAST_FAILURE" 2>&1 || rc=$?
    spin_stop
    if [ "$rc" -ne 0 ]; then
        log_line "FAILED ($rc): $*"
        return "$rc"
    fi
    log_line "OK: $*"
    return 0
}

#  run_cmd_soft: same, but prints the output on failure instead of aborting, so
#  the caller can decide. Used where a failure is expected and recoverable
#  (a registry that refuses an anonymous pull, a port already in use).
run_cmd_soft() {
    local label="$1"; shift
    if [ "$OPT_DRY_RUN" = "1" ]; then
        printf '  %s[dry-run]%s %s\n' "$C_MUTED" "$C_OFF" "$*"
        log_line "DRY-RUN: $*"
        return 0
    fi
    log_line "RUN(soft): $*"
    LAST_FAILURE="$STATE_DIR/last-command.log"
    local rc=0
    if [ "$OPT_VERBOSE" = "1" ]; then
        printf '  %s$ %s%s\n' "$C_MUTED" "$*" "$C_OFF"
    else
        spin_start "$label"
    fi
    "$@" >"$LAST_FAILURE" 2>&1 || rc=$?
    spin_stop
    if [ "$rc" -ne 0 ]; then
        log_line "SOFT-FAILED ($rc): $*"
        [ "$OPT_VERBOSE" != "1" ] && ui_warn "$label failed (exit $rc)" || true
        if [ "$OPT_VERBOSE" != "1" ]; then
            while IFS= read -r _l; do ui_hint "  $_l"; done < <(tail -n 4 "$LAST_FAILURE" 2>/dev/null || true)
        fi
    else
        log_line "OK: $*"
    fi
    return "$rc"
}

#  Capture stdout of a command into $RUN_OUT (no spinner: used for queries).
RUN_OUT=""
run_capture() {
    local rc=0
    RUN_OUT=$("$@" 2>/dev/null) || rc=$?
    log_line "CAPTURE($rc): $* -> $(printf '%s' "$RUN_OUT" | head -n 3 | tr '\n' ' ')"
    return "$rc"
}

#  Progress bar over a real unit of work: files copied.
copy_tree_progress() {
    # copy_tree_progress <src> <dst> <label>
    local src="$1" dst="$2" label="$3"
    local total=0 done=0
    if [ "$OPT_DRY_RUN" = "1" ]; then
        printf '  %s[dry-run]%s copy %s -> %s\n' "$C_MUTED" "$C_OFF" "$src" "$dst"
        return 0
    fi
    if [ ! -d "$src" ]; then
        return 0
    fi
    total=$(find "$src" -type f 2>/dev/null | wc -l | tr -d ' ')
    [ "$total" -lt 1 ] && total=1 || true
    log_line "COPY: $src -> $dst ($total files)"
    mkdir -p "$dst"
    #  One file at a time so the bar is real progress, not a decoration.
    while IFS= read -r _f; do
        local rel="${_f#$src/}"
        local target="$dst/$rel"
        mkdir -p "$(dirname "$target")"
        cp -p "$_f" "$target" 2>/dev/null || cp "$_f" "$target"
        done=$(( done + 1 ))
        if [ "$UI_INTERACTIVE" = "1" ]; then
            [ $(( done % 25 )) -eq 0 ] && ui_bar "$done" "$total" "$label ($done/$total)" || true
        fi
    done < <(find "$src" -type f 2>/dev/null)
    ui_bar "$total" "$total" "$label ($total/$total)"
    ui_bar_done
}

# ---------------------------------------------------------------------------
#  Waiting -- never `sleep` and hope. Poll the thing that actually means ready.
# ---------------------------------------------------------------------------
wait_for() {
    # wait_for <label> <timeout-seconds> <command...>
    local label="$1" timeout="$2"; shift 2
    if [ "$OPT_DRY_RUN" = "1" ]; then
        printf '  %s[dry-run]%s would wait up to %ss for: %s\n' "$C_MUTED" "$C_OFF" "$timeout" "$label"
        log_line "DRY-RUN wait: $label"
        return 0
    fi
    local waited=0 step=2
    while [ "$waited" -lt "$timeout" ]; do
        if "$@" >/dev/null 2>&1; then
            return 0
        fi
        if [ "$UI_INTERACTIVE" = "1" ]; then
            ui_bar "$waited" "$timeout" "$label (${waited}s/${timeout}s)"
        fi
        sleep "$step"
        waited=$(( waited + step ))
    done
    ui_bar "$timeout" "$timeout" "$label (timeout)"
    ui_bar_done
    return 1
}

port_open() {
    # Pure bash TCP test: no nc, no /dev/tcp quirks across platforms.
    local host="$1" port="$2"
    ( exec 3<>"/dev/tcp/$host/$port" ) >/dev/null 2>&1
}

http_ok() {
    local url="$1"
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null || echo 000)
    [ "$code" = "200" ]
}

#  Grep a LOG FILE, never a live pipe: `set -o pipefail` turns grep -q's
#  early exit into a SIGPIPE failure, so a check that matched reports failure.
file_has() {
    grep -q -F -- "$2" "$1" 2>/dev/null
}

# ---------------------------------------------------------------------------
#  Input helpers
# ---------------------------------------------------------------------------
set_var() { printf -v "$1" '%s' "$2"; }
get_var() { eval "printf '%s' \"\${$1:-}\""; }

v_port()    { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
v_int()     { case "$1" in ''|*[!0-9]*) return 1 ;; esac; return 0; }
v_nonempty() { [ -n "$1" ]; }
v_identifier() { case "$1" in *[!A-Za-z0-9_-]*) return 1 ;; esac; [ -n "$1" ]; }
v_dnsname() { case "$1" in [a-z0-9]*) case "$1" in *[!a-z0-9-]*) return 1 ;; esac; return 0 ;; *) return 1 ;; esac; }
v_dir()     { [ -d "$1" ]; }
v_file()    { [ -f "$1" ]; }
v_worlddir() { [ -f "$1/level.dat" ]; }

#  ask <VARNAME> <question> <default> <validator> <hint>
ask() {
    local var="$1" question="$2" default="$3" validator="${4:-v_nonempty}" hint="${5:-}"
    local answer=""
    if [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
        set_var "$var" "$default"
        printf '  %s%s%s %s%s%s\n' "$C_MUTED" "$question" "$C_OFF" "$C_BOLD" "$default" "$C_OFF"
        return 0
    fi
    while :; do
        printf '  %s?%s %s %s[%s]%s ' "$C_ACCENT" "$C_OFF" "$question" "$C_MUTED" "$default" "$C_OFF"
        answer=""
        read -r answer || true
        [ -z "$answer" ] && answer="$default" || true
        if "$validator" "$answer"; then
            set_var "$var" "$answer"
            return 0
        fi
        ui_err "that value is not valid"
        [ -n "$hint" ] && ui_hint "$hint" || true
    done
}

#  ask_yesno <VARNAME> <question> <y|n default>
ask_yesno() {
    local var="$1" question="$2" default="$3"
    local answer="" shown="yes"
    [ "$default" = "n" ] && shown="no" || true
    if [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
        set_var "$var" "$default"
        printf '  %s%s%s %s%s%s\n' "$C_MUTED" "$question" "$C_OFF" "$C_BOLD" "$shown" "$C_OFF"
        return 0
    fi
    while :; do
        printf '  %s?%s %s %s(y/n, %s)%s ' "$C_ACCENT" "$C_OFF" "$question" "$C_MUTED" "$shown" "$C_OFF"
        answer=""
        read -r answer || true
        [ -z "$answer" ] && answer="$default" || true
        case "$answer" in
            y|Y|yes|YES|Yes) set_var "$var" "y"; return 0 ;;
            n|N|no|NO|No)    set_var "$var" "n"; return 0 ;;
        esac
        ui_err "answer y or n"
    done
}

#  ask_menu <VARNAME> <question> <default-index> <"index|Label|Description">...
ask_menu() {
    local var="$1" question="$2" default="$3"; shift 3
    local opt answer="" i=0 count=$#
    if [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
        for opt in "$@"; do
            i=$(( i + 1 ))
            if [ "$i" = "$default" ]; then
                set_var "$var" "$(printf '%s' "$opt" | cut -d'|' -f1)"
                printf '  %s%s%s %s%s%s\n' "$C_MUTED" "$question" "$C_OFF" "$C_BOLD" \
                    "$(printf '%s' "$opt" | cut -d'|' -f2)" "$C_OFF"
            fi
        done
        return 0
    fi
    printf '\n  %s%s%s\n' "$C_BOLD" "$question" "$C_OFF"
    for opt in "$@"; do
        local idx label desc
        idx=$(printf '%s' "$opt" | cut -d'|' -f1)
        label=$(printf '%s' "$opt" | cut -d'|' -f2)
        desc=$(printf '%s' "$opt" | cut -d'|' -f3)
        if [ "$idx" = "$default" ]; then
            printf '    %s%s)%s %s%s  %s(default)%s\n' "$C_ACCENT" "$idx" "$C_OFF" "$C_BOLD" "$label" "$C_MUTED" "$C_OFF"
        else
            printf '    %s%s)%s %s\n' "$C_ACCENT" "$idx" "$C_OFF" "$label"
        fi
        [ -n "$desc" ] && printf '       %s%s%s\n' "$C_MUTED" "$desc" "$C_OFF" || true
    done
    while :; do
        printf '\n  %s?%s choose [%s] ' "$C_ACCENT" "$C_OFF" "$default"
        answer=""
        read -r answer || true
        [ -z "$answer" ] && answer="$default" || true
        if v_int "$answer" && [ "$answer" -ge 1 ] && [ "$answer" -le "$count" ]; then
            set_var "$var" "$answer"
            return 0
        fi
        ui_err "enter a number between 1 and $count"
    done
}

#  ask_secret <VARNAME> <question> <default> -- never echoes what was typed
ask_secret() {
    local var="$1" question="$2" default="$3"
    local answer=""
    if [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
        set_var "$var" "$default"
        printf '  %s%s%s %s%s%s\n' "$C_MUTED" "$question" "$C_OFF" "$C_BOLD" "[generated]" "$C_OFF"
        return 0
    fi
    printf '  %s?%s %s %s(blank = generate one)%s ' "$C_ACCENT" "$C_OFF" "$question" "$C_MUTED" "$C_OFF"
    read -rs answer || true
    printf '\n'
    [ -z "$answer" ] && answer="$default" || true
    set_var "$var" "$answer"
}

#  A short, readable random secret for defaults that must not be blank.
random_secret() {
    local n="${1:-18}" s=""
    if command -v openssl >/dev/null 2>&1; then
        #  Ask for more bytes than needed: base64 then drops '+', '/' and '=' below, so
        #  a fixed 32 would sometimes come back as 30 characters.
        s=$(openssl rand -base64 $(( n + 8 )) 2>/dev/null | tr -dc 'A-Za-z0-9' | cut -c1-"$n")
    fi
    if [ -z "$s" ]; then
        s=$( (date +%s; printf '%s' "$RANDOM$RANDOM$RANDOM") | cksum | tr -dc '0-9' | cut -c1-"$n")
    fi
    [ -z "$s" ] && s="bedwars-secret" || true
    printf '%s' "$s"
}

#  ask_path <VARNAME> <question> <default> <validator> <hint>
ask_path() {
    local var="$1" question="$2" default="$3" validator="${4:-v_nonempty}" hint="${5:-}"
    local answer=""
    if [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
        set_var "$var" "$default"
        return 0
    fi
    while :; do
        printf '  %s?%s %s\n    %s%s%s ' "$C_ACCENT" "$C_OFF" "$question" "$C_MUTED" "$default" "$C_OFF"
        answer=""
        read -r answer || true
        [ -z "$answer" ] && answer="$default" || true
        # Expand ~ ourselves; read does not.
        case "$answer" in "~/"*) answer="$HOME/${answer#~/}" ;; "~") answer="$HOME" ;; esac
        if [ "$validator" "$answer" ]; then
            set_var "$var" "$answer"
            return 0
        fi
        ui_err "that path is not valid"
        [ -n "$hint" ] && ui_hint "$hint" || true
    done
}

# ---------------------------------------------------------------------------
#  Arguments
# ---------------------------------------------------------------------------
usage() {
    cat <<'USAGE'
BedwarsRecoded -- guided installer

Usage:
  ./install.sh [options]

Options:
  --mode <docker|kubernetes>  Skip the deployment-mode question.
  --yes                       Accept every default. Fully unattended.
  --dry-run                   Print every action instead of doing it. Nothing is
                              written, built or started.
  --resume                    Reuse the .installer/state from a previous run as
                              the defaults, instead of the shipped defaults.
  --teardown                  Remove the stack this installer created, and stop.
  --verbose                   Echo each command as it runs (implies no spinner).
  --no-color                  Plain output. Also honoured: NO_COLOR=1.
  -h, --help                  This text.

Examples:
  ./install.sh                                  # the full guided experience
  ./install.sh --mode docker --yes              # unattended Docker stack
  ./install.sh --dry-run                        # see what it would do
  ./install.sh --teardown                       # clean up afterwards

What it does, in order:
  1. Checks the machine (tools, daemon, cluster, ports, disk).
  2. Asks what you want: Docker or Kubernetes, and every parameter, with the
     repository's own defaults as one Enter away.
  3. Copies your world files and server jar into place.
  4. Writes deploy/compose/.env (or a Helm values override) with your answers.
  5. Builds the images and starts the stack.
  6. Waits for readiness and then proves it: controller health, the lobby
     reporting LOBBY, the proxy resolving its lobby server, and no hub
     masquerading as a match host in the registry.
  7. Prints how to connect and how to tear it down.

Everything is logged to .installer/install.log.
USAGE
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --mode)
                [ $# -ge 2 ] || fail "--mode needs a value" "try --mode docker or --mode kubernetes"
                OPT_MODE="$2"; shift 2 ;;
            --mode=*) OPT_MODE="${1#--mode=}"; shift ;;
            --yes|-y) OPT_YES="1"; shift ;;
            --dry-run) OPT_DRY_RUN="1"; shift ;;
            --resume) OPT_RESUME="1"; shift ;;
            --teardown) OPT_TEARDOWN="1"; shift ;;
            --verbose|-v) OPT_VERBOSE="1"; shift ;;
            --no-color) OPT_NO_COLOR="1"; shift ;;
            -h|--help) usage; exit 0 ;;
            *)
                printf 'unknown option: %s\n\n' "$1" >&2
                usage >&2
                exit 2 ;;
        esac
    done
    case "$OPT_MODE" in
        ""|docker|kubernetes|k8s) : ;;
        *) printf 'unknown --mode: %s (expected docker or kubernetes)\n' "$OPT_MODE" >&2; exit 2 ;;
    esac
    [ "$OPT_MODE" = "k8s" ] && OPT_MODE="kubernetes" || true
}

# ---------------------------------------------------------------------------
#  Locate the repository
# ---------------------------------------------------------------------------
locate_root() {
    SCRIPT_PATH="$0"
    # Resolve a symlinked script, so `ln -s ~/code/BedwarsRecoded/install.sh ~/bin/...` works.
    if command -v readlink >/dev/null 2>&1; then
        local target
        target=$(readlink -f "$0" 2>/dev/null || readlink "$0" 2>/dev/null || true)
        [ -n "$target" ] && SCRIPT_PATH="$target" || true
    fi
    SCRIPT_DIR=$(cd "$(dirname "$SCRIPT_PATH")" && pwd)

    local dir="$SCRIPT_DIR" i=0
    while [ "$i" -lt 5 ]; do
        if [ -d "$dir/BedwarsRecoded-Spigot" ] && [ -d "$dir/BedwarsRecoded-Core" ] \
           && [ -f "$dir/deploy/verify_deploy.py" ]; then
            ROOT="$dir"
            return 0
        fi
        dir=$(dirname "$dir")
        i=$(( i + 1 ))
    done
    fail "this script is not inside a BedwarsRecoded checkout" \
         "run it from the repository root: ./install.sh  (expected to find BedwarsRecoded-Core/ and deploy/ next to it)"
}

# ---------------------------------------------------------------------------
#  State -- a record of what this installer did, so teardown and --resume work
# ---------------------------------------------------------------------------
save_state() {
    [ -n "$STATE_DIR" ] || return 0
    {
        printf 'INSTALLER_VERSION=%s\n' "$INSTALLER_VERSION"
        printf 'INSTALLED_AT=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S')"
        printf 'MODE=%s\n' "$CFG_MODE"
        printf 'COMPOSE_PROJECT=%s\n' "bedwars"
        printf 'NETWORK_NAME=%s\n' "bedwars_bedwars"
        printf 'NAMESPACE=%s\n' "$CFG_NAMESPACE"
        printf 'RELEASE=%s\n' "$CFG_RELEASE"
        printf 'K8S_INSTALL_MODE=%s\n' "$CFG_K8S_INSTALL_MODE"
        printf 'STACK=%s\n' "$CFG_STACK"
        printf 'ENGINE=%s\n' "$CFG_ENGINE"
        printf 'CONTROLLER_PORT=%s\n' "$CFG_CONTROLLER_PORT"
    } >"$STATE_FILE"
    log_line "state written: $STATE_FILE"
}

load_state() {
    [ -f "$STATE_FILE" ] || return 0
    log_line "loading previous state from $STATE_FILE"
    # Only shell-safe KEY=VALUE lines, read one at a time.
    while IFS= read -r _line; do
        case "$_line" in
            *=*) key="${_line%%=*}"; val="${_line#*=}"
                 case "$key" in
                     MODE) CFG_MODE="$val" ;;
                     NAMESPACE) CFG_NAMESPACE="$val" ;;
                     RELEASE) CFG_RELEASE="$val" ;;
                     K8S_INSTALL_MODE) CFG_K8S_INSTALL_MODE="$val" ;;
                     STACK) CFG_STACK="$val" ;;
                     ENGINE) CFG_ENGINE="$val" ;;
                     CONTROLLER_PORT) CFG_CONTROLLER_PORT="$val" ;;
                 esac
                 ;;
        esac
    done <"$STATE_FILE"
}

# ---------------------------------------------------------------------------
#  Preflight -- what the machine has, before we ask the user anything
# ---------------------------------------------------------------------------
HAVE_DOCKER="0"
HAVE_DOCKER_COMPOSE="0"
HAVE_KUBECTL="0"
HAVE_HELM="0"
HAVE_CLUSTER="0"
HAVE_MINIKUBE="0"
HAVE_CURL="0"
HAVE_SM_CRD="0"
HAVE_KEDA_CRD="0"
DOCKER_VERSION=""
CLUSTER_CONTEXT=""
#  Version strings for the preflight report, empty when the tool is absent. Java and
#  Maven are only needed to build the plugin from source here (the images build their
#  own), so they are reported, not required.
JAVA_VERSION=""
JAVA_MAJOR=""
MAVEN_VERSION=""
GIT_VERSION=""
PYTHON_VERSION=""
#  One line per preflight result, rendered again in the final summary:
#  "mark<TAB>label<TAB>detail". Newline-separated: no associative arrays on bash 3.2.
ENV_ROWS=""
ENV_FAILED="0"

probe_tools() {
    local missing="0"
    # --- required on every path ---------------------------------------------
    if command -v curl >/dev/null 2>&1; then
        HAVE_CURL="1"
        record_env ok "curl" "$(curl --version 2>/dev/null | head -1 | awk '{print $1, $2}')"
    else
        record_env err "curl" "required for the health checks and not installed"
        missing="1"
    fi

    # --- the container runtime ----------------------------------------------
    #  Both paths need it: the images (the Spigot server, the controller, the proxy)
    #  are built here and then either run or loaded into the cluster.
    if command -v docker >/dev/null 2>&1; then
        DOCKER_VERSION=$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)
        if [ -n "$DOCKER_VERSION" ]; then
            HAVE_DOCKER="1"
            record_env ok "docker" "$DOCKER_VERSION, daemon reachable"
            if docker compose version >/dev/null 2>&1; then
                HAVE_DOCKER_COMPOSE="1"
                record_env ok "docker compose" "$(docker compose version --short 2>/dev/null || echo v2)"
            else
                record_env warn "docker compose" "the v2 'docker compose' plugin is missing; the Docker path needs it"
            fi
        else
            record_env warn "docker" "installed, but its daemon is not answering"
            ui_hint "start it, then re-run: Docker Desktop, or 'sudo systemctl start docker' on Linux"
        fi
    else
        record_env warn "docker" "not found -- the images are built with it on both paths"
    fi

    # --- Kubernetes ----------------------------------------------------------
    if command -v kubectl >/dev/null 2>&1; then
        HAVE_KUBECTL="1"
        local kver
        kver=$(kubectl version --client -o yaml 2>/dev/null | grep -m1 'gitVersion' | tr -d ' "' | cut -d: -f2 || true)
        record_env ok "kubectl" "${kver:-present}"
        if kubectl cluster-info >/dev/null 2>&1; then
            HAVE_CLUSTER="1"
            CLUSTER_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "unknown")
            record_env ok "cluster" "reachable, context '$CLUSTER_CONTEXT'"
        else
            record_env warn "cluster" "no cluster is reachable from the current kubectl context"
        fi
    else
        record_env info "kubectl" "absent -- only the Kubernetes path needs it"
    fi
    if command -v minikube >/dev/null 2>&1; then
        HAVE_MINIKUBE="1"
        record_env info "minikube" "$(minikube version --short 2>/dev/null || echo present) -- a local cluster can be started for you"
    fi
    if command -v helm >/dev/null 2>&1; then
        HAVE_HELM="1"
        record_env ok "helm" "$(helm version --short 2>/dev/null || echo present)"
    else
        record_env info "helm" "absent -- Kubernetes can still use the plain manifests"
    fi

    # --- tooling the repository uses to build and test itself ----------------
    #  None of this is needed to RUN the network: the images carry their own JDK and
    #  build the plugin inside the build stage. It is needed to build the plugin here,
    #  or to run the repository's own test suite, so it is reported, never required.
    if command -v java >/dev/null 2>&1; then
        JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')
        JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION" | cut -d. -f1)
        case "$JAVA_MAJOR" in ''|*[!0-9]*) JAVA_MAJOR="" ;; esac
        if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 25 ]; then
            record_env ok "java" "$JAVA_VERSION -- can build the plugin on this machine"
        else
            record_env info "java" "${JAVA_VERSION:-present} -- the plugin is built with Java 25"
        fi
    else
        record_env info "java" "absent -- not needed to run the network (the images carry a JDK)"
    fi
    if command -v mvn >/dev/null 2>&1; then
        MAVEN_VERSION=$(mvn -v 2>/dev/null | head -1 | awk '{print $3}')
        record_env ok "maven" "${MAVEN_VERSION:-present} -- can build the plugin and run its tests"
    else
        record_env info "maven" "absent -- only needed to build the plugin on this machine"
    fi
    if command -v git >/dev/null 2>&1; then
        GIT_VERSION=$(git --version 2>/dev/null | awk '{print $3}')
        record_env info "git" "${GIT_VERSION:-present}"
    fi
    if command -v python3 >/dev/null 2>&1; then
        PYTHON_VERSION=$(python3 --version 2>/dev/null | awk '{print $2}')
        record_env info "python3" "${PYTHON_VERSION:-present} -- used by the helper scripts"
    fi

    if [ "$missing" = "1" ]; then
        fail "a required tool is missing" "install what is marked above and re-run"
    fi
    return 0
}

check_disk() {
    # Images plus MySQL data are a few gigabytes; warn early rather than halfway.
    local avail_kb
    avail_kb=$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2 {print $4}')
    case "$avail_kb" in ''|*[!0-9]*) return 0 ;; esac
    local avail_gb=$(( avail_kb / 1024 / 1024 ))
    if [ "$avail_gb" -lt 5 ]; then
        record_env warn "disk" "only ${avail_gb} GB free -- the images and database want ~5 GB"
    else
        record_env ok "disk" "${avail_gb} GB free"
    fi
}

check_port_free() {
    local port="$1" what="$2"
    if port_open 127.0.0.1 "$port"; then
        ui_warn "port $port is already in use -- $what may fail to bind"
        ui_hint "re-run with a different port, or stop whatever is listening on $port"
        return 1
    fi
    ui_ok "port $port is free ($what)"
    return 0
}

# ---------------------------------------------------------------------------
#  Wizard: deployment mode
# ---------------------------------------------------------------------------
#  Capacity is servers x matches-per-server, and that product is the only number that
#  tells a user what they actually bought. Printed wherever the two are asked for.
capacity_note() {
    if [ "$CFG_MIN_SERVERS" -gt "$CFG_MAX_SERVERS" ]; then
        ui_warn "minimum ($CFG_MIN_SERVERS) is above maximum ($CFG_MAX_SERVERS); using the maximum as the minimum"
        CFG_MIN_SERVERS="$CFG_MAX_SERVERS"
    fi
    local cap=$(( CFG_MAX_SERVERS * CFG_GAMES_PER_SERVER ))
    ui_ok "capacity: $CFG_MAX_SERVERS servers x $CFG_GAMES_PER_SERVER matches each = $cap matches at once (arena group '$CFG_ARENA_GROUP')"
    if [ "$CFG_MIN_SERVERS" = "0" ]; then
        ui_hint "with a minimum of 0 the fleet starts empty, and scales back to nothing when idle"
    else
        ui_hint "the minimum keeps $CFG_MIN_SERVERS server(s) warm even with nobody playing"
    fi
    return 0
}

#  The controller's write endpoints are the ones a pod could abuse: it could claim
#  capacity it does not have, or ask to be sent players. A shared secret is what makes
#  them pod-only. Generated here and written into the deployment config, so the user
#  never copies it anywhere by hand.
wizard_api_token() {
    phase "$(em '🔒') Controller authentication"
    ui_hint "The controller's write endpoints (/pods/*, /lobby/queue) are called by the game"
    ui_hint "pods and by the proxy. A shared token means only they can call them."
    ui_hint "It is generated here and handed to every client automatically."
    local want="y"
    if [ "$OPT_YES" != "1" ]; then
        ask_yesno want "Generate a shared API token for the controller?" "y"
    fi
    if [ "$want" = "y" ]; then
        CFG_API_TOKEN=$(random_secret 32)
        ui_ok "token generated (${#CFG_API_TOKEN} characters) -- written to the config, never printed"
    else
        CFG_API_TOKEN=""
        ui_warn "the controller's write endpoints will be OPEN to anything that can reach them"
        ui_hint "fine on a private host; read chapter 21 of docs/MANUAL.md before exposing it"
    fi
    return 0
}

#  Offline mode is a PROXY setting: whether Velocity checks each player with Mojang.
#  It only makes sense when the stack contains the proxy, so the caller decides.
wizard_offline_mode() {
    phase "$(em '🌐') Player authentication"
    ui_hint "Velocity normally verifies every player against Mojang before letting them in."
    ui_hint "Offline mode skips that check, so a client with no Mojang session can connect."
    ui_hint "That is how you test with an offline client -- and it also lets anyone use any name."
    ui_hint "The game servers behind the proxy already run offline: they trust the proxy."
    local off="n"
    if [ "$OPT_YES" != "1" ]; then
        ask_yesno off "Run the proxy in OFFLINE MODE (testing only)?" "n"
    fi
    if [ "$off" = "y" ]; then
        CFG_OFFLINE_MODE="yes"
        ui_warn "offline mode ON -- anyone who can reach the proxy port can join as anyone"
        ui_hint "never leave this on for a server the public can reach"
    else
        CFG_OFFLINE_MODE="no"
        ui_ok "online mode -- players are authenticated with Mojang"
    fi
    return 0
}

wizard_mode() {
    phase "$(em '🧭') How should this run?"
    if [ -n "$OPT_MODE" ]; then
        CFG_MODE="$OPT_MODE"
        ui_info "deployment mode: $CFG_MODE (from --mode)"
        return 0
    fi
    local dflt="1" reco=""
    if [ "$HAVE_DOCKER" != "1" ] && [ "$HAVE_CLUSTER" = "1" ]; then
        #  Docker cannot run here and a cluster can: the choice makes itself.
        dflt="2"
        reco="Docker is not available on this machine, and a cluster is reachable."
    elif [ "$HAVE_CLUSTER" = "1" ]; then
        dflt="1"
        reco="Both are ready. Docker is the shorter path; Kubernetes is for disposable pods and autoscaling."
    elif [ "$HAVE_DOCKER" = "1" ]; then
        dflt="1"
        reco="No cluster is reachable yet, so Docker is the one this machine is ready for."
    elif [ "$HAVE_KUBECTL" = "1" ] && [ "$HAVE_MINIKUBE" = "1" ]; then
        dflt="2"
        reco="Docker is not available; minikube can create a cluster for you instead."
    fi

    local choice=""
    ask_menu choice "Where should the network run?" "$dflt" \
        "1|Docker Compose|One host, one daemon. The fastest path to a playable network, and the only one that needs nothing but Docker." \
        "2|Kubernetes|A cluster: disposable game pods, KEDA scaling, rolling updates. Needs kubectl and a reachable cluster."
    if [ "$choice" = "1" ]; then
        CFG_MODE="docker"
    else
        CFG_MODE="kubernetes"
    fi
    [ -n "$reco" ] && ui_hint "$reco" || true
    ui_ok "deployment mode: $CFG_MODE"
}

# ---------------------------------------------------------------------------
#  Wizard: Docker
# ---------------------------------------------------------------------------
wizard_docker() {
    phase "$(em '🐳') Docker: what to bring up"
    local choice=""
    ask_menu choice "Which services?" "3" \
        "1|Controller only|MySQL, object storage and the controller (the control plane). No Minecraft server at all." \
        "2|Controller + one game server|Adds a real Spigot game pod on port 25567, so you can drive matches directly." \
        "3|Full network|Controller, the lobby hub and the Velocity proxy published on 25565. What a player connects to." \
        "4|Everything|The full network plus a manually-started game pod, for debugging."
    case "$choice" in
        1) CFG_STACK="infra" ;;
        2) CFG_STACK="game" ;;
        3) CFG_STACK="network" ;;
        4) CFG_STACK="everything" ;;
    esac
    ui_ok "stack: $CFG_STACK"

    if [ "$CFG_STACK" = "game" ] || [ "$CFG_STACK" = "everything" ]; then
        phase "$(em '⚙️') Docker: engine for the game servers"
        if [ "$OPT_YES" = "1" ]; then
            ui_info "engine: spigot (supplied jar is used if present)"
        else
            ui_hint "Spigot has no official download; a jar in server-jars/ is used as-is, and"
            ui_hint "without one the image compiles Spigot with BuildTools (minutes, first build only)."
            ui_hint "Paper is never compiled -- it is downloaded."
            ask_menu choice "Which server engine?" "1" \
                "1|Spigot 26.3|The brief's default. Exact Spigot, never Paper. Slower first build without a jar." \
                "2|Paper 26.3|Built from the PaperMC API. Faster to build, and still loads this plugin unchanged."
            [ "$choice" = "2" ] && CFG_ENGINE="paper" || CFG_ENGINE="spigot" || true
        fi
        ui_ok "engine: $CFG_ENGINE"
    fi

    phase "$(em '🗄️') Docker: database"
    ask CFG_DB_NAME "Database name" "bedwars" v_identifier
    ask CFG_DB_USER "Database user" "bedwars" v_identifier
    ask CFG_DB_PORT "Published MySQL port" "3306" v_port
    local gen="n"
    ask_yesno gen "Generate strong random database passwords?" "n"
    if [ "$gen" = "y" ]; then
        CFG_DB_PASSWORD=$(random_secret 20)
        CFG_DB_ROOT_PASSWORD=$(random_secret 20)
        ui_ok "passwords generated (written to deploy/compose/.env, shown in the summary)"
    else
        CFG_DB_PASSWORD="bedwars"
        CFG_DB_ROOT_PASSWORD="rootpw"
        ui_hint "using the repository's development passwords"
    fi

    phase "$(em '🎮') Docker: controller and capacity"
    ui_kv "what a server is" "one Spigot process, hosting several matches at once"
    ui_hint "the controller starts and stops those processes as demand changes"
    ask CFG_CONTROLLER_PORT "Controller port on the host" "8080" v_port
    check_port_free "$CFG_CONTROLLER_PORT" "the controller" || true
    ask CFG_ARENA_GROUP "Arena group" "solo" v_identifier
    ui_hint "matches per server: how many games run side by side in each Spigot process"
    ask CFG_GAMES_PER_SERVER "Matches per server" "25" v_int
    ui_hint "minimum servers: how many stay warm with nobody playing (0 = scale to zero)"
    ask CFG_MIN_SERVERS "Minimum servers" "0" v_int
    ui_hint "maximum servers: the ceiling the controller will never exceed"
    ask CFG_MAX_SERVERS "Maximum servers" "10" v_int
    capacity_note

    phase "$(em '🌍') Docker: world templates"
    ask_menu choice "Where do arena worlds come from?" "1" \
        "1|Bundled local template|The Glacier arena baked into the image. Nothing to configure, no object storage." \
        "2|S3-compatible storage|MinIO in the stack. Production-shaped, and needed for Slime worlds later."
    if [ "$choice" = "2" ]; then
        CFG_TEMPLATE_SOURCE="S3"
        CFG_OBJECT_STORAGE="yes"
        ask CFG_S3_BUCKET "Bucket name" "bedwars-templates" v_identifier
        ask CFG_S3_ACCESS_KEY "Access key" "minioadmin" v_nonempty
        CFG_S3_SECRET_KEY=$(random_secret 18)
        if [ "$OPT_YES" != "1" ]; then
            ask_yesno choice "Keep the default MinIO secret 'minioadmin' instead of a generated one?" "n"
            [ "$choice" = "y" ] && CFG_S3_SECRET_KEY="minioadmin" || true
        else
            CFG_S3_SECRET_KEY="minioadmin"
        fi
    else
        CFG_TEMPLATE_SOURCE="LOCAL"
        CFG_OBJECT_STORAGE="no"
    fi
    ui_ok "template source: $CFG_TEMPLATE_SOURCE"

    wizard_api_token

    #  Only a stack with the proxy can be put in offline mode.
    if [ "$CFG_STACK" = "network" ] || [ "$CFG_STACK" = "everything" ]; then
        wizard_offline_mode
    else
        ui_info "no proxy in this stack, so player authentication does not apply"
    fi
}

# ---------------------------------------------------------------------------
#  Wizard: Kubernetes
# ---------------------------------------------------------------------------
wizard_kubernetes() {
    phase "$(em '☸️') Kubernetes: cluster"
    if [ "$HAVE_CLUSTER" = "1" ]; then
        ui_ok "using the current context: $CLUSTER_CONTEXT"
        local prov="n"
        ask_yesno prov "Start a fresh local cluster anyway (minikube)?" "n"
        if [ "$prov" = "y" ]; then
            CFG_K8S_PROVISION_CLUSTER="yes"
        fi
    else
        if [ "$HAVE_MINIKUBE" = "1" ]; then
            ui_info "no cluster is reachable, but minikube can create one"
            CFG_K8S_PROVISION_CLUSTER="y"
        elif [ "$HAVE_KUBECTL" = "1" ]; then
            fail "no Kubernetes cluster is reachable" \
                 "point kubectl at one (kubectl config use-context ...) and re-run, or install minikube/kind first"
        else
            fail "the Kubernetes path needs kubectl" "install kubectl, or choose the Docker path"
        fi
    fi
    if [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
        ask CFG_MINIKUBE_CPUS "minikube CPUs" "3" v_int
        ask CFG_MINIKUBE_MEMORY "minikube memory (MB)" "4096" v_int
        ask_menu CFG_MINIKUBE_DRIVER "minikube driver" "1" \
            "1|docker|Runs the cluster in containers. Needs nothing extra." \
            "2|podman|Same idea, a different runtime." \
            "3|hyperkit / hypervisor|A VM. Best isolation, slowest to start."
        case "$CFG_MINIKUBE_DRIVER" in
            1) CFG_MINIKUBE_DRIVER="docker" ;;
            2) CFG_MINIKUBE_DRIVER="podman" ;;
            3) CFG_MINIKUBE_DRIVER="$( [ "$(uname -s)" = "Darwin" ] && echo hyperkit || echo kvm2 )" ;;
        esac
    fi

    phase "$(em '📦') Kubernetes: what to install"
    ask CFG_NAMESPACE "Namespace" "bedwars" v_dnsname
    ask CFG_RELEASE "Release name" "bedwars" v_dnsname
    if [ "$HAVE_HELM" = "1" ]; then
        ask_menu CFG_K8S_INSTALL_MODE "How should it be applied?" "1" \
            "1|Helm chart|Tracked release, values override, rollback. The maintained path." \
            "2|Plain manifests|kubectl apply -k deploy/k8s/. No Helm needed, less convenient to change."
        case "$CFG_K8S_INSTALL_MODE" in 1) CFG_K8S_INSTALL_MODE="helm" ;; 2) CFG_K8S_INSTALL_MODE="plain" ;; esac
    else
        CFG_K8S_INSTALL_MODE="plain"
        ui_info "helm is absent, so the plain manifests will be used"
    fi
    local kruise="y"
    ask_yesno kruise "Install/upgrade OpenKruise + kruise-game if they are missing?" "y"
    [ "$kruise" = "y" ] && CFG_INSTALL_OPENKRUISE="yes" || CFG_INSTALL_OPENKRUISE="no" || true
    #  Whether the CRD is already there decides both the messages and the step
    #  count, so ask the cluster rather than assuming it will be installed.
    KRUISE_PRESENT="0"
    if [ "$HAVE_CLUSTER" = "1" ] && kubectl get crd gameserversets.game.kruise.io >/dev/null 2>&1; then
        KRUISE_PRESENT="1"
        ui_ok "OpenKruise is already installed here (the GameServerSet CRD is present)"
    fi
    if [ "$HAVE_MINIKUBE" = "1" ] || [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
        local loadimg="y"
        ask_yesno loadimg "Load the built images directly into the cluster?" "y"
        if [ "$loadimg" = "y" ]; then CFG_K8S_LOAD_IMAGES="yes"; else CFG_K8S_LOAD_IMAGES="no"; fi
        ui_hint "required on minikube/kind: they cannot pull an image that only exists on this host"
    else
        CFG_K8S_LOAD_IMAGES="no"
    fi

    phase "$(em '🎮') Kubernetes: capacity and database"
    ui_kv "what a replica is" "one game pod (a Spigot process) holding several matches"
    ui_hint "the GameServerSet is the group of those pods; the controller sizes it"
    ask CFG_ARENA_GROUP "Arena group" "solo" v_identifier
    ui_hint "matches per replica: how many games run side by side in each pod"
    ask CFG_GAMES_PER_SERVER "Matches per server" "25" v_int
    ui_hint "minimum replicas: how many pods stay warm with nobody playing"
    ask CFG_MIN_SERVERS "Minimum replicas" "1" v_int
    ui_hint "maximum replicas: the ceiling the controller will never exceed"
    ask CFG_MAX_SERVERS "Maximum replicas" "10" v_int
    capacity_note
    local indb="y"
    ask_yesno indb "Run MySQL inside the cluster?" "y"
    if [ "$indb" = "y" ]; then
        CFG_USE_IN_CLUSTER_DB="yes"
        CFG_DB_NAME="bedwars"
        CFG_DB_USER="bedwars"
        CFG_DB_PASSWORD=$(random_secret 20)
        ui_ok "an in-cluster MySQL will be created (development grade -- one replica, no backups)"
    else
        CFG_USE_IN_CLUSTER_DB="no"
        ask CFG_EXT_DB_HOST "Database host" "mysql.example.com" v_nonempty
        ask CFG_EXT_DB_NAME "Database name" "bedwars" v_identifier
        ask CFG_EXT_DB_USER "Database user" "bedwars" v_identifier
        ask CFG_EXT_DB_PASSWORD "Database password" "" v_nonempty
    fi
    local choice=""
    ask_menu choice "Where do arena worlds come from?" "1" \
        "1|Bundled local template|The Glacier arena baked into the image. Simplest on a small cluster." \
        "2|MinIO in the cluster|Object storage inside the namespace, so pods fetch their world at boot."
    if [ "$choice" = "2" ]; then
        CFG_TEMPLATE_SOURCE="S3"
        CFG_OBJECT_STORAGE="yes"
        ask CFG_S3_BUCKET "Bucket name" "bedwars-templates" v_identifier
        CFG_S3_ACCESS_KEY="minioadmin"
        CFG_S3_SECRET_KEY=$(random_secret 18)
    else
        CFG_TEMPLATE_SOURCE="LOCAL"
        CFG_OBJECT_STORAGE="no"
    fi
    ui_ok "template source: $CFG_TEMPLATE_SOURCE"

    wizard_api_token
    #  The chart always deploys the proxy, so this choice always applies here.
    wizard_offline_mode
}

# ---------------------------------------------------------------------------
#  Wizard: world files and the server jar (shared by both paths)
# ---------------------------------------------------------------------------
wizard_assets() {
    phase "$(em '🗂️') World files and the server jar"

    # --- the server jar -----------------------------------------------------
    #  Recorded here and printed in the review: the image resolves its own jar,
    #  so 'compiled' is only true when it actually has to compile.
    if [ "$CFG_ENGINE" = "paper" ]; then
        CFG_SERVER_JAR_NOTE="downloaded from the PaperMC API (Paper $CFG_PAPER_VERSION)"
    else
        CFG_SERVER_JAR_NOTE="compiled from source: Spigot $CFG_SPIGOT_REV via BuildTools (slow first build)"
    fi
    local found_jar=""
    if [ -d "$ROOT/server-jars" ]; then
        found_jar=$(find "$ROOT/server-jars" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | head -n 1 || true)
    fi
    if [ -n "$found_jar" ]; then
        ui_ok "a server jar is already in server-jars/: $(basename "$found_jar") ($(du -h "$found_jar" 2>/dev/null | cut -f1))"
        ui_hint "the image copies it verbatim -- no Spigot compile, so the first build takes seconds"
        CFG_SERVER_JAR_NOTE="use server-jars/$(basename "$found_jar") -- copied verbatim, no compile"
    else
        if [ "$CFG_ENGINE" = "paper" ]; then
            ui_info "no jar supplied; Paper will be downloaded from the PaperMC API at image build (never compiled)"
        else
            ui_warn "no server jar in server-jars/"
            ui_hint "without one, the image compiles Spigot 26.3 with BuildTools: correct, and slow (minutes)"
            local have="n"
            ask_yesno have "Do you have a Spigot/Paper jar to supply?" "n"
            if [ "$have" = "y" ]; then
                ask_path CFG_SERVER_JAR_SRC "Path to the .jar" "" v_file "a file ending in .jar, e.g. ~/Downloads/spigot-26.3.jar"
                local size_mb
                size_mb=$(du -m "$CFG_SERVER_JAR_SRC" 2>/dev/null | cut -f1 || echo 0)
                if [ "$size_mb" -lt 20 ]; then
                    ui_warn "that file is only ${size_mb} MB -- a real server jar is far larger"
                    ui_hint "a plugin jar here would produce an image that cannot start"
                    local force="n"
                    ask_yesno force "Use it anyway?" "n"
                    [ "$force" = "n" ] && CFG_SERVER_JAR_SRC="" || true
                else
                    ui_ok "will copy $(basename "$CFG_SERVER_JAR_SRC") into server-jars/"
                    CFG_SERVER_JAR_NOTE="$(basename "$CFG_SERVER_JAR_SRC") -- copied into server-jars/"
                fi
            fi
        fi
    fi

    # --- the hub world (lobby) ---------------------------------------------
    local have_hub="n"
    ask_yesno have_hub "Do you have a lobby/hub world to use? (otherwise a flat world is generated)" "n"
    if [ "$have_hub" = "y" ]; then
        ask_path CFG_HUB_WORLD_SRC "Directory containing your hub world (must hold level.dat)" "" v_worlddir \
            "point at the FOLDER that contains level.dat, e.g. ~/.minecraft/saves/MyHub"
        ask CFG_HUB_WORLD_NAME "Name to stage it as" "lobby" v_identifier
        local files
        files=$(find "$CFG_HUB_WORLD_SRC" -type f 2>/dev/null | wc -l | tr -d ' ')
        local mb
        mb=$(du -sm "$CFG_HUB_WORLD_SRC" 2>/dev/null | cut -f1 || echo 0)
        ui_ok "hub world found: $files files, ${mb} MB"
        ui_hint "it will be copied to deploy/templates/$CFG_HUB_WORLD_NAME/ and staged at boot"
    else
        ui_info "no hub world: the lobby will generate a flat world"
        ui_hint "you can drop one in later at deploy/templates/lobby/ (see the README there)"
    fi

    # --- the arena world ----------------------------------------------------
    if [ "$CFG_STACK" = "game" ] || [ "$CFG_STACK" = "everything" ] || [ "$CFG_MODE" = "kubernetes" ]; then
        local arena_baked="Glacier"
        if [ -d "$ROOT/deploy/templates/$arena_baked" ]; then
            local baked_files
            baked_files=$(find "$ROOT/deploy/templates/$arena_baked" -type f 2>/dev/null | wc -l | tr -d ' ')
            ui_ok "arena template bundled: $arena_baked ($baked_files files)"
        fi
        local have_arena="n"
        ask_yesno have_arena "Replace the bundled arena with your own world?" "n"
        if [ "$have_arena" = "y" ]; then
            ask_path CFG_ARENA_WORLD_SRC "Directory containing your arena world (must hold level.dat)" "" v_worlddir \
                "point at the FOLDER that contains level.dat"
            ui_warn "this replaces the bundled arena for every new image build"
            local confirm="y"
            ask_yesno confirm "Confirm: replace the bundled arena?" "n"
            [ "$confirm" = "n" ] && CFG_ARENA_WORLD_SRC="" || true
        fi
    fi
}

# ---------------------------------------------------------------------------
#  Review -- everything chosen, in one screen, before anything happens
# ---------------------------------------------------------------------------
review_summary() {
    ui_section "$(em '🧾') Review"
    local token_shown="none (open)"
    [ -n "$CFG_API_TOKEN" ] && token_shown="set (${#CFG_API_TOKEN} chars, written to the config)" || true
    if [ "$CFG_MODE" = "docker" ]; then
        ui_kv "Mode" "Docker Compose"
        ui_kv "Services" "$CFG_STACK"
        ui_kv "Game engine" "$CFG_ENGINE"
        ui_kv "Controller port" "$CFG_CONTROLLER_PORT"
        ui_kv "Database" "$CFG_DB_NAME / user $CFG_DB_USER / port $CFG_DB_PORT"
        ui_kv "Database password" "$( [ "$CFG_DB_PASSWORD" = "bedwars" ] && echo "the default (bedwars)" || echo "generated (in .env)" )"
        ui_kv "Arena group" "$CFG_ARENA_GROUP"
        ui_kv "Matches per server" "$CFG_GAMES_PER_SERVER"
        ui_kv "Servers (min/max)" "$CFG_MIN_SERVERS / $CFG_MAX_SERVERS"
        ui_kv "Capacity" "$(( CFG_MAX_SERVERS * CFG_GAMES_PER_SERVER )) matches at once"
        ui_kv "Template source" "$CFG_TEMPLATE_SOURCE"
        ui_kv "Object storage" "$CFG_OBJECT_STORAGE"
    else
        ui_kv "Mode" "Kubernetes"
        ui_kv "Cluster" "$( [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ] && echo "start minikube (${CFG_MINIKUBE_CPUS} cpu / ${CFG_MINIKUBE_MEMORY} MB / $CFG_MINIKUBE_DRIVER)" || echo "$CLUSTER_CONTEXT" )"
        ui_kv "Namespace" "$CFG_NAMESPACE"
        ui_kv "Install method" "$CFG_K8S_INSTALL_MODE"
        ui_kv "Release" "$CFG_RELEASE"
        ui_kv "OpenKruise" "$( [ "$CFG_INSTALL_OPENKRUISE" = "yes" ] && echo "install if missing" || echo "do not touch" )"
        ui_kv "Load images into cluster" "$CFG_K8S_LOAD_IMAGES"
        ui_kv "Arena group" "$CFG_ARENA_GROUP"
        ui_kv "Matches per server" "$CFG_GAMES_PER_SERVER"
        ui_kv "Replicas (min/max)" "$CFG_MIN_SERVERS / $CFG_MAX_SERVERS"
        ui_kv "Capacity" "$(( CFG_MAX_SERVERS * CFG_GAMES_PER_SERVER )) matches at once"
        ui_kv "Database" "$( [ "$CFG_USE_IN_CLUSTER_DB" = "yes" ] && echo "in-cluster MySQL" || echo "$CFG_EXT_DB_HOST / $CFG_EXT_DB_NAME" )"
        ui_kv "Template source" "$CFG_TEMPLATE_SOURCE"
    fi
    ui_kv "Hub world" "$( [ -n "$CFG_HUB_WORLD_SRC" ] && echo "$CFG_HUB_WORLD_SRC -> deploy/templates/$CFG_HUB_WORLD_NAME" || echo "generated flat world" )"
    ui_kv "Arena world" "$( [ -n "$CFG_ARENA_WORLD_SRC" ] && echo "$CFG_ARENA_WORLD_SRC (replaces the bundled arena)" || echo "bundled Glacier" )"
    ui_kv "Server jar" "$CFG_SERVER_JAR_NOTE"
    ui_kv "Controller API token" "$token_shown"
    ui_kv "Proxy authentication" "$( [ "$CFG_OFFLINE_MODE" = "yes" ] && echo "OFFLINE MODE -- testing only" || echo "online (checked with Mojang)" )"
    if [ "$CFG_OFFLINE_MODE" = "yes" ]; then
        ui_blank
        ui_warn "offline mode: players will NOT be verified with Mojang"
        ui_hint "it is a testing switch -- and the game servers behind the proxy always run offline"
    fi
}

review_and_confirm() {
    while :; do
        review_summary
        if [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
            ui_info "proceeding (unattended)"
            return 0
        fi
        local answer=""
        printf '\n  %s?%s %s[c]ontinue, [e]dit, [a]bort%s ' "$C_ACCENT" "$C_OFF" "$C_MUTED" "$C_OFF"
        read -r answer || true
        case "$answer" in
            e|E|edit)
                if [ "$CFG_MODE" = "docker" ]; then wizard_docker; else wizard_kubernetes; fi
                wizard_assets
                ;;
            a|A|abort)
                ui_warn "aborted before changing anything"
                exit 0 ;;
            *) return 0 ;;
        esac
    done
}

# ---------------------------------------------------------------------------
#  Streaming runner -- for long jobs (image builds) where a spinner is a lie.
#  Shows elapsed time and the most recent meaningful line of real output.
# ---------------------------------------------------------------------------
run_streaming() {
    local label="$1"; shift
    if [ "$OPT_DRY_RUN" = "1" ]; then
        printf '  %s[dry-run]%s %s\n' "$C_MUTED" "$C_OFF" "$*"
        log_line "DRY-RUN: $*"
        return 0
    fi
    local out="$STATE_DIR/stream.log"
    : >"$out"
    log_line "RUN(stream): $*"
    LAST_FAILURE="$out"
    "$@" >>"$out" 2>&1 &
    local pid=$!
    local start now elapsed last=""
    start=$(date +%s)
    while kill -0 "$pid" 2>/dev/null; do
        now=$(date +%s)
        elapsed=$(( now - start ))
        # The last line that actually says something about progress.
        last=$(grep -E '^#[0-9]|DONE|CACHED|Building|Pulling|Download|Compil|Installing' "$out" 2>/dev/null | tail -n 1 | cut -c1-58 || true)
        if [ "$UI_INTERACTIVE" = "1" ]; then
            printf '\r  %s%s%s %s%4ds%s %s%s\033[K' "$C_ACCENT" "$label" "$C_OFF" "$C_MUTED" "$elapsed" "$C_OFF" "$last" "$C_OFF"
        elif [ $(( elapsed % 15 )) -eq 0 ] && [ "$elapsed" -gt 0 ]; then
            printf '  %s (%ss) %s\n' "$label" "$elapsed" "$last"
        fi
        sleep 1
    done
    local rc=0
    wait "$pid" || rc=$?
    if [ "$UI_INTERACTIVE" = "1" ]; then printf '\r\033[K'; fi
    if [ "$rc" -ne 0 ]; then
        log_line "FAILED ($rc): $*"
        return "$rc"
    fi
    log_line "OK: $*"
    return 0
}

# ---------------------------------------------------------------------------
#  Writing configuration
# ---------------------------------------------------------------------------
write_env_file() {
    step_begin "Writing deploy/compose/.env"
    local env_file="$ROOT/deploy/compose/.env"
    local tmp="$STATE_DIR/.env.new"
    {
        printf '# Generated by install.sh %s on %s.\n' "$INSTALLER_VERSION" "$(date '+%Y-%m-%d %H:%M:%S')"
        printf '# Re-running the installer rewrites this file; edit it by hand if you prefer.\n\n'
        printf 'BEDWARS_DB_NAME=%s\n' "$CFG_DB_NAME"
        printf 'BEDWARS_DB_USER=%s\n' "$CFG_DB_USER"
        printf 'BEDWARS_DB_PASSWORD=%s\n' "$CFG_DB_PASSWORD"
        printf 'BEDWARS_DB_ROOT_PASSWORD=%s\n' "$CFG_DB_ROOT_PASSWORD"
        printf 'BEDWARS_DB_PORT=%s\n' "$CFG_DB_PORT"
        printf '\n'
        printf 'BEDWARS_S3_ACCESS_KEY=%s\n' "$CFG_S3_ACCESS_KEY"
        printf 'BEDWARS_S3_SECRET_KEY=%s\n' "$CFG_S3_SECRET_KEY"
        printf 'BEDWARS_S3_BUCKET=%s\n' "$CFG_S3_BUCKET"
        if [ -n "$CFG_MINIO_IMAGE" ]; then
            printf '# Mirror used because the default registry refused the pull\n'
            printf 'BEDWARS_MINIO_IMAGE=%s\n' "$CFG_MINIO_IMAGE"
            printf 'BEDWARS_MC_IMAGE=%s\n' "$CFG_MC_IMAGE"
        fi
        printf '\n'
        printf 'SERVER_ENGINE=%s\n' "$CFG_ENGINE"
        printf 'SPIGOT_REV=%s\n' "$CFG_SPIGOT_REV"
        printf 'PAPER_VERSION=%s\n' "$CFG_PAPER_VERSION"
        printf '\n'
        #  Shared secret for the controller's write endpoints. Written only when one was
        #  generated: absent means the controller runs open, which it logs on start.
        if [ -n "$CFG_API_TOKEN" ]; then
            printf 'BEDWARS_API_TOKEN=%s\n' "$CFG_API_TOKEN"
        else
            printf '# BEDWARS_API_TOKEN is not set: the controller accepts writes openly\n'
        fi
        printf 'BEDWARS_OFFLINE_MODE=%s\n' "$( [ "$CFG_OFFLINE_MODE" = "yes" ] && echo true || echo false )"
    } >"$tmp"

    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_hint "[dry-run] would write $env_file"
        while IFS= read -r _l; do
            case "$_l" in
                '#'*|'') continue ;;
                #  A dry run must not put the secret on screen or in the log.
                BEDWARS_API_TOKEN=*) ui_hint "  BEDWARS_API_TOKEN=<generated, not shown>" ;;
                *) ui_hint "  $_l" ;;
            esac
        done <"$tmp"
        return 0
    fi
    mv "$tmp" "$env_file"
    chmod 600 "$env_file" 2>/dev/null || true
    ui_ok "wrote $env_file (mode 600 -- it holds passwords)"
    log_line "wrote $env_file"
}

write_helm_values() {
    step_begin "Writing the Helm values override"
    local values_file="$ROOT/deploy/helm/values-install.yaml"
    local tmp="$STATE_DIR/values.new"
    local gss_name="bedwars-$CFG_ARENA_GROUP"
    local suffix=".$gss_name.$CFG_NAMESPACE.svc.cluster.local"
    #  A local cluster is usually a single small node. The chart's default game-pod
    #  footprint (2 CPU / 4 GiB) starves it - observed for real, the API server went
    #  unreachable - so use the small footprint the repository documents for minikube
    #  and start at zero replicas.
    local local_cluster="0"
    case "$CLUSTER_CONTEXT" in
        minikube|minikube-*|kind-*|docker-desktop|docker-desktop-*) local_cluster="1" ;;
    esac
    {
        printf '# Generated by install.sh %s on %s.\n' "$INSTALLER_VERSION" "$(date '+%Y-%m-%d %H:%M:%S')"
        printf '# Use it with:\n'
        printf '#   helm upgrade --install %s deploy/helm/bedwars -n %s --create-namespace -f deploy/helm/values-install.yaml\n\n' "$CFG_RELEASE" "$CFG_NAMESPACE"
        printf 'image:\n  tag: "1.0.0"\n\n'
        printf 'arena:\n'
        printf '  group: %s\n' "$CFG_ARENA_GROUP"
        printf '  templateSource: %s\n' "$CFG_TEMPLATE_SOURCE"
        printf '\nlobby:\n'
        printf '  enabled: true\n'
        printf '  world: %s\n' "$CFG_HUB_WORLD_NAME"
        printf '  templateSource: LOCAL\n'
        printf '\ngameServerSet:\n'
        printf '  enabled: true\n'
        printf '  name: %s\n' "$gss_name"
        if [ "$local_cluster" = "1" ]; then
            printf '  # Local cluster: keep the documented small footprint and start idle.\n'
            printf '  # The chart default (2 CPU / 4 GiB per pod) starves a single-node cluster.\n'
            printf '  replicas: 0\n'
            printf '  cpuRequest: 500m\n'
            printf '  memoryRequest: 512Mi\n'
            printf '  cpuLimit: "1"\n'
            printf '  memoryLimit: 1536Mi\n'
            printf '  jvmOptions: "-XX:MaxRAMPercentage=55 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"\n'
        else
            printf '  replicas: %s\n' "$CFG_MIN_SERVERS"
        fi
        #  A bare cluster has no Prometheus Operator and no KEDA. The chart renders
        #  those objects unconditionally when enabled, and helm fails on an unknown
        #  kind, so the values file has to match what the cluster can accept.
        printf '\nkeda:\n'
        if [ "$HAVE_KEDA_CRD" = "1" ]; then
            printf '  enabled: true\n'
            printf '  minReplicas: %s\n' "$CFG_MIN_SERVERS"
            printf '  maxReplicas: %s\n' "$CFG_MAX_SERVERS"
        else
            printf '  enabled: false\n'
        fi
        printf '\nserviceMonitor:\n'
        if [ "$HAVE_SM_CRD" = "1" ]; then
            printf '  enabled: true\n'
        else
            printf '  enabled: false\n'
        fi
        printf '\ncontroller:\n'
        if [ -n "$CFG_API_TOKEN" ]; then
            printf '  # Shared secret for the controller write endpoints. The chart creates the\n'
            printf '  # Secret and hands it to the controller, the proxy and every game pod.\n'
            printf '  apiToken: %s\n' "$CFG_API_TOKEN"
        else
            printf '  # No token: the controller accepts writes from anything that can reach it.\n'
            printf '  apiToken: ""\n'
        fi
        printf '\nvelocity:\n'
        printf '  lobbyServer: lobby\n'
        printf '  # Offline mode is a testing switch: no Mojang check, any name may join.\n'
        printf '  offlineMode: %s\n' "$( [ "$CFG_OFFLINE_MODE" = "yes" ] && echo true || echo false )"
        printf '  # Must match the GameServerSet name above and the namespace, or the proxy\n'
        printf '  # resolves a host that does not exist and every queue strands the player.\n'
        printf '  podAddressSuffix: "%s"\n' "$suffix"
        printf '\n'
        if [ "$CFG_USE_IN_CLUSTER_DB" = "yes" ]; then
            printf 'mysql:\n'
            printf '  enabled: true\n'
            printf '  database: %s\n' "$CFG_DB_NAME"
            printf '  username: %s\n' "$CFG_DB_USER"
            printf '  password: %s\n' "$CFG_DB_PASSWORD"
        else
            printf 'mysql:\n  enabled: false\n'
        fi
        printf '\n'
        if [ "$CFG_OBJECT_STORAGE" = "yes" ]; then
            printf 'minio:\n  enabled: true\n\n'
            printf 's3:\n'
            printf '  bucket: %s\n' "$CFG_S3_BUCKET"
            printf '  accessKey: %s\n' "$CFG_S3_ACCESS_KEY"
            printf '  secretKey: %s\n' "$CFG_S3_SECRET_KEY"
        else
            printf 'minio:\n  enabled: false\n'
        fi
    } >"$tmp"

    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_hint "[dry-run] would write $values_file"
        return 0
    fi
    mv "$tmp" "$values_file"
    ui_ok "wrote $values_file"
    log_line "wrote $values_file (gss=$gss_name suffix=$suffix local_cluster=$local_cluster)"
    if [ "$local_cluster" = "1" ]; then
        ui_info "local cluster detected: game pods start at 0 replicas with a small footprint"
        ui_hint "the chart default (2 CPU / 4 GiB) starves a single-node cluster -- seen for real"
        ui_hint "start a match host when you want one: kubectl -n $CFG_NAMESPACE scale gameserversets $gss_name --replicas=1"
    fi
}

# ---------------------------------------------------------------------------
#  Staging assets: the server jar and the worlds
# ---------------------------------------------------------------------------
stage_assets() {
    step_begin "Copying world files and the server jar"

    if [ -n "$CFG_SERVER_JAR_SRC" ] && [ -f "$CFG_SERVER_JAR_SRC" ]; then
        if [ "$OPT_DRY_RUN" = "1" ]; then
            ui_hint "[dry-run] copy $CFG_SERVER_JAR_SRC -> server-jars/"
        else
            mkdir -p "$ROOT/server-jars"
            cp -f "$CFG_SERVER_JAR_SRC" "$ROOT/server-jars/$(basename "$CFG_SERVER_JAR_SRC")"
            ui_ok "server jar staged: $(basename "$CFG_SERVER_JAR_SRC")"
        fi
    fi

    if [ -n "$CFG_HUB_WORLD_SRC" ] && [ -d "$CFG_HUB_WORLD_SRC" ]; then
        local dest="$ROOT/deploy/templates/$CFG_HUB_WORLD_NAME"
        ui_info "copying the hub world into deploy/templates/$CFG_HUB_WORLD_NAME/"
        # Replace, but never a path we did not build: the destination is always
        # inside deploy/templates, and must have a level.dat to qualify.
        if [ "$OPT_DRY_RUN" != "1" ]; then
            case "$dest" in
                "$ROOT"/deploy/templates/*) : ;;
                *) fail "refusing to stage a world outside deploy/templates" "$dest" ;;
            esac
            if [ -d "$dest" ]; then
                rm -rf "$dest"
            fi
        fi
        copy_tree_progress "$CFG_HUB_WORLD_SRC" "$dest" "hub world"
        ui_ok "hub world staged at deploy/templates/$CFG_HUB_WORLD_NAME/"
    fi

    if [ -n "$CFG_ARENA_WORLD_SRC" ] && [ -d "$CFG_ARENA_WORLD_SRC" ]; then
        local adest="$ROOT/deploy/templates/Glacier"
        ui_warn "replacing the bundled arena with your world"
        if [ "$OPT_DRY_RUN" != "1" ]; then
            case "$adest" in
                "$ROOT"/deploy/templates/*) : ;;
                *) fail "refusing to stage a world outside deploy/templates" "$adest" ;;
            esac
            rm -rf "$adest"
        fi
        copy_tree_progress "$CFG_ARENA_WORLD_SRC" "$adest" "arena world"
        ui_ok "arena replaced at deploy/templates/Glacier/"
    fi

    if [ -z "$CFG_HUB_WORLD_SRC" ] && [ -z "$CFG_SERVER_JAR_SRC" ]; then
        ui_info "nothing to copy -- the images bring their own world and resolve their own server jar"
    fi
}

# ---------------------------------------------------------------------------
#  Docker execution
# ---------------------------------------------------------------------------
COMPOSE="docker compose -f deploy/compose/docker-compose.yml"

compose_profiles() {
    local flags=""
    case "$CFG_STACK" in
        game) flags="--profile game" ;;
        network) flags="--profile network" ;;
        everything) flags="--profile game --profile network" ;;
    esac
    printf '%s' "$flags"
}

compose_services() {
    local svcs="mysql controller"
    [ "$CFG_OBJECT_STORAGE" = "yes" ] && svcs="$svcs minio minio-init" || true
    case "$CFG_STACK" in
        game) svcs="$svcs game-pod" ;;
        network) svcs="$svcs lobby velocity" ;;
        everything) svcs="$svcs lobby velocity game-pod" ;;
    esac
    printf '%s' "$svcs"
}

docker_object_storage_pull() {
    [ "$CFG_OBJECT_STORAGE" = "yes" ] || return 0
    step_begin "Object storage images"
    ui_info "pulling the MinIO images (only needed for the S3 template path)"
    local rc=0
    run_cmd_soft "pulling minio images" docker pull quay.io/minio/minio:latest || rc=$?
    if [ "$rc" -ne 0 ]; then
        # A registry that refuses anonymous pulls is common on locked-down
        # networks. The repository documents a mirror; offer it rather than
        # letting the user discover a 401 half an hour later.
        ui_warn "the default registry refused the pull"
        ui_hint "the repository documents Bitnami mirrors for exactly this case"
        local use_mirror="y"
        ask_yesno use_mirror "Use the Bitnami mirrors instead?" "y"
        if [ "$use_mirror" = "y" ]; then
            CFG_MINIO_IMAGE="bitnamilegacy/minio:latest"
            CFG_MC_IMAGE="bitnamilegacy/minio-client:latest"
            run_cmd "pulling the mirrors" docker pull "$CFG_MINIO_IMAGE" || \
                fail "could not pull $CFG_MINIO_IMAGE either" \
                     "check network/registry access, or set BEDWARS_MINIO_IMAGE in deploy/compose/.env to a mirror you can reach"
            run_cmd "pulling the client mirror" docker pull "$CFG_MC_IMAGE" || true
            ui_ok "mirrors pulled; they are recorded in .env"
        else
            ui_warn "continuing without a verified MinIO image -- the object-storage services may fail"
        fi
    else
        ui_ok "MinIO images present"
    fi
}

docker_build() {
    step_begin "Building the images"
    ui_info "first build is the slow one; later builds reuse every layer"
    local svcs="controller"
    case "$CFG_STACK" in
        game|everything) svcs="$svcs game-pod" ;;
    esac
    case "$CFG_STACK" in
        network|everything) svcs="$svcs lobby velocity" ;;
    esac
    local profiles=""
    profiles=$(compose_profiles)
    # shellcheck disable=SC2086
    if ! run_streaming "building images" $COMPOSE $profiles build $svcs; then
        fail "image build failed" "the full build output is in $LOG_FILE"
    fi
    ui_ok "images built"
}

docker_start() {
    step_begin "Starting the stack"
    local profiles svcs
    profiles=$(compose_profiles)
    svcs=$(compose_services)
    # shellcheck disable=SC2086
    if ! run_cmd "starting containers" $COMPOSE $profiles up -d $svcs; then
        fail "docker compose up failed" "check 'deploy/compose/.env' and the log"
    fi
    ui_ok "containers requested: $svcs"
}

docker_wait() {
    step_begin "Waiting for the stack to become ready"
    # MySQL first: everything else waits on it, and it is slow on a cold volume.
    if ! wait_for "MySQL is healthy" 180 bash -c "$COMPOSE ps mysql --format '{{.Health}}' 2>/dev/null | grep -q healthy"; then
        ui_warn "MySQL did not report healthy in 180s -- continuing, the controller may still come up"
    else
        ui_ok "MySQL healthy"
    fi
    if ! wait_for "controller answering /healthz" 120 http_ok "http://localhost:$CFG_CONTROLLER_PORT/healthz"; then
        ui_warn "the controller is not answering yet"
        ui_hint "docker compose -f deploy/compose/docker-compose.yml logs controller"
    else
        ui_ok "controller is serving on port $CFG_CONTROLLER_PORT"
    fi
    case "$CFG_STACK" in
        network|everything)
            if wait_for "the lobby is up (players can land)" 240 bash -c "$COMPOSE logs lobby 2>/dev/null >$STATE_DIR/lobby.log; grep -q 'lobby_ready' $STATE_DIR/lobby.log"; then
                ui_ok "lobby is ready"
            else
                ui_warn "the lobby has not logged lobby_ready yet -- a first boot builds its world"
            fi
            if wait_for "the proxy is up" 180 bash -c "$COMPOSE logs velocity 2>/dev/null >$STATE_DIR/velocity.log; grep -q 'proxy initialised' $STATE_DIR/velocity.log"; then
                ui_ok "proxy is ready"
            else
                ui_warn "the proxy has not finished starting"
            fi
            ;;
    esac
    case "$CFG_STACK" in
        game|everything)
            if wait_for "a game pod registered with the controller" 300 bash -c "curl -s http://localhost:$CFG_CONTROLLER_PORT/infra | grep -q '\"registeredServers\":[1-9]'"; then
                ui_ok "a game server registered itself with the controller"
            else
                ui_warn "no game server has registered yet"
                ui_hint "a pod needs ~30-60s to boot Spigot and report in; check: $COMPOSE logs game-pod"
            fi
            ;;
    esac
}

docker_verify() {
    step_begin "Proving what was built"
    local infra
    infra=$(curl -s --max-time 5 "http://localhost:$CFG_CONTROLLER_PORT/infra" 2>/dev/null || true)
    if [ -z "$infra" ]; then
        ui_warn "could not read /infra -- skipping the checks"
        return 0
    fi
    local registered provisioner
    registered=$(printf '%s' "$infra" | tr ',' '\n' | grep -o '"registeredServers":[0-9]*' | cut -d: -f2 || true)
    provisioner=$(printf '%s' "$infra" | tr ',' '\n' | grep -o '"provisioner":"[A-Z]*"' | cut -d'"' -f4 || true)
    ui_kv "controller provisioner" "$provisioner"
    ui_kv "registered game servers" "$registered"
    if [ "$provisioner" = "DOCKER" ]; then
        ui_ok "the controller is using the Docker provisioner (the classic misconfiguration is KUBERNETES here)"
    else
        ui_warn "the controller reports provisioner '$provisioner' -- on a single host this should be DOCKER"
    fi
    #  The token is the one setting that breaks the whole control plane silently: with
    #  it set on the controller and not on the clients, every report is refused with 401
    #  and the fleet simply looks empty. Assert the state instead of assuming it.
    local authed
    authed=$(printf '%s' "$infra" | tr ',' '\n' | grep -o '"authenticated":[a-z]*' | cut -d: -f2 || true)
    if [ -n "$authed" ]; then
        if [ -z "$CFG_API_TOKEN" ] && [ "$authed" = "false" ]; then
            ui_ok "controller authentication: open, as configured (no token)"
        elif [ -n "$CFG_API_TOKEN" ] && [ "$authed" = "true" ]; then
            ui_ok "controller authentication: the generated token is required"
        else
            ui_warn "the controller reports authenticated=$authed but the config says otherwise"
            ui_hint "a mismatch means a client cannot report: see chapter 21 of docs/MANUAL.md"
        fi
    fi
    case "$CFG_STACK" in
        network|everything)
            #  The proxy's own view: the entrypoint logs the effective online-mode, so the
            #  requested mode can be checked rather than trusted.
            if [ -f "$STATE_DIR/velocity.log" ]; then
                if file_has "$STATE_DIR/velocity.log" "online-mode=false"; then
                    if [ "$CFG_OFFLINE_MODE" = "yes" ]; then
                        ui_ok "the proxy is in OFFLINE MODE, as requested (no Mojang check)"
                    else
                        ui_warn "the proxy is in offline mode but online mode was configured"
                    fi
                elif file_has "$STATE_DIR/velocity.log" "online-mode=true"; then
                    if [ "$CFG_OFFLINE_MODE" = "yes" ]; then
                        ui_warn "offline mode was requested but the proxy reports online-mode=true"
                    else
                        ui_ok "the proxy verifies players with Mojang (online mode)"
                    fi
                fi
            fi
            #  The assertion that matters: a hub must never appear as a match host.
            if [ "$registered" = "0" ]; then
                ui_ok "no server is registered as a match host yet -- correct: the lobby is a hub, not a game server"
            else
                ui_ok "game servers registered: $registered"
            fi
            if [ -f "$STATE_DIR/lobby.log" ] && file_has "$STATE_DIR/lobby.log" "role=LOBBY"; then
                ui_ok "the lobby booted in LOBBY role"
            fi
            if [ -f "$STATE_DIR/velocity.log" ]; then
                if file_has "$STATE_DIR/velocity.log" "not registered"; then
                    ui_warn "the proxy does not see its lobby server -- check the lobby Service and velocity.toml"
                else
                    ui_ok "the proxy found its lobby server"
                fi
            fi
            ;;
    esac
}

# ---------------------------------------------------------------------------
#  Kubernetes execution
# ---------------------------------------------------------------------------
KUBECTL_NS=""

k8s_establish_cluster() {
    if [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
        step_begin "Starting a local cluster"
        if ! command -v minikube >/dev/null 2>&1; then
            fail "minikube is not installed" "install minikube, or point kubectl at an existing cluster"
        fi
        local state
        state=$(minikube status -o json 2>/dev/null | grep -o '"Host":"[A-Za-z]*"' | cut -d'"' -f4 || true)
        if [ "$state" = "Running" ]; then
            ui_ok "minikube is already running"
        else
            if ! run_streaming "starting minikube" minikube start --driver="$CFG_MINIKUBE_DRIVER" \
                    --cpus="$CFG_MINIKUBE_CPUS" --memory="$CFG_MINIKUBE_MEMORY"; then
                ui_warn "the first start failed -- a half-initialised cluster is common"
                ui_hint "retrying once from a clean slate"
                run_cmd "deleting the bad cluster" minikube delete || true
                run_streaming "starting minikube (retry)" minikube start --driver="$CFG_MINIKUBE_DRIVER" \
                    --cpus="$CFG_MINIKUBE_CPUS" --memory="$CFG_MINIKUBE_MEMORY" || \
                    fail "minikube would not start" "run 'minikube start' by hand to see the full error"
            fi
            if [ "$OPT_DRY_RUN" = "1" ]; then
                ui_hint "[dry-run] would start minikube now"
            else
                ui_ok "minikube started"
            fi
        fi
        HAVE_CLUSTER="1"
        CLUSTER_CONTEXT=$(kubectl config current-context 2>/dev/null || echo minikube)
    fi
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_hint "[dry-run] skipping the cluster reachability check"
        return 0
    fi
    #  A cluster that has just been started is not instantly answerable, so poll
    #  rather than testing once and declaring failure.
    if ! wait_for "the cluster API to answer" 150 kubectl cluster-info; then
        fail "kubectl cannot reach a cluster" "run 'kubectl cluster-info' to see why, then re-run"
    fi
    CLUSTER_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "$CLUSTER_CONTEXT")
}

k8s_namespace() {
    step_begin "Namespace"
    KUBECTL_NS="$CFG_NAMESPACE"
    #  Two optional CRDs decide whether the chart's metrics/scaling objects can be
    #  rendered at all. A bare cluster has neither, and helm fails hard rather than
    #  skipping them, so ask the cluster instead of assuming.
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_hint "[dry-run] skipping the optional-CRD probe (Prometheus Operator, KEDA)"
    else
        if kubectl get crd servicemonitors.monitoring.coreos.com >/dev/null 2>&1; then
            HAVE_SM_CRD="1"
        fi
        if kubectl get crd scaledobjects.keda.sh >/dev/null 2>&1; then
            HAVE_KEDA_CRD="1"
        fi
        if [ "$HAVE_SM_CRD" = "0" ]; then
            ui_info "no Prometheus Operator on this cluster: the ServiceMonitor will be disabled"
            ui_hint "it needs the monitoring.coreos.com CRDs; install the operator to enable it"
        fi
        if [ "$HAVE_KEDA_CRD" = "0" ]; then
            ui_info "no KEDA on this cluster: the ScaledObject will be disabled"
            ui_hint "it needs the keda.sh CRDs; the game servers then scale by hand"
        fi
    fi
    if [ "$CFG_K8S_INSTALL_MODE" = "plain" ]; then
        # The plain manifests carry their own namespace and DNS suffix; pointing
        # them somewhere else would need edits, so be explicit instead of silently
        # deploying to the wrong place.
        if [ "$KUBECTL_NS" != "bedwars" ]; then
            ui_warn "the plain manifests are written for the 'bedwars' namespace"
            ui_hint "using 'bedwars'; choose the Helm method if you need another namespace"
            KUBECTL_NS="bedwars"
            CFG_NAMESPACE="bedwars"
        fi
        if [ "$CFG_ARENA_GROUP" != "solo" ]; then
            ui_warn "the plain manifests host the 'solo' arena group"
            ui_hint "using 'solo'; choose the Helm method to run another group"
            CFG_ARENA_GROUP="solo"
        fi
    fi
    if run_capture kubectl get namespace "$KUBECTL_NS"; then
        ui_ok "namespace $KUBECTL_NS already exists"
    else
        run_cmd "creating namespace $KUBECTL_NS" kubectl create namespace "$KUBECTL_NS" || \
            fail "could not create namespace $KUBECTL_NS"
        ui_ok "namespace $KUBECTL_NS created"
    fi
}

k8s_openkruise() {
    local have_crd="no"
    if kubectl get crd gameserversets.game.kruise.io >/dev/null 2>&1; then
        have_crd="yes"
    fi
    if [ "$have_crd" = "yes" ]; then
        ui_ok "OpenKruise GameServerSet CRD is present"
        return 0
    fi
    if [ "$CFG_INSTALL_OPENKRUISE" != "yes" ]; then
        ui_warn "the GameServerSet CRD is missing and you asked not to install it"
        ui_hint "the game pods cannot exist without it; installing the persistent tier only"
        return 1
    fi
    step_begin "Installing OpenKruise and kruise-game"
    ui_info "the GameServerSet CRD comes from kruise-game; without it, no game pod can exist"
    if command -v helm >/dev/null 2>&1; then
        run_cmd "adding the OpenKruise chart repo" helm repo add openkruise https://openkruise.io/charts || true
        run_cmd "updating chart repos" helm repo update || true
        if ! run_cmd "installing kruise" helm upgrade --install kruise openkruise/kruise \
                --namespace kruise-system --create-namespace --wait --timeout 5m; then
            fail "could not install OpenKruise" "see chapter 8.1 of docs/MANUAL.md for the manual steps"
        fi
        if ! run_cmd "installing kruise-game" helm upgrade --install kruise-game openkruise/kruise-game \
                --namespace kruise-system --wait --timeout 5m; then
            fail "could not install kruise-game" "the GameServerSet CRD will be missing without it"
        fi
    else
        run_cmd "installing kruise (manifests)" kubectl apply -f \
            "https://github.com/openkruise/kruise/releases/latest/download/kruise-all-in-one.yaml" || \
            fail "could not install OpenKruise" "install helm, or follow chapter 8.1 manually"
    fi
    # The publish of the CRD is asynchronous; wait for the name we actually use.
    if wait_for "the GameServerSet CRD to appear" 180 kubectl get crd gameserversets.game.kruise.io; then
        ui_ok "GameServerSet CRD ready"
    else
        fail "the GameServerSet CRD never appeared" \
             "check 'kubectl get crd | grep kruise'; a wrong API group here is the classic failure"
    fi
    return 0
}

k8s_build_images() {
    step_begin "Building and publishing the images"
    local profiles
    profiles=$(compose_profiles)
    if ! run_streaming "building images" docker compose -f "$ROOT/deploy/compose/docker-compose.yml" build controller lobby velocity; then
        fail "image build failed" "the full output is in $LOG_FILE"
    fi
    ui_ok "images built"
}

k8s_load_images() {
    [ "$CFG_K8S_LOAD_IMAGES" = "yes" ] || return 0
    step_begin "Loading images into the cluster"
    ui_info "a local cluster cannot pull an image that only exists on this host"
    local loader="" img
    if command -v minikube >/dev/null 2>&1 && [ "$CLUSTER_CONTEXT" = "minikube" ]; then
        loader="minikube"
    elif command -v kind >/dev/null 2>&1 && printf '%s' "$CLUSTER_CONTEXT" | grep -q kind; then
        loader="kind"
    fi
    for img in bedwars-spigot:1.0.0 bedwars-controller:1.0.0 bedwars-velocity:1.0.0; do
        if ! docker image inspect "$img" >/dev/null 2>&1; then
            ui_warn "$img is not built locally -- skipping"
            continue
        fi
        case "$loader" in
            minikube)
                run_cmd "loading $img" minikube image load "$img" || ui_warn "could not load $img"
                # 'minikube image load' exits 0 even when it loaded nothing, so
                # verify the presence rather than trusting the exit code.
                if minikube image ls 2>/dev/null | grep -q "$img"; then
                    ui_ok "$img is in the cluster"
                else
                    ui_warn "$img does not appear in 'minikube image ls'"
                fi
                ;;
            kind)
                run_cmd "loading $img" kind load docker-image "$img" || ui_warn "could not load $img"
                ui_ok "$img loaded"
                ;;
            *)
                ui_info "no known local-cluster loader; the cluster must pull $img itself"
                ;;
        esac
    done
}

k8s_deploy() {
    step_begin "Deploying"
    if [ "$CFG_K8S_INSTALL_MODE" = "helm" ]; then
        if ! run_streaming "helm upgrade --install $CFG_RELEASE" helm upgrade --install "$CFG_RELEASE" \
                "$ROOT/deploy/helm/bedwars" --namespace "$KUBECTL_NS" --create-namespace \
                -f "$ROOT/deploy/helm/values-install.yaml"; then
            fail "helm install failed" "run 'helm template' by hand to see the rendered manifests"
        fi
        ui_ok "release $CFG_RELEASE applied"
        return 0
    fi
    # Plain manifests, applied one file at a time in order, so the CRD-before-use
    # ordering is explicit and a missing OpenKruise is caught here rather than
    # showing up as 'no game pods appeared'.
    if ! kubectl get crd gameserversets.game.kruise.io >/dev/null 2>&1; then
        ui_warn "OpenKruise is missing: applying only the persistent tier"
        ui_hint "the GameServerSet manifest is skipped; install OpenKruise and re-apply it later"
    fi
    local files="00-namespace.yaml 50-s3-config.yaml 51-mysql.yaml 13-networkpolicy.yaml 14-pdb.yaml 20-mc-router.yaml 25-lobby.yaml 30-velocity.yaml 40-controller.yaml"
    [ "$CFG_OBJECT_STORAGE" = "yes" ] && files="$files 52-minio.yaml" || true
    #  Only apply what the cluster has CRDs for; an unknown kind is a hard apply
    #  error. The GameServerSet comes before the ScaledObject that targets it.
    if kubectl get crd gameserversets.game.kruise.io >/dev/null 2>&1; then
        files="$files 10-gameserverset.yaml"
    fi
    if [ "$HAVE_SM_CRD" = "1" ]; then
        files="$files 12-servicemonitor.yaml"
    fi
    if [ "$HAVE_KEDA_CRD" = "1" ]; then
        files="$files 11-keda-scaledobject.yaml"
    fi
    #  The plain path has no chart to render the Secret, and the workloads reference it
    #  as optional. Create it BEFORE they start, so the pods find it on their first
    #  attempt instead of needing a rollout. The value is piped in, never an argument:
    #  an argument is readable by any other process on the machine (ps).
    if [ -n "$CFG_API_TOKEN" ]; then
        ui_info "creating the controller's shared-secret Secret"
        if [ "$OPT_DRY_RUN" = "1" ]; then
            ui_hint "[dry-run] would create secret bedwars-controller-token in $KUBECTL_NS"
        else
            if ! printf '%s' "$CFG_API_TOKEN" | kubectl -n "$KUBECTL_NS" create secret generic \
                    bedwars-controller-token --from-file=token=/dev/stdin --dry-run=client -o yaml \
                    | kubectl apply -f - >/dev/null 2>&1; then
                fail "could not create the controller token Secret" \
                     "check that you may write Secrets in namespace $KUBECTL_NS"
            fi
            ui_ok "secret bedwars-controller-token created (value piped, never on the command line)"
        fi
    fi
    local f
    for f in $files; do
        if ! run_cmd "applying $f" kubectl apply -n "$KUBECTL_NS" -f "$ROOT/deploy/k8s/$f"; then
            fail "kubectl apply failed for $f" "the file is deploy/k8s/$f"
        fi
    done
    ui_ok "manifests applied"
}

k8s_wait() {
    step_begin "Waiting for the workloads"
    if ! wait_for "the controller Deployment is available" 240 \
            kubectl -n "$KUBECTL_NS" wait --for=condition=available deploy/bedwars-controller --timeout=20s; then
        ui_warn "the controller Deployment did not become available"
        ui_hint "kubectl -n $KUBECTL_NS describe deploy/bedwars-controller"
    else
        ui_ok "controller available"
    fi
    if ! wait_for "the lobby Deployment is available" 300 \
            kubectl -n "$KUBECTL_NS" wait --for=condition=available deploy/lobby --timeout=20s; then
        ui_warn "the lobby Deployment did not become available"
        ui_hint "kubectl -n $KUBECTL_NS logs deploy/lobby  (a first boot generates its world, which takes a minute)"
    else
        ui_ok "lobby available"
    fi
    if ! wait_for "the velocity Deployment is available" 180 \
            kubectl -n "$KUBECTL_NS" wait --for=condition=available deploy/velocity --timeout=20s; then
        ui_warn "the velocity Deployment did not become available"
    else
        ui_ok "velocity available"
    fi
}

k8s_verify() {
    step_begin "Proving what was built"
    # Reach the controller without leaving a port-forward running: one short-lived
    # forward, then kill it.
    local pf_pid="" infra=""
    if [ "$OPT_DRY_RUN" != "1" ]; then
        kubectl -n "$KUBECTL_NS" port-forward svc/bedwars-controller 18080:8080 >/dev/null 2>&1 &
        pf_pid=$!
        if wait_for "controller port-forward" 60 http_ok "http://localhost:18080/healthz"; then
            infra=$(curl -s --max-time 5 "http://localhost:18080/infra" 2>/dev/null || true)
        fi
        kill "$pf_pid" 2>/dev/null || true
        wait "$pf_pid" 2>/dev/null || true
    fi
    if [ -z "$infra" ]; then
        ui_warn "could not reach /infra through a port-forward -- skipping the deep checks"
    else
        local registered provisioner
        registered=$(printf '%s' "$infra" | tr ',' '\n' | grep -o '"registeredServers":[0-9]*' | cut -d: -f2 || true)
        provisioner=$(printf '%s' "$infra" | tr ',' '\n' | grep -o '"provisioner":"[A-Z]*"' | cut -d'"' -f4 || true)
        ui_kv "controller provisioner" "$provisioner"
        ui_kv "registered game servers" "${registered:-0}"
        if [ "$provisioner" = "KUBERNETES" ]; then
            ui_ok "the controller is using the Kubernetes provisioner (correct on a cluster)"
        fi
    fi
    if [ "$OPT_DRY_RUN" != "1" ]; then
        #  A first boot unpacks hundreds of libraries and generates a world before the
        #  plugin says anything, so sample the log once and a healthy lobby reads as
        #  broken. Poll for the line instead, with a generous window.
        if wait_for "the lobby to log its role" 240 bash -c "kubectl -n $KUBECTL_NS logs deploy/lobby --tail=10000 2>/dev/null >$STATE_DIR/lobby-k8s.log; grep -q -F 'role=LOBBY' $STATE_DIR/lobby-k8s.log"; then
            ui_ok "the lobby booted in LOBBY role"
        else
            ui_warn "the lobby has not logged its role yet"
            ui_hint "its boot log is long: world generation and library unpacking come first"
            ui_hint "kubectl -n $KUBECTL_NS logs deploy/lobby | grep -E 'bedwars_setup|lobby_ready'"
        fi
        if kubectl -n "$KUBECTL_NS" get deploy lobby >/dev/null 2>&1; then
            ui_ok "the lobby Deployment exists in $KUBECTL_NS"
        fi
    fi
}

# ---------------------------------------------------------------------------
#  The finishing screen: what is running, how to use it, how to undo it
# ---------------------------------------------------------------------------
#  The preflight results, printed a second time in the summary: this list is what a bug
#  report needs, and it is the answer to "did the installer actually check anything?".
print_env_report() {
    local mark label detail
    printf '%s' "$ENV_ROWS" | while IFS='|' read -r mark label detail; do
        [ -z "$label" ] && continue
        case "$mark" in
            ok)   printf '  %s✔%s %-14s %s\n' "$C_OK" "$C_OFF" "$label" "$detail" ;;
            warn) printf '  %s▲%s %-14s %s\n' "$C_WARN" "$C_OFF" "$label" "$detail" ;;
            err)  printf '  %s✖%s %-14s %s\n' "$C_ERR" "$C_OFF" "$label" "$detail" ;;
            *)    printf '  %s•%s %-14s %s\n' "$C_ACCENT" "$C_OFF" "$label" "$detail" ;;
        esac
    done
    return 0
}

print_summary() {
    ui_section "$(em '🎉') Done"
    if [ "$CFG_MODE" = "docker" ]; then
        ui_ok "the stack is up"
        ui_blank
        ui_kv "Controller" "http://localhost:$CFG_CONTROLLER_PORT   (/healthz, /infra, /metrics)"
        case "$CFG_STACK" in
            infra) ui_kv "Player entry point" "none -- this stack has no Minecraft server" ;;
            game) ui_kv "Game server" "localhost:25567 (debug only; the controller places players)" ;;
            network|everything) ui_kv "Player entry point" "localhost:25565  <- point a Minecraft client here" ;;
        esac
        if [ "$CFG_STACK" = "network" ] || [ "$CFG_STACK" = "everything" ]; then
            ui_blank
            ui_info "what a player sees: connect to localhost:25565, land in the lobby, right-click a sign"
            ui_hint "to place one: join as an operator, place a sign, set its FIRST line to [bedwars]"
            ui_hint "the hub refuses block placement for normal players on purpose; staff hold bedwars.lobby.build"
        fi
        ui_blank
        ui_kv "Config written" "deploy/compose/.env"
        ui_kv "Log" "$LOG_FILE"
        ui_blank
        ui_info "check it yourself:"
        ui_hint "curl -s http://localhost:$CFG_CONTROLLER_PORT/infra"
        ui_hint "docker compose -f deploy/compose/docker-compose.yml $(compose_profiles) logs -f   # follow everything"
    else
        ui_ok "the release is applied"
        ui_blank
        ui_kv "Namespace" "$CFG_NAMESPACE"
        ui_kv "Release" "$CFG_RELEASE"
        [ "$CFG_K8S_INSTALL_MODE" = "helm" ] && ui_kv "Values" "deploy/helm/values-install.yaml" || true
        ui_blank
        ui_info "reach the controller:"
        ui_hint "kubectl -n $CFG_NAMESPACE port-forward svc/bedwars-controller 8080:8080"
        ui_hint "curl -s localhost:8080/infra"
        ui_blank
        ui_info "watch a match get provisioned:"
        ui_hint "kubectl -n $CFG_NAMESPACE get gameserversets,pods -w"
        ui_hint "kubectl -n $CFG_NAMESPACE logs deploy/lobby | grep -E 'bedwars_setup|lobby_ready'"
        ui_blank
        ui_kv "Log" "$LOG_FILE"
    fi

    ui_blank
    ui_section "$(em '🧪') This machine, verified"
    print_env_report

    ui_blank
    ui_hint "tear it down with: $SCRIPT_PATH --teardown"

    if [ -z "$CFG_API_TOKEN" ]; then
        ui_blank
        ui_warn "the controller's write endpoints are open (no API token)"
        ui_hint "that is the documented development mode, and fine on a private host"
        ui_hint "before exposing port $CFG_CONTROLLER_PORT: see chapter 21 of docs/MANUAL.md"
    else
        ui_blank
        ui_ok "the controller requires a generated token; the pods and the proxy were given it"
        ui_hint "it lives in the config file the installer wrote (mode 600), never in the log"
    fi

    if [ "$CFG_OFFLINE_MODE" = "yes" ]; then
        ui_blank
        ui_warn "the proxy is in OFFLINE MODE: no Mojang check, any name can join"
        ui_hint "testing only. Undo: set BEDWARS_OFFLINE_MODE=false in deploy/compose/.env"
        ui_hint "(or velocity.offlineMode: false in the Helm values) and restart the proxy"
    fi
}

# ---------------------------------------------------------------------------
#  Teardown
# ---------------------------------------------------------------------------
teardown_docker() {
    step_begin "Removing the Docker stack"
    local with_volumes="n"
    if [ "$OPT_YES" != "1" ] && [ "$UI_INTERACTIVE" = "1" ]; then
        ask_yesno with_volumes "Also delete the database and object-storage volumes? (data is lost)" "n"
    fi
    local flags="--profile game --profile network"
    # shellcheck disable=SC2086
    if [ "$with_volumes" = "y" ]; then
        run_cmd "stopping and removing (with volumes)" docker compose -f "$ROOT/deploy/compose/docker-compose.yml" $flags down -v --remove-orphans || true
    else
        run_cmd "stopping and removing" docker compose -f "$ROOT/deploy/compose/docker-compose.yml" $flags down --remove-orphans || true
    fi
    # Containers the controller provisioned are not compose services; they carry
    # the label the provisioner filters on, so they are the safe set to remove.
    local leftovers
    leftovers=$(docker ps -aq --filter "label=bedwars.provisioned=true" 2>/dev/null || true)
    if [ -n "$leftovers" ]; then
        ui_info "removing containers the controller provisioned"
        # shellcheck disable=SC2086
        run_cmd "removing provisioned containers" docker rm -f $leftovers || true
    fi
    if docker network inspect bedwars_bedwars >/dev/null 2>&1; then
        run_cmd "removing the network" docker network rm bedwars_bedwars || true
    fi
    ui_ok "Docker stack removed"
}

teardown_kubernetes() {
    step_begin "Removing the Kubernetes release"
    local ns="$CFG_NAMESPACE"
    if [ "$CFG_K8S_INSTALL_MODE" = "helm" ]; then
        if helm status "$CFG_RELEASE" -n "$ns" >/dev/null 2>&1; then
            run_cmd "helm uninstall $CFG_RELEASE" helm uninstall "$CFG_RELEASE" -n "$ns" || true
            ui_ok "release $CFG_RELEASE removed"
        else
            ui_warn "no helm release named $CFG_RELEASE in $ns"
        fi
    else
        run_cmd "deleting the namespace $ns" kubectl delete namespace "$ns" --ignore-not-found || true
        ui_ok "namespace $ns removed"
    fi
    local purge="n"
    if [ "$OPT_YES" != "1" ] && [ "$UI_INTERACTIVE" = "1" ]; then
        ask_yesno purge "Also delete the namespace (removes MySQL/MinIO data)?" "n"
    fi
    if [ "$purge" = "y" ]; then
        run_cmd "deleting namespace $ns" kubectl delete namespace "$ns" --ignore-not-found || true
        ui_ok "namespace $ns deleted"
    fi
}

run_teardown() {
    ui_banner
    ui_section "Teardown"
    local mode="$CFG_MODE"
    if [ -z "$mode" ] && [ -f "$STATE_FILE" ]; then
        load_state
        mode="$CFG_MODE"
    fi
    if [ -z "$mode" ]; then
        if [ "$HAVE_DOCKER" = "1" ] && [ "$HAVE_CLUSTER" != "1" ]; then
            mode="docker"
        elif [ "$HAVE_CLUSTER" = "1" ]; then
            mode="kubernetes"
        else
            mode="docker"
        fi
    fi
    ui_info "removing the $mode stack"
    step_total 1
    if [ "$mode" = "docker" ]; then
        teardown_docker
    else
        teardown_kubernetes
    fi
    ui_blank
    ui_ok "done. Nothing else on this machine was touched."
}

# ---------------------------------------------------------------------------
#  Plan -- the numbered steps are only for the part that does real work, so the
#  numbers mean something.
# ---------------------------------------------------------------------------
plan_execution() {
    local n=0
    if [ "$CFG_MODE" = "docker" ]; then
        #  env, stage, build, start, wait, verify
        n=6
        if [ "$CFG_OBJECT_STORAGE" = "yes" ]; then
            n=$(( n + 1 ))
        fi
    else
        #  namespace, build, deploy, wait, verify
        n=5
        if [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
            n=$(( n + 1 ))                       # starting the cluster
        fi
        if [ "$KRUISE_PRESENT" = "0" ] && [ "$CFG_INSTALL_OPENKRUISE" = "yes" ]; then
            n=$(( n + 1 ))                       # installing OpenKruise
        fi
        if [ "$CFG_K8S_LOAD_IMAGES" = "yes" ]; then
            n=$(( n + 1 ))                       # loading the images
        fi
        if [ "$CFG_K8S_INSTALL_MODE" = "helm" ]; then
            n=$(( n + 1 ))                       # writing the values override
        fi
    fi
    step_total "$n"
}

# ---------------------------------------------------------------------------
#  Main
# ---------------------------------------------------------------------------
main() {
    parse_args "$@"
    ui_init
    locate_root
    cd "$ROOT" || fail "could not enter $ROOT"
    STATE_DIR="$ROOT/.installer"
    LOG_FILE="$STATE_DIR/install.log"
    STATE_FILE="$STATE_DIR/state"

    if [ "$OPT_DRY_RUN" != "1" ]; then
        mkdir -p "$STATE_DIR"
        : >"$LOG_FILE"
        log_line "=== $INSTALLER_NAME $INSTALLER_VERSION started ==="
        log_line "args: $*"
    else
        STATE_DIR="${TMPDIR:-/tmp}/bedwars-installer-dryrun"
        mkdir -p "$STATE_DIR"
        LOG_FILE="$STATE_DIR/install.log"
        STATE_FILE="$STATE_DIR/state"
        : >"$LOG_FILE"
        log_line "=== dry run ==="
    fi
    trap 'on_err $? $LINENO "$BASH_COMMAND"' ERR
    trap 'on_signal' INT TERM
    trap 'cleanup_ui' EXIT

    if [ "$OPT_TEARDOWN" = "1" ]; then
        probe_tools >/dev/null 2>&1 || true
        run_teardown
        return 0
    fi

    if [ "$OPT_RESUME" = "1" ]; then
        load_state
    fi

    ui_banner
    printf '  %sThis will take a fresh checkout to a running network.%s\n' "$C_MUTED" "$C_OFF"
    printf '  %sEvery step is reversible, and nothing is deleted that this script did not create.%s\n' "$C_MUTED" "$C_OFF"
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_blank
        ui_warn "DRY RUN: nothing will be written, built or started"
    fi

    phase "$(em '🔎') Checking this machine"
    probe_tools
    check_disk

    wizard_mode
    if [ "$CFG_MODE" = "docker" ]; then
        if [ "$HAVE_DOCKER" != "1" ] || [ "$HAVE_DOCKER_COMPOSE" != "1" ]; then
            fail "the Docker path needs a running Docker daemon with the compose v2 plugin" \
                 "start Docker (or install it), then re-run -- or choose the Kubernetes path"
        fi
        wizard_docker
    else
        if [ "$HAVE_KUBECTL" != "1" ]; then
            fail "the Kubernetes path needs kubectl" "install kubectl, or choose the Docker path"
        fi
        wizard_kubernetes
    fi
    wizard_assets
    review_and_confirm

    if [ "$OPT_DRY_RUN" != "1" ]; then
        save_state
    fi

    plan_execution
    if [ "$CFG_MODE" = "docker" ]; then
        write_env_file
        stage_assets
        docker_object_storage_pull
        docker_build
        docker_start
        docker_wait
        docker_verify
    else
        k8s_establish_cluster
        k8s_namespace
        k8s_openkruise || true
        k8s_build_images
        k8s_load_images
        if [ "$CFG_K8S_INSTALL_MODE" = "helm" ]; then
            write_helm_values
        fi
        k8s_deploy
        k8s_wait
        k8s_verify
    fi
    save_state
    print_summary
    return 0
}

main "$@"
