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
#  "" = ask (the default), "yes" = install without asking, "no" = never install.
OPT_INSTALL_DEPS=""

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
CFG_K8S_CLUSTER_TOOL=""     # minikube | kind: which local cluster tool to create with
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
    total=$(find "$src" -type f 2>/dev/null | wc -l | tr -d ' ' || echo 0)
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
        s=$(openssl rand -base64 $(( n + 8 )) 2>/dev/null | tr -dc 'A-Za-z0-9' | cut -c1-"$n" || true)
    fi
    if [ -z "$s" ]; then
        s=$( (date +%s; printf '%s' "$RANDOM$RANDOM$RANDOM") | cksum | tr -dc '0-9' | cut -c1-"$n" || true)
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
  --install-deps              Install missing dependencies with the system package
                              manager without asking. Without this flag the installer
                              asks; with --yes it does NOT install (installing system
                              packages unattended is too invasive) and prints the
                              commands instead.
  --no-install-deps           Never install anything: report what is missing, print
                              the commands for your distribution, and stop.
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
  1. Checks the machine (tools, daemon, cluster, ports, disk) and, if something is
     missing, offers to install it with your distribution's package manager
     (apt, dnf, pacman, zypper or Homebrew) -- or prints the exact commands to run.
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
            --install-deps) OPT_INSTALL_DEPS="yes"; shift ;;
            --no-install-deps) OPT_INSTALL_DEPS="no"; shift ;;
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
HAVE_KIND="0"
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
#  Platform facts, decided once by detect_platform. They drive both the offer to
#  install missing dependencies and the manual instructions printed when declined.
OS_FAMILY="unknown"      # debian | fedora | arch | suse | macos | unknown
DOCKER_BIN=""            # the docker that `command -v` resolves
DOCKER_SHIM="0"          # 1 when that docker is the Windows one WSL exposes
DOCKER_STATE=""          # absent | shim | ok | permission | not_running | unknown
DOCKER_ERR=""            # the first line docker itself said, when the answer was not usable
PKG_MGR=""               # apt-get | dnf | pacman | zypper | brew | (empty)
SUDO=""                  # "sudo" unless already root
SUDO_N=""                # "sudo -n": for use inside helpers that redirect output
IS_ROOT="0"
IS_WSL="0"
BIN_DIR="/usr/local/bin" # where a downloaded binary (kubectl, helm, minikube) goes
BIN_SUDO=""
PKG_LISTS_UPDATED="0"
INSTALLED_NOW=""
FAILED_INSTALL=""

probe_tools() {
    # Re-runnable: ensure_dependencies probes again after installing something, and
    # the report must not accumulate a second copy of every row.
    ENV_ROWS=""
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
    probe_docker_daemon
    case "$DOCKER_STATE" in
        absent)
            record_env warn "docker" "not found -- the images are built with it on both paths"
            ;;
        shim)
            record_env warn "docker" "the Windows docker ($DOCKER_BIN) is not usable inside WSL"
            ui_hint "enable WSL integration in Docker Desktop, or install docker in this distro:"
            ui_hint "  sudo apt-get install -y docker.io && sudo service docker start"
            ui_hint "then /usr/bin/docker has to come before /mnt/... on PATH for it to be used"
            ;;
        permission)
            #  The daemon is up. Say so, and name the actual problem: the socket's group.
            record_env warn "docker" "the daemon is running, but this user cannot open its socket"
            if docker_reachable_only_with_sudo; then
                ui_hint "confirmed: docker works with sudo here, so only your group membership is missing"
            fi
            ui_hint "join the docker group, then apply it to this shell:"
            ui_hint "  $SUDO usermod -aG docker \${USER} && newgrp docker"
            ui_hint "a fresh login works too; then re-run this installer"
            ;;
        not_running)
            record_env warn "docker" "installed, but no daemon is listening"
            if [ "$OS_FAMILY" = "macos" ]; then
                ui_hint "start Docker Desktop, then re-run this installer"
            else
                ui_hint "start it:  $SUDO systemctl start docker   (or:  $SUDO service docker start)"
            fi
            ;;
        ok)
            HAVE_DOCKER="1"
            record_env ok "docker" "$DOCKER_VERSION, daemon reachable"
            if docker compose version >/dev/null 2>&1; then
                HAVE_DOCKER_COMPOSE="1"
                record_env ok "docker compose" "$(docker compose version --short 2>/dev/null | head -1 || echo v2)"
            else
                record_env warn "docker compose" "the v2 'docker compose' plugin is missing; the Docker path needs it"
            fi
            ;;
        *)
            #  Unrecognised answer: print what docker actually said, so the next person has
            #  the error in front of them instead of a guess.
            record_env warn "docker" "installed, but it did not answer usefully: ${DOCKER_ERR:-no output}"
            ui_hint "run 'docker info' yourself to see the full error"
            ;;
    esac

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
    if command -v kind >/dev/null 2>&1; then
        HAVE_KIND="1"
        record_env info "kind" "$(kind version 2>/dev/null | head -1 || echo present) -- a local cluster can be created for you"
    fi
    if command -v helm >/dev/null 2>&1; then
        HAVE_HELM="1"
        record_env ok "helm" "$(helm version --short 2>/dev/null || echo present)"
    else
        record_env info "helm" "absent -- the BedWars manifests do not need it, OpenKruise does"
    fi

    # --- tooling the repository uses to build and test itself ----------------
    #  None of this is needed to RUN the network: the images carry their own JDK and
    #  build the plugin inside the build stage. It is needed to build the plugin here,
    #  or to run the repository's own test suite, so it is reported, never required.
    if command -v java >/dev/null 2>&1; then
        JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/' || true)
        JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION" | cut -d. -f1)
        case "$JAVA_MAJOR" in ''|*[!0-9]*) JAVA_MAJOR="" ;; esac
        if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 25 ]; then
            record_env ok "java" "$JAVA_VERSION -- can build the plugin on this machine"
        elif [ -n "$JAVA_VERSION" ]; then
            record_env info "java" "$JAVA_VERSION -- the plugin is built with Java 25"
        else
            # `java` on the PATH that will not answer is usually a broken or empty JDK
            # home, and saying "present" would hide exactly the problem worth fixing.
            record_env warn "java" "installed, but it did not report a version -- check the JDK"
        fi
    else
        record_env info "java" "absent -- not needed to run the network (the images carry a JDK)"
    fi
    if command -v mvn >/dev/null 2>&1; then
        # `mvn -v` fails outright without a JDK, and a bare assignment takes that
        # failure's status: under `set -e` one broken tool killed the whole run.
        MAVEN_VERSION=$(mvn -v 2>/dev/null | head -1 | awk '{print $3}' || true)
        if [ -n "$MAVEN_VERSION" ]; then
            record_env ok "maven" "$MAVEN_VERSION -- can build the plugin and run its tests"
        else
            # Maven needs a JDK to run at all, so this is the common "mvn installed, no
            # java" state: report it rather than claiming it can build anything.
            record_env warn "maven" "installed, but it failed to start -- maven needs a JDK"
        fi
    else
        record_env info "maven" "absent -- only needed to build the plugin on this machine"
    fi
    if command -v git >/dev/null 2>&1; then
        GIT_VERSION=$(git --version 2>/dev/null | awk '{print $3}' || true)
        record_env info "git" "${GIT_VERSION:-present}"
    fi
    if command -v python3 >/dev/null 2>&1; then
        PYTHON_VERSION=$(python3 --version 2>/dev/null | awk '{print $2}' || true)
        record_env info "python3" "${PYTHON_VERSION:-present} -- used by the helper scripts"
    fi

    # Deliberately quiet about a missing tool here: whether it is fatal depends on
    # the path the user picks, and ensure_dependencies offers to install it.
    if [ -n "$OS_FAMILY" ] && [ "$OS_FAMILY" != "unknown" ]; then
        record_env info "platform" "$(platform_label)$( [ "$IS_WSL" = "1" ] && printf ' -- on WSL' || true )"
    fi
    return 0
}

check_disk() {
    # Images plus MySQL data are a few gigabytes; warn early rather than halfway.
    local avail_kb
    avail_kb=$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2 {print $4}' || true)
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
#  Dependencies: what is missing, and installing it
# ---------------------------------------------------------------------------
#  The installer is the front door, so it must not stop at "docker is missing"
#  and leave the user to work out the rest. It detects the platform, offers to
#  install what is missing with that platform's own package manager, and when the
#  answer is no (or there is no package manager) prints the exact commands for
#  that platform before exiting.
detect_platform() {
    OS_FAMILY="unknown"
    PKG_MGR=""
    IS_WSL="0"
    case "$(uname -s 2>/dev/null || echo unknown)" in
        Darwin)
            OS_FAMILY="macos"
            if command -v brew >/dev/null 2>&1; then PKG_MGR="brew"; fi
            ;;
        Linux)
            # WSL reports Linux, but the host is Windows and that changes the advice
            # for the Docker daemon (Docker Desktop integration vs an in-distro one).
            if [ -n "${WSL_DISTRO_NAME:-}" ]; then
                IS_WSL="1"
            elif [ -r /proc/version ] && grep -qi microsoft /proc/version 2>/dev/null; then
                IS_WSL="1"
            fi
            if command -v apt-get >/dev/null 2>&1; then
                OS_FAMILY="debian"; PKG_MGR="apt-get"
            elif command -v dnf >/dev/null 2>&1; then
                OS_FAMILY="fedora"; PKG_MGR="dnf"
            elif command -v pacman >/dev/null 2>&1; then
                OS_FAMILY="arch"; PKG_MGR="pacman"
            elif command -v zypper >/dev/null 2>&1; then
                OS_FAMILY="suse"; PKG_MGR="zypper"
            fi
            ;;
    esac

    if [ "$(id -u 2>/dev/null || echo 1)" = "0" ]; then
        IS_ROOT="1"; SUDO=""
    else
        IS_ROOT="0"
        if command -v sudo >/dev/null 2>&1; then SUDO="sudo"; else SUDO=""; fi
    fi
    #  Anything run through run_cmd/run_streaming has its output redirected to a log, so a
    #  sudo prompt issued from there is invisible and sudo then waits out its five-minute
    #  password timeout. Those call sites use the -n form; credentials are settled up front
    #  instead (sudo_ready), which puts the prompt on the terminal where it belongs.
    if [ -n "$SUDO" ]; then SUDO_N="$SUDO -n"; else SUDO_N=""; fi

    # A downloaded binary needs a home. Prefer /usr/local/bin, but never require
    # root: fall back to ~/.local/bin, which is where a user can write.
    if [ "$IS_ROOT" = "1" ]; then
        BIN_DIR="/usr/local/bin"; BIN_SUDO=""
    elif [ -n "$SUDO" ]; then
        #  /usr/local/bin is the preferred home; whether sudo can actually get us there is
        #  settled at install time, with a working prompt or a user-writable fallback.
        BIN_DIR="/usr/local/bin"; BIN_SUDO="$SUDO_N"
    else
        BIN_DIR="$HOME/.local/bin"; BIN_SUDO=""
    fi
}

platform_label() {
    case "$OS_FAMILY" in
        debian) printf 'Debian / Ubuntu (apt)' ;;
        fedora) printf 'Fedora / RHEL (dnf)' ;;
        arch)   printf 'Arch (pacman)' ;;
        suse)   printf 'openSUSE (zypper)' ;;
        macos)  if [ -n "$PKG_MGR" ]; then printf 'macOS (Homebrew)'; else printf 'macOS (no Homebrew)'; fi ;;
        *)      printf 'unrecognised platform' ;;
    esac
}

#  Is this tool usable right now? Presence is enough for the build-only tools: a
#  java older than 25 cannot build the plugin but does not stop the network, and
#  installing a package would not fix the version anyway.
tool_ok() {
    case "$1" in
        curl)    [ "$HAVE_CURL" = "1" ] ;;
        docker)  [ "$HAVE_DOCKER" = "1" ] ;;
        compose) [ "$HAVE_DOCKER_COMPOSE" = "1" ] ;;
        kubectl) [ "$HAVE_KUBECTL" = "1" ] ;;
        java)    command -v java >/dev/null 2>&1 ;;
        maven)   command -v mvn >/dev/null 2>&1 ;;
        *)       return 1 ;;
    esac
}

#  Is this a version, or a paragraph? A binary that is not docker prints prose and still
#  exits 0, so the answer has to look like a version -- but a real daemon may answer with a
#  suffixed build ("20.10.24+dfsg1" is what Debian's own package reports), so the shape is
#  "starts with a digit, then only characters a version can contain".
looks_like_version() {
    case "$1" in
        v[0-9]*|[0-9]*) ;;
        *) return 1 ;;
    esac
    case "$1" in
        *[!0-9A-Za-z.+_-]*) return 1 ;;
    esac
    return 0
}

#  What the docker on PATH can actually reach, and why not when it cannot.
#
#  Sets DOCKER_STATE to one of: absent | shim | ok | permission | not_running | unknown,
#  plus DOCKER_BIN / DOCKER_VERSION / DOCKER_ERR.
#
#  The distinction is the whole point. "The daemon is not answering" is wrong -- and sends
#  people to restart a service that is already running -- when the daemon is up and this
#  user is simply not in the docker group yet, which is the state of every fresh Ubuntu
#  before a re-login. A user with a screenshot of `systemctl status docker: active
#  (running)` reported exactly that.
probe_docker_daemon() {
    DOCKER_STATE="absent"; DOCKER_VERSION=""; DOCKER_ERR=""; DOCKER_SHIM="0"
    command -v docker >/dev/null 2>&1 || return 0
    DOCKER_BIN=$(command -v docker)
    case "$DOCKER_BIN" in
        # WSL PATH interop puts the Windows docker.exe on the PATH: it cannot reach a Linux
        # daemon at all, so starting one is pointless advice.
        /mnt/*|*.exe) DOCKER_SHIM="1"; DOCKER_STATE="shim"; return 0 ;;
    esac
    local raw="" first="" cleaned=""
    #  Ask the daemon for its version. No pipeline here on purpose: under `set -o pipefail`
    #  a `printf | head -1` can fail with SIGPIPE and abort the whole run (this installer
    #  has been killed by exactly that class of bug before), so the first line is taken with
    #  parameter expansion instead.
    raw=$(docker info --format '{{.ServerVersion}}' 2>&1) || true
    first="${raw%%$'\n'*}"
    cleaned="${first//[[:space:]]/}"
    case "$raw" in
        #  Permission first: a refused socket also says "dial unix ...", so testing for the
        #  transport before the cause would misreport the commonest case on a fresh host.
        *"permission denied"*) DOCKER_STATE="permission" ;;
        *"Cannot connect to the Docker daemon"*|*"Is the docker daemon running"*|\
        *"connection refused"*|*"no such file or directory"*|*"connect: connection"*)
            DOCKER_STATE="not_running" ;;
        *)
            if looks_like_version "$cleaned"; then
                DOCKER_STATE="ok"
                DOCKER_VERSION="$cleaned"
            else
                DOCKER_STATE="unknown"
                DOCKER_ERR="$first"
            fi
            ;;
    esac
    return 0
}

#  True only when the daemon answers. `wait_for` polls a command's exit status, and the
#  probe itself always returns 0 (it reports by setting DOCKER_STATE), so this is the
#  predicate to poll.
docker_daemon_ok() {
    probe_docker_daemon
    [ "$DOCKER_STATE" = "ok" ]
}

#  Can this user reach the daemon with sudo but not without it? Then the daemon is fine and
#  only the group membership is missing -- which makes the diagnosis certain rather than
#  likely. sudo -n never prompts: if a password is needed it simply fails.
docker_reachable_only_with_sudo() {
    [ -n "$SUDO" ] || return 1
    [ "$DOCKER_STATE" = "permission" ] || return 1
    $SUDO -n docker info >/dev/null 2>&1
}

#  A docker this machine can actually use: present, and not the Windows one that WSL
#  PATH interop exposes. Starting a daemon is pointless for a binary that cannot reach
#  a Linux one.
docker_binary_usable() {
    command -v docker >/dev/null 2>&1 || return 1
    [ "$DOCKER_SHIM" = "1" ] && return 1
    return 0
}

tool_label() {
    case "$1" in
        curl)    printf 'curl' ;;
        docker)  printf 'docker' ;;
        compose) printf 'docker compose (the v2 plugin)' ;;
        kubectl) printf 'kubectl' ;;
        helm)    printf 'helm' ;;
        minikube) printf 'minikube' ;;
        kind)    printf 'kind' ;;
        java)    printf 'java' ;;
        maven)   printf 'maven' ;;
        *)       printf '%s' "$1" ;;
    esac
}

tool_role() {
    case "$1" in
        java|maven) printf 'only needed to build the plugin on this machine' ;;
        helm)       printf 'needed to install OpenKruise (upstream publishes no kubectl manifest)' ;;
        *)          printf 'required for this path' ;;
    esac
}

#  Package names for the current platform, one per line, most preferred first.
#  Empty output means no package manager here ships it: use a binary download.
pkg_candidates() {
    case "$1" in
        curl)
            case "$OS_FAMILY" in
                macos) if command -v curl >/dev/null 2>&1; then :; else printf 'curl\n'; fi ;;
                *)     printf 'curl\n' ;;
            esac
            ;;
        docker)
            case "$OS_FAMILY" in
                debian) printf 'docker.io\n' ;;
                fedora) printf 'moby-engine\n' ;;
                arch)   printf 'docker\n' ;;
                suse)   printf 'docker\n' ;;
                macos)  printf 'docker\n' ;;   # a cask; install_docker_macos handles it
            esac
            ;;
        compose)
            case "$OS_FAMILY" in
                # Ubuntu 24.04+ ships the v2 plugin under this name. Older releases
                # only have the v1 'docker-compose' package, which the installer
                # cannot use, hence the fallback to the plugin binary below.
                debian) printf 'docker-compose-v2\ndocker-compose-plugin\n' ;;
                fedora) printf 'docker-compose\n' ;;
                arch)   printf 'docker-compose\n' ;;
                suse)   printf 'docker-compose\n' ;;
                macos)  printf 'docker-compose\n' ;;
            esac
            ;;
        kubectl)
            case "$OS_FAMILY" in
                # Only these two carry it in their own repositories.
                fedora) printf 'kubernetes-client\n' ;;
                arch)   printf 'kubectl\n' ;;
                macos)  printf 'kubectl\n' ;;
            esac
            ;;
        helm)
            case "$OS_FAMILY" in
                arch)  printf 'helm\n' ;;
                macos) printf 'helm\n' ;;
                *)     printf '' ;;
            esac
            ;;
        kind)
            #  No distribution packages kind; it is a single static binary, so Linux
            #  takes the release download and macOS takes the formula.
            case "$OS_FAMILY" in
                macos) printf 'kind\n' ;;
                *)     printf '' ;;
            esac
            ;;
        java)
            case "$OS_FAMILY" in
                # 25 first, then whatever the distribution has. A 21 install is still
                # useful for everything except compiling this plugin.
                debian) printf 'openjdk-25-jdk\nopenjdk-24-jdk\nopenjdk-21-jdk\n' ;;
                fedora) printf 'java-25-openjdk-devel\njava-latest-openjdk-devel\n' ;;
                arch)   printf 'jdk-openjdk\n' ;;
                suse)   printf 'java-21-openjdk-devel\n' ;;
                macos)  printf 'openjdk\n' ;;
            esac
            ;;
        maven)
            case "$OS_FAMILY" in
                macos) printf 'maven\n' ;;
                *)     printf 'maven\n' ;;
            esac
            ;;
    esac
}

#  One package-manager invocation for the platform.
install_packages() {
    # install_packages <label> <package...>
    local label="$1"; shift
    case "$PKG_MGR" in
        #  All four need root, and a prompt cannot be answered from inside run_streaming:
        #  settle the credentials first and visibly, then run non-interactively so a missing
        #  credential fails immediately instead of after a five-minute silence.
        apt-get|dnf|pacman|zypper)
            if [ "$IS_ROOT" != "1" ]; then
                sudo_ready || return 1
            fi
            ;;
    esac
    case "$PKG_MGR" in
        apt-get)
            if [ "$PKG_LISTS_UPDATED" != "1" ]; then
                run_streaming "updating the package lists" $SUDO_N apt-get update || return 1
                PKG_LISTS_UPDATED="1"
            fi
            run_streaming "$label" $SUDO_N apt-get install -y "$@"
            ;;
        dnf)    run_streaming "$label" $SUDO_N dnf install -y "$@" ;;
        pacman) run_streaming "$label" $SUDO_N pacman -S --noconfirm --needed "$@" ;;
        zypper) run_streaming "$label" $SUDO_N zypper --non-interactive install "$@" ;;
        brew)   run_streaming "$label" brew install "$@" ;;
        *)      return 1 ;;
    esac
}

#  Try each candidate package in order, since the same name is not blessed by every
#  release of the same distribution (openjdk-25 vs openjdk-21, docker-compose-v2 vs
#  docker-compose-plugin).
install_with_pkg_mgr() {
    local tool="$1" label pkg rc=1
    label="installing $(tool_label "$tool")"
    while IFS= read -r pkg; do
        [ -z "$pkg" ] && continue
        if install_packages "$label" "$pkg"; then
            INSTALLED_NOW="$INSTALLED_NOW $tool"
            return 0
        fi
        rc=1
    done <<EOF
$(pkg_candidates "$tool")
EOF
    return "$rc"
}

#  Where a binary goes when no package manager ships the tool.
#  Make sure a sudo command can run WITHOUT asking anything -- and when it does need to ask,
#  ask the user properly: on the terminal, visibly, once.
#
#  This exists because of a specific failure. run_cmd and run_streaming send a command's
#  output to a log file, so sudo's "[sudo] password for ...:" went into a log nobody reads,
#  the screen showed only "RUN: sudo mv ...", and sudo sat there for its full five-minute
#  password timeout before failing with "FAILED (1)". A user on Ubuntu watched exactly that:
#  the download and the chmod succeeded, then five minutes of silence, then a failure.
sudo_ready() {
    [ -n "$SUDO" ] || return 1
    #  Credentials already cached: nothing to ask, nothing to hide.
    $SUDO -n true >/dev/null 2>&1 && return 0
    #  A dry run, an unattended run and a run with no terminal cannot answer a prompt, so
    #  none of them may pretend to hold a credential: they take the path a real unattended
    #  run takes, instead of waiting five minutes for an answer.
    if [ "$OPT_DRY_RUN" = "1" ] || [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
        return 1
    fi
    [ -r /dev/tty ] && [ -w /dev/tty ] || return 1
    ui_info "this step needs root; you will be asked for your password once"
    #  Deliberately NOT through run_cmd: the prompt has to reach the user, not a log file.
    if $SUDO -v </dev/tty >/dev/tty 2>&1; then
        $SUDO -n true >/dev/null 2>&1 && return 0
    fi
    ui_warn "sudo did not authenticate, so this goes somewhere you can write instead"
    return 1
}

#  Put a downloaded binary where it will be found. /usr/local/bin first, but a machine where
#  the user cannot or will not authenticate sudo still ends up with the tool installed.
install_downloaded_binary() {
    local name="$1" tmp="$2" fallback="$HOME/.local/bin" on_path="0"
    if [ -z "$BIN_SUDO" ] || [ -w "$BIN_DIR" ]; then
        run_cmd "installing $name" mv "$tmp" "$BIN_DIR/$name" && return 0
    fi
    if sudo_ready; then
        run_cmd "installing $name" $BIN_SUDO mv "$tmp" "$BIN_DIR/$name" && return 0
    fi
    ui_info "$BIN_DIR needs root, so $name is installed to $fallback instead"
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_info "[dry-run] would install it to $fallback"
        return 0
    fi
    mkdir -p "$fallback" 2>/dev/null || true
    [ -w "$fallback" ] || return 1
    run_cmd "installing $name" mv "$tmp" "$fallback/$name" || return 1
    BIN_DIR="$fallback"; BIN_SUDO=""
    case ":$PATH:" in *":$fallback:"*) on_path="1" ;; esac
    #  Usable in THIS run as well, not only in some future shell.
    PATH="$fallback:$PATH"; export PATH
    ui_ok "$name installed to $fallback"
    if [ "$on_path" = "0" ]; then
        ui_hint "add it to your PATH so later shells find it:"
        ui_hint "  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.profile"
    fi
    return 0
}

install_binary_file() {
    # install_binary_file <label> <source-url> <destination-basename>
    local label="$1" url="$2" name="$3" tmp
    tmp="$STATE_DIR/$name"
    run_streaming "$label" curl -fsSL -o "$tmp" "$url" || return 1
    run_cmd "installing $name" chmod +x "$tmp" || return 1
    install_downloaded_binary "$name" "$tmp" || return 1
    return 0
}

arch_suffix() {
    case "$(uname -m 2>/dev/null || echo x86_64)" in
        x86_64|amd64)   printf 'amd64' ;;
        aarch64|arm64)  printf 'arm64' ;;
        *)              printf 'amd64' ;;
    esac
}

linux_only_hint() {
    # Downloading a Linux binary makes no sense on macOS: say so rather than fail.
    local tool="$1"
    ui_warn "no package-manager route to $(tool_label "$tool") on $(platform_label)"
    ui_hint "install it by hand and re-run; the installer will pick it up"
    FAILED_INSTALL="$FAILED_INSTALL $tool"
    return 1
}

#  Tools that no distribution ships: fetch the official binary instead.
install_binary_tool() {
    local tool="$1" arch ver url tmp_script tmp_plugin plugin_dir
    arch="$(arch_suffix)"
    if [ "$OS_FAMILY" = "macos" ]; then
        linux_only_hint "$tool"
        return 1
    fi
    case "$tool" in
        kubectl)
            ver=$(curl -fsSL --max-time 25 https://dl.k8s.io/release/stable.txt 2>/dev/null || true)
            [ -n "$ver" ] || { ui_warn "could not resolve the current kubectl version (no network?)"; FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            url="https://dl.k8s.io/release/$ver/bin/linux/$arch/kubectl"
            install_binary_file "downloading kubectl $ver" "$url" kubectl || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            ;;
        helm)
            # The official installer script, downloaded and run rather than piped in,
            # so what is about to execute is at least a file that was seen.
            url="https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3"
            tmp_script="$STATE_DIR/get-helm-3"
            run_streaming "downloading the helm installer" curl -fsSL -o "$tmp_script" "$url" || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            #  The script installs into HELM_INSTALL_DIR, and reaches for sudo otherwise --
            #  a prompt issued from inside run_streaming goes to the log and hangs. Point it
            #  at a directory this user can write, and forbid sudo outright.
            local helmdir
            if [ "$IS_ROOT" = "1" ] || [ -w "$BIN_DIR" ]; then helmdir="$BIN_DIR"; else helmdir="$HOME/.local/bin"; fi
            mkdir -p "$helmdir" 2>/dev/null || true
            #  The script is written in bash, and on Debian/Ubuntu `sh` is dash: run that way it
            #  dies at its first `[[` with "[[: not found" and then reports "Either curl or wget
            #  is required", which is a consequence, not the cause. Run it with the shell it was
            #  written for -- bash is present, the installer itself is bash.
            local helmsh="bash"
            command -v bash >/dev/null 2>&1 || helmsh="sh"
            #  Put the target directory on PATH BEFORE the script runs. Its last act is to check
            #  that helm is reachable and exit 1 when it is not -- a complaint about $PATH, not a
            #  failed install. Reported from a VM: "helm installed into
            #  /home/.../.local/bin/helm" immediately followed by "helm not found. Is
            #  /home/.../.local/bin on your $PATH?", non-zero exit, and a perfectly good helm on
            #  disk. Prefixing PATH satisfies that check, and helm is then usable for the rest of
            #  this run as well.
            local helm_rc=0 on_path="0"
            case ":$PATH:" in
                *":$helmdir:"*) on_path="1" ;;
                *) PATH="$helmdir:$PATH"; export PATH ;;
            esac
            run_streaming "installing helm" env HELM_INSTALL_DIR="$helmdir" USE_SUDO=false "$helmsh" "$tmp_script" || helm_rc=$?
            #  Judge the outcome by what is on disk, not by the exit code: it conflates "installed
            #  but not on PATH" with "could not install", and only the disk knows which happened.
            if ! command -v helm >/dev/null 2>&1 && [ ! -x "$helmdir/helm" ]; then
                ui_warn "helm could not be installed (the installer script exited $helm_rc)"
                FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1
            fi
            if [ "$helm_rc" -ne 0 ]; then
                ui_info "the helm installer script exited $helm_rc, but helm is on disk"
            fi
            ui_ok "helm installed: $(command -v helm 2>/dev/null || printf '%s' "$helmdir/helm")"
            if [ "$on_path" = "0" ]; then
                ui_hint "$helmdir is not on your PATH yet, so later shells would not find helm:"
                ui_hint "  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.profile"
            fi
            ;;
        minikube)
            url="https://storage.googleapis.com/minikube/releases/latest/minikube-linux-$arch"
            install_binary_file "downloading minikube" "$url" minikube || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            ;;
        kind)
            #  kind publishes one static binary per platform; no package manager needed.
            url="https://kind.sigs.k8s.io/dl/latest/kind-linux-$arch"
            install_binary_file "downloading kind" "$url" kind || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            ;;
        compose)
            # The plugin binary, exactly as the upstream docs install it.
            url="https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$arch"
            tmp_plugin="$STATE_DIR/docker-compose"
            run_streaming "downloading the compose plugin" curl -fsSL -o "$tmp_plugin" "$url" || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            run_cmd "installing the compose plugin" chmod +x "$tmp_plugin" || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            plugin_dir="/usr/local/lib/docker/cli-plugins"
            #  A plugin in the user's own directory works just as well and needs no root,
            #  so that is the fallback rather than a failure.
            if [ "$IS_ROOT" != "1" ] && ! sudo_ready; then
                plugin_dir="$HOME/.docker/cli-plugins"
            fi
            if [ "$plugin_dir" = "$HOME/.docker/cli-plugins" ]; then
                mkdir -p "$plugin_dir" 2>/dev/null || true
                run_cmd "installing the compose plugin" mv "$tmp_plugin" "$plugin_dir/docker-compose" || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            else
                run_cmd "installing the compose plugin" $BIN_SUDO mkdir -p "$plugin_dir" || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
                run_cmd "installing the compose plugin" $BIN_SUDO mv "$tmp_plugin" "$plugin_dir/docker-compose" || { FAILED_INSTALL="$FAILED_INSTALL $tool"; return 1; }
            fi
            ;;
        *)  linux_only_hint "$tool"; return 1 ;;
    esac
    INSTALLED_NOW="$INSTALLED_NOW $tool"
    return 0
}

#  macOS takes Docker from a cask (Docker Desktop), and that is a GUI application
#  rather than a CLI package, so it is handled separately and never silently.
install_docker_macos() {
    if [ -z "$PKG_MGR" ]; then
        ui_warn "Homebrew is not installed, so Docker cannot be installed here"
        ui_hint "either install Docker Desktop from https://docker.com/products/docker-desktop"
        ui_hint "or install Homebrew (https://brew.sh) and re-run this installer"
        FAILED_INSTALL="$FAILED_INSTALL docker"
        return 1
    fi
    ui_hint "Docker Desktop is a GUI application: Homebrew will install it, but you"
    ui_hint "must start it once by hand before the daemon answers"
    if run_streaming "installing Docker Desktop" brew install --cask docker; then
        INSTALLED_NOW="$INSTALLED_NOW docker"
        return 0
    fi
    FAILED_INSTALL="$FAILED_INSTALL docker"
    return 1
}

install_one_tool() {
    local tool="$1"
    case "$tool" in
        docker)
            if [ "$OS_FAMILY" = "macos" ]; then
                install_docker_macos
                return $?
            fi
            install_with_pkg_mgr "$tool"
            return $?
            ;;
        compose)
            if install_with_pkg_mgr "$tool"; then
                return 0
            fi
            # No v2 plugin in this release's repositories: take the official binary.
            install_binary_tool "$tool"
            return $?
            ;;
        kubectl|helm|minikube|kind)
            if install_with_pkg_mgr "$tool"; then
                return 0
            fi
            install_binary_tool "$tool"
            return $?
            ;;
        *)
            install_with_pkg_mgr "$tool"
            return $?
            ;;
    esac
}

#  The package manager command a user would run by hand, for the manual route.
manual_command() {
    local tool="$1" pkgs
    # Docker on macOS means Docker Desktop, not the 'docker' CLI formula: the CLI
    # alone has no daemon and would look like a successful install that does nothing.
    if [ "$OS_FAMILY" = "macos" ]; then
        case "$tool" in
            docker)  printf 'brew install --cask docker     # Docker Desktop, then start it once'; return 0 ;;
            compose) printf 'docker compose ships with Docker Desktop; CLI only: brew install docker-compose'; return 0 ;;
        esac
    fi
    pkgs=$(pkg_candidates "$tool" | tr '\n' ' ')
    # Trim the trailing space, both for looks and so the copy-paste is exact.
    pkgs=$(printf '%s' "$pkgs" | sed 's/ *$//')
    if [ -n "$pkgs" ]; then
        case "$OS_FAMILY" in
            debian) printf '%s apt-get install -y %s' "$SUDO" "$pkgs" ;;
            fedora) printf '%s dnf install -y %s' "$SUDO" "$pkgs" ;;
            arch)   printf '%s pacman -S --needed %s' "$SUDO" "$pkgs" ;;
            suse)   printf '%s zypper install %s' "$SUDO" "$pkgs" ;;
            macos)  printf 'brew install %s' "$pkgs" ;;
        esac
        return 0
    fi
    local arch
    arch="$(arch_suffix)"
    case "$tool" in
        kubectl) printf 'curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/%s/kubectl" && chmod +x kubectl && sudo mv kubectl /usr/local/bin/' "$arch" ;;
        helm)    printf 'curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash' ;;
        minikube) printf 'curl -Lo minikube "https://storage.googleapis.com/minikube/releases/latest/minikube-linux-%s" && chmod +x minikube && sudo mv minikube /usr/local/bin/' "$arch" ;;
        kind)    printf 'curl -Lo kind "https://kind.sigs.k8s.io/dl/latest/kind-linux-%s" && chmod +x kind && sudo mv kind /usr/local/bin/' "$arch" ;;
        compose) printf 'install the compose v2 plugin: https://docs.docker.com/compose/install/linux/' ;;
        *)       printf '' ;;
    esac
}

manual_instructions() {
    # manual_instructions <tool...>
    local tools="$1" t cmd n
    # Count the words, not the arguments: the caller passes the whole list as one
    # string so that an empty list stays one (empty) word.
    n=0
    for t in $tools; do n=$(( n + 1 )); done
    ui_blank
    if [ "$n" -gt 1 ]; then
        ui_info "install them yourself, then re-run this installer:"
    else
        ui_info "install it yourself, then re-run this installer:"
    fi
    ui_blank
    for t in $tools; do
        cmd="$(manual_command "$t")"
        # Compose is part of Docker Desktop (macOS) and of the docker package on most
        # distributions, so its "command" is sometimes an explanation, not a command.
        case "$t" in
            compose)
                case "$OS_FAMILY" in
                    macos) ui_hint "$cmd" ;;
                    *)     if [ -n "$cmd" ]; then ui_hint "$(tool_label "$t"): $cmd"; else ui_hint "docker compose ships with the docker package"; fi ;;
                esac
                ;;
            *)
                if [ -n "$cmd" ]; then
                    ui_hint "$(tool_label "$t"): $cmd"
                else
                    ui_hint "$(tool_label "$t"): see the documentation for $(platform_label)"
                fi
                ;;
        esac
    done
    case "$OS_FAMILY" in
        macos)
            if [ -z "$PKG_MGR" ]; then
                ui_blank
                ui_hint "Homebrew is not installed: https://brew.sh installs it in one command"
            fi
            ;;
    esac
    case "$IS_WSL" in
        1)
            ui_blank
            ui_info "you are on WSL:"
            ui_hint "the least painful route to a Docker daemon is Docker Desktop with WSL integration"
            ui_hint "https://docs.docker.com/desktop/wsl/  (or install docker here and start dockerd)"
            ;;
    esac
    ui_blank
    ui_hint "this machine looks like: $(platform_label)"
}

#  The daemon is up but this user cannot open its socket. Report that honestly, offer the
#  group fix, and stop. Shared: the preflight, the dependency check and the daemon-start
#  attempt all arrive here, and all of them used to say "the daemon is not answering" --
#  advice to restart a service that is running, from an installer being run on a host whose
#  `systemctl status docker` says `active (running)`.
docker_permission_stop() {
    ui_blank
    ui_warn "the docker daemon is running, but this user cannot open its socket"
    if docker_reachable_only_with_sudo; then
        ui_hint "confirmed: 'sudo docker info' answers, so the daemon is healthy and only your"
        ui_hint "group membership is missing"
    fi
    local in_group="0"
    case " $(id -nG) " in *" docker "*) in_group="1" ;; esac
    if [ -n "$SUDO" ] && [ "$in_group" = "0" ]; then
        if [ "$OPT_YES" = "1" ] || [ "$OPT_INSTALL_DEPS" = "no" ]; then
            #  Never change someone's group membership without being asked, and never in an
            #  unattended run.
            ui_hint "add yourself to the docker group:"
            ui_hint "  $SUDO usermod -aG docker \${USER}"
        else
            ask_yesno addgrp "Add $USER to the docker group now?" "y"
            if [ "$addgrp" = "y" ]; then
                #  Needs root: sudo_ready asks on the terminal, once, and says so honestly
                #  when it cannot -- nothing here may report a change that did not happen.
                if sudo_ready && run_cmd_soft "adding $USER to the docker group" \
                        sh -c "$SUDO_N usermod -aG docker \$USER"; then
                    ui_ok "added to the docker group"
                else
                    ui_hint "could not change your group membership; do it yourself:"
                    ui_hint "  $SUDO usermod -aG docker \${USER}"
                fi
            else
                ui_hint "add yourself to the docker group:"
                ui_hint "  $SUDO usermod -aG docker \${USER}"
            fi
        fi
    elif [ "$in_group" = "1" ]; then
        ui_hint "you are already in the docker group, so this shell has not picked it up yet"
    fi
    ui_hint "then apply it to THIS shell and run this installer again:"
    ui_hint "  newgrp docker      # or log out and back in"
    fail "docker is not usable by this user yet" \
         "run: newgrp docker   (then run this installer again)"
}

#  Docker is installed but nothing is listening: that is not an install problem, so
#  do not send the user off to install it again.
offer_start_docker() {
    #  A daemon that is already up is not started again; and a socket this user may not open
    #  is not a daemon problem at all -- starting it would "succeed" and change nothing.
    probe_docker_daemon
    if [ "$DOCKER_STATE" = "permission" ]; then
        ui_warn "the docker daemon is running -- this user just cannot open its socket"
        ui_hint "the daemon does not need starting; your group membership does"
        return 1
    fi
    phase "$(em '🐳') Starting the Docker daemon"
    ui_warn "the docker command exists, but its daemon is not answering"
    local start=""
    if [ "$OS_FAMILY" = "macos" ]; then
        start=""
        ui_hint "starting Docker Desktop ..."
        run_cmd_soft "starting Docker Desktop" open -a Docker || true
    elif command -v systemctl >/dev/null 2>&1; then
        #  Needs root, so settle the credentials here -- visibly -- and then run it with -n.
        #  Through run_cmd_soft the output is redirected, and a prompt there is invisible.
        if [ -n "$SUDO" ] && ! sudo_ready; then
            ui_warn "starting the daemon needs root, and sudo was not authenticated"
        fi
        start="$SUDO_N systemctl start docker"
    elif command -v service >/dev/null 2>&1; then
        if [ -n "$SUDO" ] && ! sudo_ready; then
            ui_warn "starting the daemon needs root, and sudo was not authenticated"
        fi
        start="$SUDO_N service docker start"
    fi
    if [ -n "$start" ]; then
        ui_hint "running: $start"
        # Soft on purpose: it may need a password, or the unit may not exist yet.
        run_cmd_soft "starting the docker daemon" sh -c "$start" || true
    fi
    if wait_for "the docker daemon to answer" 60 docker_daemon_ok; then
        if [ "$OPT_DRY_RUN" = "1" ]; then
            # wait_for cannot actually observe anything in a dry run, so do not print a
            # success line for something that was never checked.
            ui_info "a real run would verify the daemon here"
        else
            ui_ok "the docker daemon is answering now"
        fi
        return 0
    fi
    ui_warn "the daemon still is not answering"
    ui_hint "start it yourself (Docker Desktop, or: $SUDO systemctl start docker), then re-run"
    return 1
}

#  The offer itself. Runs after the mode is known, because what is required depends
#  on the path: Kubernetes needs kubectl, and both paths need Docker (both build
#  their images here).
ensure_dependencies() {
    # A present binary with a dead daemon is a different problem from a missing one.
    if [ "$HAVE_DOCKER" != "1" ] && docker_binary_usable; then
        offer_start_docker || true
        probe_tools >/dev/null 2>&1 || true
    fi

    local required="curl docker compose" t
    if [ "$CFG_MODE" = "kubernetes" ]; then required="$required kubectl"; fi
    local buildtools="java maven"

    local missing_req="" missing_opt=""
    for t in $required; do tool_ok "$t" || missing_req="$missing_req $t"; done
    for t in $buildtools; do tool_ok "$t" || missing_opt="$missing_opt $t"; done

    if [ -z "$missing_req" ] && [ -z "$missing_opt" ]; then
        return 0
    fi

    phase "$(em '📦') Missing dependencies"
    if [ -n "$missing_req" ]; then
        for t in $missing_req; do
            ui_err "$(tool_label "$t") -- $(tool_role "$t")"
        done
    fi
    if [ -n "$missing_opt" ]; then
        for t in $missing_opt; do
            ui_warn "$(tool_label "$t") -- $(tool_role "$t")"
        done
    fi
    ui_blank
    ui_kv "this machine" "$(platform_label)"

    #  Every remaining branch below either installs packages or prints instructions for
    #  installing them. Neither is right when the only thing "missing" is permission to open
    #  a socket of a daemon that is already running: the honest fix is group membership, and
    #  an installer that reports "the daemon is not answering" here sends people to restart a
    #  service their own `systemctl status docker` calls `active (running)`. So settle that
    #  case before any of the branches can speak.
    if [ -n "$missing_req" ]; then
        local only_docker="1" t3=""
        for t3 in $missing_req; do
            case "$t3" in
                docker|compose) ;;
                *) only_docker="0" ;;
            esac
        done
        if [ "$only_docker" = "1" ]; then
            probe_docker_daemon
            case "$DOCKER_STATE" in
                permission)
                    docker_permission_stop
                    ;;
                not_running)
                    #  Installed and simply not started: also not an install problem. (The
                    #  offer to start it has already been made above.)
                    ui_blank
                    ui_warn "docker is installed, but no daemon is listening"
                    if [ "$OS_FAMILY" = "macos" ]; then
                        ui_hint "start Docker Desktop, then run this installer again"
                    else
                        ui_hint "start it:  $SUDO systemctl start docker   (or:  $SUDO service docker start)"
                        ui_hint "then run this installer again"
                    fi
                    fail "the docker daemon is not running" \
                         "start Docker and run this installer again"
                    ;;
            esac
        fi
    fi

    # Nothing to offer without a package manager, so go straight to instructions.
    if [ -z "$PKG_MGR" ]; then
        if [ "$OPT_INSTALL_DEPS" = "yes" ]; then
            ui_warn "auto-install was requested, but no package manager was found"
        else
            ui_info "no package manager was found on this machine"
        fi
        manual_instructions "$missing_req $missing_opt"
        fail "missing dependencies" "install them as shown above, then run this installer again"
    fi

    local answer="yes"
    if [ "$OPT_INSTALL_DEPS" = "no" ]; then
        answer="no"
    elif [ "$OPT_INSTALL_DEPS" != "yes" ]; then
        if [ "$OPT_DRY_RUN" = "1" ]; then
            ui_hint "[dry-run] would ask whether to install these with $PKG_MGR"
            answer="yes"
        elif [ "$OPT_YES" = "1" ] || [ "$UI_INTERACTIVE" != "1" ]; then
            # Unattended: installing system packages without being asked is too
            # invasive, so treat it as declined and print the commands instead.
            answer="no"
        else
            ui_blank
            ui_info "the installer can install these now with $PKG_MGR"
            ui_hint "it only uses $(platform_label)'s own package manager, and everything it"
            ui_hint "runs is printed first and written to the log"
            local want="y"
            ask_yesno want "Install the missing dependencies automatically?" "y"
            [ "$want" = "y" ] && answer="yes" || answer="no"
        fi
    fi

    if [ "$answer" != "yes" ]; then
        ui_blank
        ui_warn "not installing anything"
        manual_instructions "$missing_req $missing_opt"
        fail "missing dependencies" \
             "install them as shown above, then run this installer again"
    fi

    ui_blank
    local failed=""
    # Required first, and a failure there is fatal: the chosen path cannot work.
    for t in $missing_req; do
        if install_one_tool "$t"; then
            ui_ok "$(tool_label "$t") installed"
        else
            ui_err "$(tool_label "$t") could not be installed"
            failed="$failed $t"
        fi
    done
    # Build-only tools are best effort: the network runs without them.
    for t in $missing_opt; do
        if install_one_tool "$t"; then
            ui_ok "$(tool_label "$t") installed"
        else
            ui_warn "$(tool_label "$t") could not be installed automatically"
        fi
    done

    if [ -n "$INSTALLED_NOW" ]; then
        ui_blank
        ui_kv "installed" "$INSTALLED_NOW"
    fi

    # Re-probe rather than assume: a package exists the moment it is installed, but the
    # daemon it needs may not be running yet, and the compose plugin is only probed
    # once the daemon answers.
    probe_tools >/dev/null 2>&1 || true

    # An installed Docker whose daemon is silent is not a missing package, so deal with
    # the daemon first and only then decide what is genuinely absent. This is the normal
    # shape after a fresh install, and on WSL, where nothing starts dockerd for you.
    if [ "$HAVE_DOCKER" != "1" ] && docker_binary_usable; then
        if offer_start_docker; then
            probe_tools >/dev/null 2>&1 || true
        fi
    fi

    local still="" t2="" daemon_only="1"
    for t2 in $required; do tool_ok "$t2" || still="$still $t2"; done
    for t2 in $still; do
        # Anything left that is not docker-or-compose-with-a-dead-daemon is a genuinely
        # missing tool, which the manual route has to describe instead.
        case "$t2" in
            docker|compose)
                # A shim is "not installed" for our purposes, so this is a package to
                # install, not a daemon to start.
                if ! docker_binary_usable; then daemon_only="0"; fi
                ;;
            *) daemon_only="0" ;;
        esac
    done

    if [ -n "$still" ]; then
        if [ "$daemon_only" = "1" ]; then
            #  "Missing" here can also mean "present and healthy but not open to this user",
            #  which is a different fix (group membership, not restarting a daemon).
            probe_docker_daemon
            if [ "$DOCKER_STATE" = "permission" ]; then
                docker_permission_stop
            fi
            ui_blank
            ui_warn "docker is installed, but its daemon is not running, so nothing can start yet"
            if [ "$OS_FAMILY" = "macos" ]; then
                ui_hint "start Docker Desktop, then run this installer again"
            else
                ui_hint "start it:  $SUDO systemctl start docker   (or:  $SUDO service docker start)"
                ui_hint "then run this installer again"
            fi
            fail "the docker daemon is not running" \
                 "start Docker and run this installer again"
        fi
        manual_instructions "$still"
        fail "some tools are still missing after installing" \
             "install them as shown above, then run this installer again"
    fi

    #  Present, and possibly perfectly healthy, but not open to this user: the docker
    #  socket belongs to a group, and group membership only takes effect in a NEW session.
    #  This is the normal state of a fresh Ubuntu install, and reporting it as "the daemon
    #  is not running" sends people to restart a service that is already running.
    probe_docker_daemon
    case "$DOCKER_STATE" in
        ok)
            ;;
        permission)
            docker_permission_stop
            ;;
        not_running)
            ui_blank
            if [ "$OS_FAMILY" = "macos" ]; then
                ui_warn "docker is installed, but Docker Desktop is not answering yet"
                ui_hint "start Docker Desktop, let it settle, then re-run this installer"
            else
                ui_warn "docker is installed, but no daemon is listening on its socket"
                ui_hint "start it:  $SUDO systemctl start docker   (or:  $SUDO service docker start)"
            fi
            fail "the docker daemon is not running" \
                 "start Docker, then run this installer again"
            ;;
        *)
            ui_blank
            ui_warn "docker is installed, but it did not answer usefully: ${DOCKER_ERR:-no output}"
            ui_hint "run 'docker info' to see the full error, then re-run this installer"
            fail "docker is not usable on this machine" \
                 "see the message above"
            ;;
    esac
    ui_ok "every dependency this path needs is present"
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

    #  Any stack whose controller can start a game server needs the game image, including
    #  "full network": the controller provisions containers from it, so skipping the build
    #  there leaves a network that can never place a player in a match.
    if [ "$CFG_STACK" != "infra" ]; then
        phase "$(em '⚙️') Server engine for the game servers"
        ui_info "this is the Minecraft server software the match pods run."
        if [ "$OPT_YES" = "1" ]; then
            ui_info "engine: $CFG_ENGINE (from the defaults; pass the wizard to choose)"
        else
            ask_menu choice "Which server engine should the game servers run?" "1" \
                "1|Spigot 26.3|Spigot's own BuildTools compiles it on the first build (several minutes), or uses your jar from server-jars/ as-is. Exactly Spigot, never Paper." \
                "2|Paper 26.3|Downloaded prebuilt from the PaperMC API: no compile step, so the first build is minutes faster. Runs this plugin unchanged."
            case "$choice" in
                2) CFG_ENGINE="paper" ;;
                *) CFG_ENGINE="spigot" ;;
            esac
            if [ "$CFG_ENGINE" = "paper" ]; then
                ui_hint "Paper: version $CFG_PAPER_VERSION is downloaded at image build time."
            else
                ui_hint "Spigot: a matching jar in server-jars/ is used as-is; without one the image"
                ui_hint "compiles Spigot $CFG_SPIGOT_REV with BuildTools (slow first build, then cached)."
            fi
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
# ---------------------------------------------------------------------------
#  Reaching a cluster without sending the user away to do homework
# ---------------------------------------------------------------------------
#  "no cluster is reachable, point kubectl at one and re-run" made the user leave the
#  installer, work it out by hand and come back -- which is the opposite of a guided
#  install, and it cannot see the two most common cases: a context that is listed but
#  dead, and a local cluster that exists but is not running.

kube_current_context() { kubectl config current-context 2>/dev/null || true; }

kube_contexts() { kubectl config get-contexts -o name 2>/dev/null || true; }

#  Does the API server behind this context answer? A context can be listed and dead; that
#  is a different thing from "no cluster exists", and it is the common one.
kube_context_alive() {
    [ -n "$1" ] || return 1
    kubectl --context="$1" cluster-info --request-timeout=3s >/dev/null 2>&1
}

#  minikube has no porcelain list, so the names come out of its JSON without needing jq.
minikube_profiles() {
    command -v minikube >/dev/null 2>&1 || return 0
    minikube profile list -o json 2>/dev/null | tr ',' '\n' | grep -o '"Name":"[^"]*"' \
        | cut -d'"' -f4 || true
}

minikube_profile_running() {
    [ -n "$1" ] || return 1
    local host=""
    host=$(minikube status -p "$1" -o json 2>/dev/null | grep -o '"Host":"[A-Za-z]*"' \
        | cut -d'"' -f4 || true)
    [ "$host" = "Running" ]
}

kind_clusters() {
    command -v kind >/dev/null 2>&1 || return 0
    kind get clusters 2>/dev/null || true
}

#  A name the user types is worth validating against the real list, because the cost of a
#  typo is a confusing "context not found" further down.
kube_is_known_context() {
    local c=""
    for c in $(kube_contexts); do [ "$c" = "$1" ] && return 0; done
    return 1
}

#  Switch kubectl to a context and confirm the cluster behind it really answers before
#  letting the install depend on it.
kube_use_context() {
    local ctx="$1"
    run_cmd "pointing kubectl at '$ctx'" kubectl config use-context "$ctx" || return 1
    if [ "$OPT_DRY_RUN" = "1" ]; then
        HAVE_CLUSTER="1"; CLUSTER_CONTEXT="$ctx"
        ui_info "[dry-run] a real run would now verify that '$ctx' answers"
        return 0
    fi
    if wait_for "the cluster in '$ctx' to answer" 30 kube_context_alive "$ctx"; then
        HAVE_CLUSTER="1"; CLUSTER_CONTEXT="$ctx"
        ui_ok "cluster reachable through context '$ctx'"
        return 0
    fi
    ui_warn "'$ctx' is selected, but nothing answers there yet"
    return 1
}

#  Start a local cluster that already exists. minikube keeps profiles around when they are
#  stopped, and a stopped kind cluster is a stopped container -- both are one command from
#  working again, and neither is worth making the user look up.
kube_start_minikube() {
    local profile="$1"
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_info "[dry-run] would run: minikube start -p $profile"
        HAVE_CLUSTER="1"; CLUSTER_CONTEXT="$profile"
        return 0
    fi
    #  Start it now rather than at provisioning time: everything the wizard asks next
    #  (namespaces, existing CRDs, where images have to be loaded) is better answered
    #  against a cluster that is actually up.
    run_streaming "starting the minikube cluster '$profile'" minikube start -p "$profile" || {
        ui_warn "minikube would not start the profile '$profile'"
        ui_hint "run 'minikube start -p $profile' by hand to see the full error"
        return 1
    }
    #  A just-started cluster is not instantly answerable: poll the API, not the process.
    if wait_for "the cluster API to answer" 180 kubectl cluster-info; then
        HAVE_CLUSTER="1"; CLUSTER_CONTEXT=$(kube_current_context)
        [ -n "$CLUSTER_CONTEXT" ] || CLUSTER_CONTEXT="$profile"
        ui_ok "minikube cluster '$profile' is up"
        return 0
    fi
    ui_warn "minikube started, but the API did not answer in time"
    return 1
}

kube_start_kind() {
    local name="$1"
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_info "[dry-run] would run: docker start $name-control-plane"
        HAVE_CLUSTER="1"; CLUSTER_CONTEXT="kind-$name"
        return 0
    fi
    run_cmd "starting the kind node '$name-control-plane'" docker start "$name-control-plane" || return 1
    if wait_for "the cluster API to answer" 180 kubectl cluster-info; then
        HAVE_CLUSTER="1"; CLUSTER_CONTEXT="kind-$name"
        ui_ok "kind cluster '$name' is up"
        return 0
    fi
    ui_warn "the kind node started, but the API did not answer in time"
    return 1
}

#  Ask the cluster what it is, act on the answer, and keep offering until the user has
#  either a cluster or an explicit decision to stop. Every option here is something the
#  installer can do itself.
#
#  Note the appends: `arr[${#arr[@]}]=value`, never `arr=( "${arr[@]}" value )`. Expanding
#  an empty array is an unbound-variable error under `set -u` in bash 3.2 -- which is
#  /bin/bash on macOS, and which is exactly how this loop first died.
kube_resolve_cluster() {
    local round=0 t="" pick="" action="" current="" scanned=0
    local ask_menu_out="" named=""
    local -a menu=()
    local -a values=()
    local -a alive=()

    while :; do
        round=$(( round + 1 ))
        HAVE_CLUSTER="0"; CLUSTER_CONTEXT=""
        #  Insurance: every branch above returns or exits, but a loop that cannot end is the
        #  worst possible failure mode in an installer, so it is bounded explicitly.
        if [ "$round" -gt 10 ]; then
            fail "could not settle on a Kubernetes cluster" \
                 "run 'kubectl config get-contexts' and 'minikube status' by hand, then re-run"
        fi

        #  The current context first: it is what the next command would use anyway.
        current=$(kube_current_context)
        alive=()
        if kube_context_alive "$current"; then
            alive[${#alive[@]}]="$current"
        fi
        #  Then the others, capped: a machine with fifty contexts must not spend a minute
        #  in connection timeouts. "Point at one by name" covers whatever is left.
        for t in $(kube_contexts); do
            [ "$t" = "$current" ] && continue
            [ "$scanned" -ge 10 ] && break
            scanned=$(( scanned + 1 ))
            if kube_context_alive "$t"; then alive[${#alive[@]}]="$t"; fi
        done

        if [ "${#alive[@]}" -ge 1 ]; then
            if [ "${#alive[@]}" = "1" ]; then
                if [ "${alive[0]}" = "$current" ]; then
                    #  Already there: switching would be a no-op command on screen.
                    HAVE_CLUSTER="1"; CLUSTER_CONTEXT="$current"
                    ui_ok "the current context already answers: $current"
                    return 0
                fi
                if kube_use_context "${alive[0]}"; then return 0; fi
            else
                #  Several answer: let the user choose rather than silently taking one.
                menu=(); values=()
                for t in "${alive[@]}"; do
                    menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|${t}|reachable"
                    values[${#values[@]}]="use:$t"
                done
                ui_blank
                ui_info "more than one cluster answers; the current context is '${current:-none}'"
                ask_menu pick "Which cluster should this install use?" "1" "${menu[@]}"
                local chosen="${values[$(( pick - 1 ))]}"
                if kube_use_context "${chosen#use:}"; then return 0; fi
                continue
            fi
        fi

        #  Nothing answers. Offer the ways out, in the order that costs the user least.
        menu=(); values=()
        local mkp="" kc=""
        for mkp in $(minikube_profiles); do
            if minikube_profile_running "$mkp"; then continue; fi
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Start the minikube cluster '$mkp'|minikube is installed and '$mkp' exists, but is not running."
            values[${#values[@]}]="minikube-start:$mkp"
        done
        for kc in $(kind_clusters); do
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Start the kind cluster '$kc'|Its node container exists but is stopped."
            values[${#values[@]}]="kind-start:$kc"
        done
        if command -v minikube >/dev/null 2>&1; then
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Create a new minikube cluster|minikube makes one here; you pick CPUs, memory and a driver next."
            values[${#values[@]}]="minikube-create:"
        elif command -v kind >/dev/null 2>&1; then
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Create a new kind cluster|kind makes one here, in Docker."
            values[${#values[@]}]="kind-create:"
        fi
        if ! command -v minikube >/dev/null 2>&1 && ! command -v kind >/dev/null 2>&1; then
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Install minikube, then create a cluster|The local cluster this installer sets up end to end, drivers and sizes included."
            values[${#values[@]}]="install:minikube"
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Install kind, then create a cluster|Lighter: its clusters are Docker containers. Assumes Docker is working."
            values[${#values[@]}]="install:kind"
        fi
        if [ -n "$(kube_contexts)" ]; then
            menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Point at a context by name|For a cluster somewhere else: name the kubectl context to use."
            values[${#values[@]}]="use-named:"
        fi
        menu[${#menu[@]}]="$(( ${#menu[@]} + 1 ))|Stop here|Prints what is missing. You can also re-run with --mode docker."
        values[${#values[@]}]="stop:"

        ui_blank
        ui_warn "no Kubernetes cluster answers right now (current context: '${current:-none}')"
        ui_hint "Docker Desktop's own Kubernetes also has to be switched on there first"
        ask_menu pick "How should I get you a cluster?" "1" "${menu[@]}"
        action="${values[$(( pick - 1 ))]}"
        case "$action" in
            use:*)
                kube_use_context "${action#use:}" && return 0
                ;;
            use-named:*)
                ask named "kubectl context name" "$current" v_notempty
                if kube_is_known_context "$named" || [ "$OPT_DRY_RUN" = "1" ]; then
                    kube_use_context "$named" && return 0
                else
                    ui_err "'$named' is not one of kubectl's contexts"
                    ui_hint "kubectl config get-contexts   # lists them"
                fi
                ;;
            minikube-start:*)
                kube_start_minikube "${action#minikube-start:}" && return 0
                ;;
            kind-start:*)
                kube_start_kind "${action#kind-start:}" && return 0
                ;;
            minikube-create:*)
                CFG_K8S_PROVISION_CLUSTER="y"; CFG_K8S_CLUSTER_TOOL="minikube"
                ui_ok "a minikube cluster will be created later in this run"
                return 0
                ;;
            kind-create:*)
                CFG_K8S_PROVISION_CLUSTER="y"; CFG_K8S_CLUSTER_TOOL="kind"
                ui_ok "a kind cluster will be created later in this run"
                return 0
                ;;
            install:*)
                if [ "$OPT_YES" = "1" ] || [ "$OPT_INSTALL_DEPS" = "no" ]; then
                    #  Never install a package unattended: an unattended run must not
                    #  decide to put software on the machine.
                    ui_warn "not installing anything unattended"
                    ui_hint "install it yourself, then re-run:"
                    ui_hint "  $(manual_command "minikube")"
                    fail "no cluster, and nothing installed to create one" \
                         "install minikube (or kind) and re-run this installer"
                fi
                local want="y" tool="${action#install:}"
                ask_yesno want "Install $tool now?" "y"
                if [ "$want" != "y" ]; then
                    ui_hint "install it yourself, then re-run:"
                    ui_hint "  $(manual_command "$tool")"
                    fail "no cluster, and nothing installed to create one" \
                         "install $tool and re-run this installer"
                fi
                if install_one_tool "$tool"; then
                    probe_tools >/dev/null 2>&1 || true
                    CFG_K8S_PROVISION_CLUSTER="y"; CFG_K8S_CLUSTER_TOOL="$tool"
                    ui_ok "$tool installed; a cluster will be created later in this run"
                    return 0
                fi
                fail "$tool could not be installed" \
                     "install it yourself, then re-run this installer"
                ;;
            stop:*)
                ui_blank
                ui_hint "the ways out, in the order they usually apply:"
                ui_hint "  1. point kubectl at a cluster:  kubectl config use-context <name>"
                ui_hint "  2. start a local one:           minikube start   /  kind create cluster"
                ui_hint "  3. install one:                 $(manual_command "minikube")"
                ui_hint "  4. use Docker instead:          re-run with --mode docker"
                fail "no Kubernetes cluster was chosen" \
                     "do one of the above, then run this installer again"
                ;;
        esac
    done
}
wizard_kubernetes() {
    phase "$(em '☸️') Kubernetes: cluster"

    #  kubectl first: without it nothing about a cluster can be judged, and it is one
    #  install away on every platform this installer supports.
    if [ "$HAVE_KUBECTL" != "1" ]; then
        ui_warn "kubectl is not installed, and it is how this path reaches a cluster"
        local getk="y"
        ask_yesno getk "Install kubectl now?" "y"
        if [ "$getk" = "y" ] && [ "$OPT_YES" != "1" ] && [ "$OPT_INSTALL_DEPS" != "no" ]; then
            install_one_tool kubectl && probe_tools >/dev/null 2>&1 || true
        elif [ "$getk" = "y" ]; then
            ui_warn "not installing anything unattended"
            ui_hint "install it yourself, then re-run:"
            ui_hint "  $(manual_command "kubectl")"
        fi
        if [ "$HAVE_KUBECTL" != "1" ]; then
            fail "the Kubernetes path needs kubectl" \
                 "install kubectl (see the command above), or re-run with --mode docker"
        fi
    fi

    #  This is where the old installer gave up with "point kubectl at one and re-run".
    #  It now looks for a context that answers, offers to start a local cluster that
    #  already exists but is stopped, offers to create one, and offers to install the
    #  tool that creates it -- and only stops if the user chooses to stop.
    kube_resolve_cluster
    if [ "$HAVE_CLUSTER" = "1" ]; then
        ui_ok "using the cluster in context: $CLUSTER_CONTEXT"
        local prov="n"
        ask_yesno prov "Create a fresh local cluster as well?" "n"
        if [ "$prov" = "y" ]; then
            CFG_K8S_PROVISION_CLUSTER="y"
        fi
    fi

    if [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
        if [ -z "$CFG_K8S_CLUSTER_TOOL" ]; then
            #  Whichever is installed decides; minikube is the better-supported one here.
            if command -v minikube >/dev/null 2>&1; then CFG_K8S_CLUSTER_TOOL="minikube"
            elif command -v kind >/dev/null 2>&1; then CFG_K8S_CLUSTER_TOOL="kind"
            else CFG_K8S_CLUSTER_TOOL="minikube"; fi
        fi
        if [ "$CFG_K8S_CLUSTER_TOOL" = "kind" ]; then
            ui_info "kind sizes its cluster from Docker's own resources, so there is nothing to pick here"
        else
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
    if [ "$HAVE_MINIKUBE" = "1" ] || [ "$HAVE_KIND" = "1" ] || [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
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
        files=$(find "$CFG_HUB_WORLD_SRC" -type f 2>/dev/null | wc -l | tr -d ' ' || echo 0)
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
            baked_files=$(find "$ROOT/deploy/templates/$arena_baked" -type f 2>/dev/null | wc -l | tr -d ' ' || echo 0)
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
        #  Capacity and arena group. These were asked for and then dropped on the Docker
        #  path, so the controller fell back to its own defaults (maximum 10 servers) while
        #  the operator believed they had configured two: pre-warming then filled ten.
        printf '# Capacity: servers x matches-per-server = concurrent matches\n'
        printf 'BEDWARS_MIN_SERVERS=%s\n' "$CFG_MIN_SERVERS"
        printf 'BEDWARS_MAX_SERVERS=%s\n' "$CFG_MAX_SERVERS"
        printf 'BEDWARS_GAMES_PER_SERVER=%s\n' "$CFG_GAMES_PER_SERVER"
        printf 'BEDWARS_ARENA_GROUP=%s\n' "$CFG_ARENA_GROUP"
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
        printf '#   helm upgrade --install %s deploy/helm/bedwars -n %s -f deploy/helm/values-install.yaml\n' "$CFG_RELEASE" "$CFG_NAMESPACE"
        printf '#   (the installer creates the namespace itself; --create-namespace fails when\n'
        printf '#    the namespace already exists and is not owned by the release)\n\n'
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
    #  "network" builds the game image too: its controller provisions match containers from
    #  it, so a network install without it can never start a game server.
    case "$CFG_STACK" in
        game|everything|network) svcs="$svcs game-pod" ;;
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

#  Is this tool usable now? A fresh probe, plus the one case a dry run cannot express: a tool
#  that THIS run reported installing is not actually on disk in a dry run, and a dry run that
#  then failed with "is not installed" would describe a state it had just said it fixed.
tool_available_now() {
    local t="$1"
    command -v "$t" >/dev/null 2>&1 && return 0
    if [ "$OPT_DRY_RUN" = "1" ]; then
        case " $INSTALLED_NOW " in *" $t "*) return 0 ;; esac
    fi
    return 1
}

k8s_establish_cluster() {
    if [ "$CFG_K8S_PROVISION_CLUSTER" = "y" ]; then
        step_begin "Starting a local cluster"
        local tool="$CFG_K8S_CLUSTER_TOOL"
        if [ -z "$tool" ]; then
            if tool_available_now minikube; then tool="minikube"
            elif tool_available_now kind; then tool="kind"
            else tool="minikube"; fi
        fi
        if [ "$tool" = "kind" ]; then
            if ! tool_available_now kind; then
                fail "kind is not installed" "install kind, or point kubectl at an existing cluster"
            fi
            local kname="bedwars"
            if kind get clusters 2>/dev/null | grep -qx "$kname"; then
                #  A cluster that exists but is stopped is a stopped container: starting it
                #  is far cheaper than deleting and recreating.
                ui_info "the kind cluster '$kname' exists; making sure its node is running"
                run_cmd "starting the kind node" docker start "$kname-control-plane" || true
            else
                run_streaming "creating the kind cluster '$kname'" \
                    kind create cluster --name "$kname" --wait 180s || {
                    ui_warn "the first attempt failed -- a half-created cluster is common"
                    run_cmd "deleting the partial cluster" kind delete cluster --name "$kname" || true
                    run_streaming "creating the kind cluster (retry)" \
                        kind create cluster --name "$kname" --wait 180s || \
                        fail "kind would not create a cluster" "run 'kind create cluster' by hand to see the full error"
                }
            fi
            if [ "$OPT_DRY_RUN" = "1" ]; then
                ui_hint "[dry-run] would create or start the kind cluster now"
            else
                ui_ok "kind cluster ready"
            fi
            HAVE_CLUSTER="1"
            CLUSTER_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "kind-$kname")
        else
            if ! tool_available_now minikube; then
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
    #  Answering is not the same as being able to run a pod: the node has to be Ready, or every
    #  workload step after this fails in a way that looks like everything except this.
    if ! wait_for "the cluster's node to be Ready" 180 k8s_node_ready; then
        k8s_diagnose "the cluster's nodes" "$CFG_NAMESPACE"
        fail "no node in the cluster is Ready" \
             "a node that is not Ready cannot run a pod: run 'kubectl describe node' for the reason (runtime, CNI, disk or memory pressure)"
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

#  The chart repository OpenKruise actually serves. The URL this used to point at
#  (https://openkruise.io/charts) answers 404, so the helm route would have failed too.
OPENKRUISE_CHARTS="https://openkruise.github.io/charts/"

#  An API server that answers is not yet a cluster that can run anything. A node reports itself
#  seconds after the cluster starts, and stays NotReady for a while after that; an install fired
#  into that window fails a couple of seconds in with "context deadline exceeded", which explains
#  nothing. Reported from a VM whose diagnosis showed "minikube NotReady control-plane 8s" with the
#  helm release already failed -- 22 GB allocatable, so memory was never the issue.
k8s_node_ready() {
    #  True when a node is Ready -- and also when this user cannot list nodes at all, because
    #  "unknown" must never block an install: a restricted kubeconfig is not a broken cluster.
    #  False only when nodes are visible and none of them is Ready. The STATUS column is split so
    #  "Ready,SchedulingDisabled" still counts.
    local out=""
    out="$(kubectl get nodes --no-headers 2>/dev/null || true)"
    if [ -z "$out" ]; then
        case "$(kubectl get nodes 2>&1 | head -n 3 || true)" in
            *orbidden*|*"cannot list resource"*|*Unauthorized*) return 0 ;;
        esac
        return 1
    fi
    printf '%s\n' "$out" | awk '{print $2}' | tr ',' '\n' | grep -qx "Ready"
}

#  `helm upgrade --install --create-namespace` is NOT idempotent. When the namespace already
#  exists and is not owned by the release, helm refuses:
#      Error: 1 error occurred: * namespaces "kruise-system" already exists
#  which is exactly what stopped the OpenKruise install on a VM whose namespace was already there
#  (an earlier attempt had made it). Better to ask kubectl whether it exists and only then let
#  helm create it -- when it is already present, helm should own the release, not the namespace.
k8s_ns_args() {
    # k8s_ns_args <namespace>: prints --create-namespace only when the namespace is missing
    if kubectl get namespace "$1" >/dev/null 2>&1; then
        return 0
    fi
    printf '%s' '--create-namespace'
}

#  Helm, installing it if this machine has none. OpenKruise publishes no kubectl-only manifest,
#  so this is a real dependency of the Kubernetes path rather than a convenience -- and the
#  installer's job is to resolve it, not to write it down.
ensure_helm() {
    if command -v helm >/dev/null 2>&1; then return 0; fi
    if [ "$OPT_DRY_RUN" = "1" ]; then
        #  Walk the real install route so the dry run prints the commands it would run, which is
        #  the only place `bash` vs `sh` for the helm installer script is visible before it bites.
        ui_info "[dry-run] helm is needed; here is what would install it"
        install_one_tool helm || true
        return 0
    fi
    if [ "$OPT_YES" = "1" ] || [ "$OPT_INSTALL_DEPS" = "no" ]; then
        ui_warn "helm is missing, and this step cannot proceed without it"
        ui_hint "install it, then re-run:"
        ui_hint "  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash"
        return 1
    fi
    ui_warn "helm is missing, and OpenKruise is only published as a chart"
    ask_yesno wanthelm "Install helm now?" "y"
    [ "$wanthelm" = "y" ] || return 1
    install_one_tool helm || return 1
    command -v helm >/dev/null 2>&1
}

#  When a Kubernetes step fails, helm prints one line -- often just "context deadline exceeded"
#  -- and it explains nothing: was the chart wrong, the API server down, the node out of memory,
#  the image unpullable? This collects what the cluster itself says. Read-only and cheap, and
#  written to the log too, so a failure report carries its own explanation instead of needing
#  another round trip.
k8s_diag_cmd() {
    # k8s_diag_cmd <description> <command...>
    local desc="$1"; shift
    local out="" _l=""
    log_line "DIAG: $desc :: $*"
    out="$("$@" 2>&1 | head -n 12)"
    printf '    %s\n' "$desc"
    if [ -n "$out" ]; then
        while IFS= read -r _l; do
            ui_hint "      $_l"
            log_line "DIAG   $_l"
        done < <(printf '%s\n' "$out")
    else
        ui_hint "      (no output)"
    fi
}

k8s_diagnose() {
    # k8s_diagnose <what failed> [namespace] [helm release]
    [ "$OPT_DRY_RUN" = "1" ] && return 0
    local what="$1" ns="${2:-}" release="${3:-}" pod=""
    printf '\n'
    ui_hint "diagnosis: what the cluster says about $what"
    k8s_diag_cmd "kubectl version" kubectl version -o yaml
    k8s_diag_cmd "the API server (/readyz)" kubectl get --raw=/readyz
    k8s_diag_cmd "nodes (are they Ready, and how much can they allocate)" kubectl get nodes -o wide
    #  Memory is the failure this architecture meets first: Kruise's manager plus a match server
    #  is a real footprint, and a node that cannot hold it fails in every way except the obvious.
    k8s_diag_cmd "allocatable memory per node" kubectl get nodes -o custom-columns=NAME:.metadata.name,MEMORY:.status.allocatable.memory,CPU:.status.allocatable.cpu
    k8s_diag_cmd "node and pod resource use (needs a metrics server)" kubectl top nodes
    if [ -n "$ns" ]; then
        k8s_diag_cmd "workloads in $ns" kubectl -n "$ns" get deploy,daemonset,statefulset,pod -o wide
        k8s_diag_cmd "recent events in $ns" kubectl -n "$ns" get events --sort-by=.lastTimestamp
    fi
    if [ -n "$release" ]; then
        k8s_diag_cmd "helm status $release" helm status "$release" -n "${ns:-default}"
        k8s_diag_cmd "helm history $release" helm history "$release" -n "${ns:-default}"
    fi
    #  A pod that is not Running explains most failures, and `describe` is where the reason is.
    if [ -n "$ns" ]; then
        pod="$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | awk '$3 != "Running" && $3 != "Completed" {print $1; exit}')"
        if [ -n "$pod" ]; then
            k8s_diag_cmd "why $pod is not Running" kubectl -n "$ns" describe pod "$pod"
        fi
    fi
    ui_hint "the full text of every one of these is in the log: $LOG_FILE"
}

#  A transient API-server blip -- minikube restarting its apiserver, a node under memory pressure --
#  surfaces as "context deadline exceeded" a few seconds into a helm run and has nothing to do with
#  the chart. Retry once, only after the cluster answers again, and only for that class of error:
#  a chart that does not exist or a value that is wrong is not going to fix itself, and a loop
#  would just hide the real message.
helm_install_with_retry() {
    # helm_install_with_retry <label> <release> <chart> <namespace> [extra args...]
    local label="$1" release="$2" chart="$3" ns="$4"; shift 4
    if run_cmd "$label" helm upgrade --install "$release" "$chart" --namespace "$ns" "$@"; then
        return 0
    fi
    local said=""
    said="$(tail -n 5 "$LAST_FAILURE" 2>/dev/null || true)"
    case "$said" in
        *"deadline exceeded"*|*"connection refused"*|*"unable to connect"*|*"no route to host"*)
            ui_warn "$label failed while the cluster was unreachable -- that is not the chart"
            if wait_for "the cluster's node to be Ready again" 120 k8s_node_ready; then
                ui_info "retrying once: $label"
                run_cmd "$label (retry)" helm upgrade --install "$release" "$chart" --namespace "$ns" "$@"
                return $?
            fi
            ui_warn "the cluster still is not answering after two minutes"
            ;;
        *)
            #  Not a connectivity failure: say what it was and do not retry. The caller diagnoses.
            case "$said" in
                *"Error"*) ;; # already visible from fail()'s tail
            esac
            ;;
    esac
    return 1
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
    #  OpenKruise is published as a chart and its releases carry NO assets: there is no
    #  kubectl-only manifest. The kubectl-all-in-one route this used to take downloaded
    #  ".../releases/latest/download/kruise-all-in-one.yaml", which answers 404 for this and
    #  every other version -- so helm is not a preference here, it is the only route, and the
    #  installer now installs it instead of fetching something that does not exist.
    ensure_helm || fail "helm is required to install OpenKruise" \
        "upstream publishes no kubectl manifest; install helm (https://helm.sh/docs/intro/install/) or follow chapter 8.1"
    #  Ask the cluster whether it is answering at all before burning a helm run on it: an
    #  unreachable API server produces exactly one line of helm output and no clue.
    if [ "$OPT_DRY_RUN" != "1" ]; then
        #  Reachability and readiness are asked separately and on purpose: cluster-info needs no
        #  special permission, while listing nodes may be denied by a restricted kubeconfig -- and
        #  a denial is not a broken cluster, so k8s_node_ready treats it as "cannot tell".
        if ! kubectl cluster-info >/dev/null 2>&1; then
            k8s_diagnose "the cluster connection" kruise-system
            fail "the Kubernetes cluster is not answering" \
                "the API server did not respond -- start it (minikube start, or Docker Desktop's Kubernetes) and re-run"
        fi
        #  Reachable but not Ready is the case that produced a doomed helm run and one line of
        #  output. Give a degraded node a minute to come back before spending anything on it.
        if ! wait_for "a Ready node" 90 k8s_node_ready; then
            k8s_diagnose "the cluster's nodes" kruise-system
            fail "no node is Ready" \
                 "the cluster is answering but cannot schedule: run 'kubectl describe node' and look at the Ready condition"
        fi
    fi
    run_cmd "adding the OpenKruise chart repo" helm repo add openkruise "$OPENKRUISE_CHARTS" || true
    run_cmd "updating chart repos" helm repo update || true
    local nsargs=""
    nsargs="$(k8s_ns_args kruise-system)"
    if ! helm_install_with_retry "installing kruise" kruise openkruise/kruise kruise-system \
            $nsargs --wait --timeout 5m; then
        k8s_diagnose "the kruise release" kruise-system kruise
        fail "could not install OpenKruise" "see chapter 8.1 of docs/MANUAL.md for the manual steps"
    fi
    if ! helm_install_with_retry "installing kruise-game" kruise-game openkruise/kruise-game kruise-system \
            --wait --timeout 5m; then
        k8s_diagnose "the kruise-game release" kruise-system kruise-game
        fail "could not install kruise-game" "the GameServerSet CRD will be missing without it"
    fi
    if [ "$OPT_DRY_RUN" = "1" ]; then
        ui_hint "[dry-run] would wait for the GameServerSet CRD to be published"
        return 0
    fi
    # The publish of the CRD is asynchronous; wait for the name we actually use.
    if wait_for "the GameServerSet CRD to appear" 180 kubectl get crd gameserversets.game.kruise.io; then
        ui_ok "GameServerSet CRD ready"
    else
        k8s_diagnose "the GameServerSet CRD" kruise-system
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
        local nsargs=""
        nsargs="$(k8s_ns_args "$KUBECTL_NS")"
        if ! helm_install_with_retry "helm upgrade --install $CFG_RELEASE" "$CFG_RELEASE" \
                "$ROOT/deploy/helm/bedwars" "$KUBECTL_NS" $nsargs \
                -f "$ROOT/deploy/helm/values-install.yaml"; then
            k8s_diagnose "the $CFG_RELEASE release" "$KUBECTL_NS" "$CFG_RELEASE"
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

    detect_platform
    phase "$(em '🔎') Checking this machine"
    probe_tools
    check_disk

    wizard_mode
    # Then, and only then, insist on what this path needs -- offering to install it
    # rather than stopping with a list of names the user has to chase down.
    ensure_dependencies
    if [ "$CFG_MODE" = "docker" ]; then
        wizard_docker
    else
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
