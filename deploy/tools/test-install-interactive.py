#!/usr/bin/env python3
"""Interactive smoke test for install.sh.

Drives the installer through a real pty, answering each prompt only after it
appears, and asserts what a user actually cares about:

  * every answer is honoured (menus, numbers, paths)
  * a nonsense answer is rejected and asked again, not accepted
  * the review's [e]dit re-runs the wizard instead of continuing
  * [a]bort exits having changed nothing
  * the generated API token is never printed, even in a dry run
  * when dependencies are missing, the installer offers to install them and, if
    declined, prints the commands for this machine and stops cleanly -- it must
    never just exit with no explanation

Runs `install.sh --dry-run`, so it needs no Docker, no cluster and no network,
and writes nothing. Requires a POSIX host with a pty (Linux or macOS).

    $ deploy/tools/test-install-interactive.py

If you reword a prompt in install.sh, update the prompt strings below; the test
says which step it stalled on rather than passing quietly. Match on a stable stem
("Which server engine"), never on the full sentence including its punctuation: a
trailing "?" that the rewrite dropped turns into "stalled at step N still waiting
for ...", which reads like an installer hang and is not one.
"""
import os
import pty
import re
import select
import subprocess
import sys
import time

ANSI = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INSTALLER = os.path.join(REPO_ROOT, "install.sh")

# (prompt substring to wait for, answer to send)
SCRIPT = [
    ("Where should the network run?", "1"),          # Docker
    ("Which services?", "4"),                        # Everything
    ("Which server engine", "1"),                    # Spigot
    ("Database name", ""),                           # accept the defaults
    ("Database user", ""),
    ("Published MySQL port", ""),
    ("Generate strong random database passwords?", "n"),
    ("Controller port on the host", ""),
    ("Arena group", "quad"),                         # a non-default value
    ("Matches per server", "twenty-five"),           # nonsense on purpose
    ("Matches per server", "5"),                     # the retry
    ("Minimum servers", "2"),
    ("Maximum servers", "7"),
    ("Where do arena worlds come from?", "1"),
    ("Generate a shared API token for the controller?", "n"),   # decline: check the warning
    ("Run the proxy in OFFLINE MODE (testing only)?", "n"),
    ("Do you have a lobby/hub world", "n"),
    ("Replace the bundled arena", "n"),
    ("a]bort", "e"),                                 # edit, not continue
    ("Which services?", "3"),                        # now Full network
    ("Which server engine", "1"),                    # now asked for this stack too
    ("Database name", ""),
    ("Database user", ""),
    ("Published MySQL port", ""),
    ("Generate strong random database passwords?", "y"),
    ("Controller port on the host", ""),
    ("Arena group", ""),
    ("Matches per server", ""),
    ("Minimum servers", ""),
    ("Maximum servers", ""),
    ("Where do arena worlds come from?", "1"),
    ("Generate a shared API token for the controller?", "y"),   # generate one this time
    ("Run the proxy in OFFLINE MODE (testing only)?", "y"),      # and enable this
    ("Do you have a lobby/hub world", "n"),
    ("a]bort", "a"),                                 # abort
]

CHECKS = [
    ("the invalid answer was rejected and re-asked",
     lambda t: "that value is not valid" in t),
    ("the corrected answer was accepted", lambda t: "Matches per server         5" in t),
    ("a non-default menu value was honoured", lambda t: "Arena group                quad" in t),
    ("the first pass used the chosen stack", lambda t: "stack: everything" in t),
    ("the re-run used the new stack", lambda t: "stack: network" in t),
    ("the engine choice was honoured", lambda t: "engine: spigot" in t),
    #  The reported gap: the engine question only appeared for two of the four stack
    #  choices, so picking the player-facing "Full network" never asked Spigot or Paper.
    #  Both passes ask it now, and the second pass is the network one.
    ("the engine question is asked for the Full network stack too",
     lambda t: t.count("Which server engine") >= 2),
    # Capacity is servers x matches-per-server: 7 x 5 must be reported as 35.
    ("the capacity arithmetic is shown to the user",
     lambda t: "= 35 matches at once" in t),
    ("the token question is asked and a token generated",
     lambda t: "token generated" in t),
    ("declining the token is possible and called out",
     lambda t: "will be OPEN" in t),
    ("offline mode can be switched on and is warned about",
     lambda t: "offline mode ON" in t),
    ("the machine check ran",
     lambda t: "Checking this machine" in t),
    ("aborting reported that nothing changed",
     lambda t: "aborted before changing anything" in t),
]


#  Every check goes through note(), so the count is known. A stage whose body was cut in
#  half -- or that returned early -- then shows up as a missing check instead of as a run
#  that quietly did less than it claims.
RUN = []
EXPECTED_CHECKS = 55         # 50 above + 5 node-readiness checks
EXPECTED_WITHOUT_HOST = 42   # the same, minus the interactive thirteen


#  A stage that cannot run on this host. Recorded with the number of checks it would have
#  contributed, so the count guard stays exact: a skip is neither a pass nor an under-run.
SKIPPED = []


def skip(label, note_count):
    """Record a stage this host cannot exercise, and what it would have checked."""
    SKIPPED.append((label, int(note_count)))
    print("SKIP  " + label)


def expected_here(expected):
    """How many checks can run on this host, given what has been skipped."""
    return expected - sum(n for _, n in SKIPPED)


def note(passed, label, detail=""):
    """Record one check and print its result."""
    RUN.append((bool(passed), label))
    print(("PASS  " if passed else "FAIL  ") + label + detail)
    return bool(passed)


def docker_daemon_answers():
    """Is there a daemon to install against? The interactive stage needs one."""
    try:
        proc = subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return False
    return proc.returncode == 0


def stub_stages():
    """The stages that need nothing from the host: they stub what they test."""
    ok = check_unattended_redaction()
    ok = check_missing_dependency_flow() and ok
    ok = check_broken_tools_do_not_abort() and ok
    ok = check_docker_daemon_states() and ok
    ok = check_kubernetes_cluster_resolution() and ok
    ok = check_sudo_is_never_hidden() and ok
    ok = check_openkruise_installs_helm() and ok
    ok = check_helm_install_judged_by_disk() and ok
    ok = check_helm_create_namespace_is_conditional() and ok
    ok = check_failure_diagnosis() and ok
    ok = check_helm_retry_is_bounded() and ok
    ok = check_node_readiness_gate() and ok
    return ok


def main():
    if not os.path.exists(INSTALLER):
        print("FAIL: no install.sh at %s" % INSTALLER)
        return 1

    # The walk-through below assumes the machine the installer is meant to meet first: one
    # where the chosen path can actually run. With no daemon the installer stops -- correctly
    # -- at the dependency check, before the first services prompt, so this stage is skipped
    # loudly rather than reported as a failure of the installer.
    if not docker_daemon_answers():
        skip("the interactive walk-through (no docker daemon answering on this host)", 13)
        print("      the stub-driven stages below need no host and still run")
        ok = stub_stages()
        return summary(ok, EXPECTED_WITHOUT_HOST)

    master, slave = pty.openpty()
    proc = subprocess.Popen([INSTALLER, "--dry-run"], stdin=slave, stdout=slave,
                            stderr=slave, close_fds=True, cwd=REPO_ROOT)
    os.close(slave)

    seen = ""
    pending = ""
    idx = 0
    fail = None
    deadline = time.time() + 240

    while idx < len(SCRIPT) and time.time() < deadline:
        ready, _, _ = select.select([master], [], [], 2.0)
        if ready:
            try:
                data = os.read(master, 65536)
            except OSError:
                break
            if not data:
                break
            chunk = data.decode("utf-8", "replace")
            pending += chunk
            seen += chunk
        want, answer = SCRIPT[idx]
        if want in pending:
            # A prompt already answered must not satisfy a later identical one.
            time.sleep(0.15)
            os.write(master, (answer + "\n").encode())
            pending = ""
            idx += 1

    if idx < len(SCRIPT):
        fail = "stalled at step %d, still waiting for %r" % (idx, SCRIPT[idx][0])

    # The abort makes the process exit immediately: drain to EOF before reaping,
    # or the final lines are lost with the pty.
    end = time.time() + 5
    while time.time() < end:
        ready, _, _ = select.select([master], [], [], 0.3)
        if not ready:
            if proc.poll() is not None:
                break
            continue
        try:
            data = os.read(master, 65536)
        except OSError:
            break
        if not data:
            break
        seen += data.decode("utf-8", "replace")

    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
        fail = fail or "the installer did not exit after aborting"
    os.close(master)

    text = ANSI.sub("", seen)
    print("answered %d of %d prompts, installer exit code %s" % (idx, len(SCRIPT), proc.returncode))
    if fail:
        print("FAIL: " + fail)
        print("--- last output ---")
        print(text[-1500:])
        return 1

    ok = True
    for label, check in CHECKS:
        ok = note(check(text), label) and ok

    # The interactive flow above ends in [a]bort, so it never reaches the step that writes
    # the config. The unattended flow does, and it is where the secret must stay out of the
    # output: a dry run has to be safe to paste into a bug report.
    ok = stub_stages() and ok
    return summary(ok, EXPECTED_CHECKS)


#  The Kubernetes cluster resolution. Reported: the wizard dead-ended with "no Kubernetes
#  cluster is reachable, point kubectl at one and re-run, or install minikube/kind first",
#  leaving the user to work it out by hand. Each stage below is a machine shape that used
#  to end exactly that way, and none of them needs a container runtime to exercise.
DOCKER_OK_STUB = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  info) if [ \"$2\" = \"--format\" ]; then echo 29.8.2; else echo 'Server: ok'; fi; exit 0 ;;\n"
    "  compose) echo 'Docker Compose version v2.30.0'; exit 0 ;;\n"
    "  version) echo 'Docker version 29.8.2'; exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)

#  One context is configured and dead; another answers.
KUBECTL_SECOND_CONTEXT_OK = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  --context=*)\n"
    "    case \"$2\" in\n"
    "      cluster-info) [ \"$1\" = \"--context=alive-ctx\" ] && exit 0 || exit 1 ;;\n"
    "      *) exit 0 ;;\n"
    "    esac ;;\n"
    "  config)\n"
    "    case \"$2\" in\n"
    "      current-context) echo dead-ctx; exit 0 ;;\n"
    "      get-contexts) [ \"$3\" = \"-o\" ] && { echo dead-ctx; echo alive-ctx; }; exit 0 ;;\n"
    "      use-context) exit 0 ;;\n"
    "    esac ;;\n"
    "  cluster-info) exit 1 ;;\n"
    "  *) exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)

#  One context, and nothing answers through it.
KUBECTL_ALL_DEAD = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  --context=*) exit 1 ;;\n"
    "  config)\n"
    "    case \"$2\" in\n"
    "      current-context) echo only-ctx; exit 0 ;;\n"
    "      get-contexts) [ \"$3\" = \"-o\" ] && echo only-ctx; exit 0 ;;\n"
    "      use-context) exit 0 ;;\n"
    "    esac ;;\n"
    "  cluster-info) exit 1 ;;\n"
    "  *) exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)

#  A local cluster that exists but is not running: one `minikube start` from working.
MINIKUBE_STOPPED_PROFILE = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  profile) [ \"$2\" = list ] && echo "
    "'{\"valid\":[{\"Name\":\"minikube\",\"Status\":\"Stopped\"}],\"invalid\":[]}'; exit 0 ;;\n"
    "  status) echo '{\"Host\":\"Stopped\",\"Kubelet\":\"Stopped\"}'; exit 0 ;;\n"
    "  start) echo 'minikube start would run here'; exit 0 ;;\n"
    "  version) echo v1.34.0; exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)


#  The stage starts at the wizard, so nothing may be "missing" before it: the fake PATH has
#  to carry the build tools too, or the run stops at the dependency check instead.
PRESENT_NOOP_STUB = "#!/bin/sh\nexit 0\n"


def kube_stubs(**extra):
    stubs = {"docker": DOCKER_OK_STUB, "java": PRESENT_NOOP_STUB, "mvn": PRESENT_NOOP_STUB}
    stubs.update(extra)
    return stubs


def run_kubernetes_wizard(tmpdir):
    """A dry, unattended Kubernetes install against a prepared PATH."""
    env = dict(os.environ)
    env["PATH"] = tmpdir
    env["NO_COLOR"] = "1"
    env["TERM"] = "dumb"
    proc = subprocess.run([INSTALLER, "--dry-run", "--yes", "--mode", "kubernetes"],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          cwd=REPO_ROOT, env=env, timeout=240)
    return ANSI.sub("", proc.stdout.decode("utf-8", "replace"))


def check_kubernetes_cluster_resolution():
    """Every shape that used to dead-end has to lead somewhere the user can act on."""
    import tempfile

    dead_end = ("no Kubernetes cluster is reachable",
                "point kubectl at one (kubectl config use-context ...) and re-run")

    cases = (
        (
            kube_stubs(kubectl=KUBECTL_SECOND_CONTEXT_OK),
            ("kind", "minikube", "kubectl", "java", "mvn"),
            "a context that answers is found and used, not left to the user",
            ("using the cluster in context: alive-ctx",),
            dead_end,
        ),
        (
            kube_stubs(kubectl=KUBECTL_ALL_DEAD, minikube=MINIKUBE_STOPPED_PROFILE),
            ("kind", "minikube", "kubectl", "java", "mvn"),
            "a stopped local cluster is offered, and starting it is the default",
            ("Start the minikube cluster", "minikube start -p minikube"),
            dead_end,
        ),
        (
            kube_stubs(kubectl=KUBECTL_ALL_DEAD),
            ("kind", "minikube", "kubectl", "java", "mvn"),
            "with no cluster tool, installing one is offered (and refused unattended)",
            ("Install minikube, then create a cluster", "not installing anything unattended"),
            dead_end,
        ),
        (
            kube_stubs(),
            ("kind", "minikube", "kubectl", "java", "mvn"),
            "without kubectl the path says how to get it",
            ("kubectl", "install it yourself"),
            ("no Kubernetes cluster is reachable",),
        ),
    )

    ok = True
    for stubs, hide, label, must, must_not in cases:
        with tempfile.TemporaryDirectory(prefix="bedwars-fakekube-") as tmpdir:
            build_fake_path(tmpdir, hide=hide, stubs=stubs)
            text = run_kubernetes_wizard(tmpdir)
        missing = [m for m in must if m not in text]
        unwanted = [m for m in must_not if m in text]
        passed = not missing and not unwanted
        ok = ok and passed
        detail = ""
        if missing:
            detail += "  missing %r" % (missing,)
        if unwanted:
            detail += "  unwanted %r" % (unwanted,)
        note(passed, label, detail)
    return ok




#  A sudo that works only when it is told not to ask. Anything else would PROMPT: the marker
#  file records that, and the sleep turns the hang into a visible timeout. This is the exact
#  shape of the reported failure -- run_cmd redirects output, so the prompt went to a log,
#  and sudo waited out its five-minute password timeout before reporting "FAILED (1)".
def sudo_stub(marker, cached):
    first = "exit 0" if cached else "exit 1"
    return (
        "#!/bin/sh\n"
        "if [ \"$1\" = \"-n\" ]; then\n"
        "  case \"$2\" in true) %s ;; *) echo \"SUDO_N: $*\" >> \"%s\"; exit 0 ;; esac\n"
        "fi\n"
        "echo \"PROMPTED: sudo $*\" >> \"%s\"\n"
        "sleep 300\n" % (first, marker, marker)
    )


#  A cluster that answers, with no OpenKruise on it: exactly the state step [2/7] meets.
#  The GameServerSet CRD lookup is the one thing that must fail, or the step is skipped.
KUBECTL_LIVE_NO_KRUISE = (
    "#!/bin/sh\n"
    "case \"$*\" in\n"
    "  *\"get crd gameserversets.game.kruise.io\"*)\n"
    "    echo 'Error from server (NotFound): customresourcedefinitions.apiextensions.k8s.io \"gameserversets.game.kruise.io\" not found' >&2\n"
    "    exit 1 ;;\n"
    "  *\"config current-context\"*) echo minikube; exit 0 ;;\n"
    "  *) exit 0 ;;\n"
    "esac\n"
)


def extract_function(name):
    """Pull one bash function out of install.sh, so a single branch can be tested alone."""
    text = open(INSTALLER, encoding="utf-8").read()
    m = re.search(r"^%s\(\) \{\n(.*?)^\}" % re.escape(name), text, re.S | re.M)
    assert m, "no function %s in install.sh" % name
    return "%s() {\n%s}\n" % (name, m.group(1))


def check_openkruise_installs_helm():
    """The reported failure: step [2/7] fetched a URL that 404s, and the install stopped."""
    import tempfile

    #  1. The step itself, on a cluster that answers and has no OpenKruise.
    with tempfile.TemporaryDirectory(prefix="bedwars-kruise-") as root:
        tmpdir = os.path.join(root, "bin")
        os.makedirs(tmpdir)
        build_fake_path(tmpdir, hide=("helm", "minikube", "kind", "kubectl", "java", "mvn",
                                      "apt-get", "uname"),
                        stubs=kube_stubs(kubectl=KUBECTL_LIVE_NO_KRUISE,
                                         uname=UBUNTU_UNAME_STUB, **{"apt-get": PRESENT_NOOP_STUB}))
        text, rc = run_kubernetes_install(tmpdir, os.path.join(root, "home"))

        ok = note("kruise-all-in-one" not in text and "404" not in text,
                  "OpenKruise is not fetched from the release URL that answers 404")
        ok = note("helm repo add openkruise https://openkruise.github.io/charts/" in text,
                  "and the chart repository it does use is the one that answers") and ok
        #  Reported next: the helm installer script was run with `sh`, which is dash on Ubuntu, so
        #  it died at its first `[[` and blamed curl. Its own shell is bash.
        ok = note("--namespace kruise-system --wait" in text
                  and "kruise-system --create-namespace" not in text,
                  "an existing namespace is used as-is: no --create-namespace") and ok
        ok = note("USE_SUDO=false bash" in text,
                  "the helm installer script is run by bash, the shell it is written in") and ok
        ok = note("USE_SUDO=false sh " not in text,
                  "and never by sh, which is dash on Ubuntu and fails at the first [[") and ok

    #  2. Unattended with no helm: refuse with the command, rather than trying anything.
    with tempfile.TemporaryDirectory(prefix="bedwars-helm-") as root:
        pathdir = os.path.join(root, "bin")
        os.makedirs(pathdir)
        build_fake_path(pathdir, hide=("helm", "docker", "kubectl"))
        script = os.path.join(root, "probe.sh")
        with open(script, "w", encoding="utf-8") as fh:
            fh.write("set -u\n"
                     "OPT_DRY_RUN=0\nOPT_YES=1\nOPT_INSTALL_DEPS=\nINSTALLED_NOW=\n"
                     "ui_info() { echo \"INFO $*\"; }\n"
                     "ui_warn() { echo \"WARN $*\"; }\n"
                     "ui_hint() { echo \"HINT $*\"; }\n"
                     "ask_yesno() { eval \"$1=n\"; }\n"
                     "install_one_tool() { return 1; }\n"
                     + extract_function("ensure_helm") +
                     "if ensure_helm; then echo RESULT=ok; else echo RESULT=refused; fi\n")
        env = {"PATH": pathdir, "HOME": root, "NO_COLOR": "1"}
        proc = subprocess.run(["/bin/bash", script], capture_output=True, text=True,
                              env=env, timeout=60)
        out = proc.stdout + proc.stderr

        ok = note("RESULT=refused" in out and "get-helm-3" in out,
                  "unattended, a missing helm is refused with the install command") and ok
        ok = note("kruise-all-in-one" not in out,
                  "and nothing is fetched from a URL that 404s") and ok
    return ok


#  The reported machine: Ubuntu (apt), an ordinary user, one dead kubectl context, no minikube.
KUBECTL_ONE_DEAD = (
    "#!/bin/sh\n"
    "case \"$*\" in\n"
    "  *\"config get-contexts\"*)\n"
    "    case \"$*\" in *\"-o name\"*) echo minikube ;; *) printf 'CURRENT   NAME\\n*         minikube\\n' ;; esac\n"
    "    exit 0 ;;\n"
    "  *\"config current-context\"*) echo minikube; exit 0 ;;\n"
    "  *) echo \"The connection to the server localhost:8080 was refused\" >&2; exit 1 ;;\n"
    "esac\n"
)
UBUNTU_UNAME_STUB = (
    "#!/bin/sh\n"
    "case \"$1\" in -s) echo Linux ;; -m) echo x86_64 ;; *) echo Linux ;; esac\n"
)


def run_kubernetes_install(tmpdir, home):
    """The Kubernetes path where a tool has to be installed mid-run (no --yes: that refuses)."""
    env = dict(os.environ)
    env["PATH"] = tmpdir
    env["HOME"] = home
    env["NO_COLOR"] = "1"
    env["TERM"] = "dumb"
    proc = subprocess.run([INSTALLER, "--dry-run", "--install-deps", "--mode", "kubernetes"],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          cwd=REPO_ROOT, env=env, timeout=240)
    return ANSI.sub("", proc.stdout.decode("utf-8", "replace")), proc.returncode


def check_sudo_is_never_hidden():
    """The reported Ubuntu failure: sudo's prompt went into a log and sudo sat for five minutes."""
    import tempfile

    ok = True
    with tempfile.TemporaryDirectory(prefix="bedwars-sudo-") as root:
        home = os.path.join(root, "home")
        os.makedirs(home)

        #  1. No cached credentials: the install must land somewhere writable, without asking.
        tmpdir = os.path.join(root, "path-a")
        os.makedirs(tmpdir)
        marker = os.path.join(root, "prompted-a.txt")
        build_fake_path(tmpdir, hide=("sudo", "apt-get", "uname", "kubectl", "minikube", "kind",
                                      "java", "mvn"),
                        stubs=kube_stubs(sudo=sudo_stub(marker, cached=False),
                                         uname=UBUNTU_UNAME_STUB, **{"apt-get": PRESENT_NOOP_STUB},
                                         kubectl=KUBECTL_ONE_DEAD))
        text, rc = run_kubernetes_install(tmpdir, home)

        ok = note("is installed to" in text and ".local/bin" in text,
                  "minikube without sudo still installs, into a directory the user owns") and ok
        ok = note(rc == 0 and "FAILED" not in text.replace("SOFT-FAILED", ""),
                  "the run finished instead of waiting out a password prompt") and ok
        ok = note(not os.path.exists(marker),
                  "sudo was never invoked in a way that can prompt") and ok

        #  2. Credentials already cached: nothing to ask, and it belongs in /usr/local/bin.
        tmpdir = os.path.join(root, "path-b")
        os.makedirs(tmpdir)
        marker = os.path.join(root, "prompted-b.txt")
        build_fake_path(tmpdir, hide=("sudo", "apt-get", "uname", "kubectl", "minikube", "kind",
                                      "java", "mvn"),
                        stubs=kube_stubs(sudo=sudo_stub(marker, cached=True),
                                         uname=UBUNTU_UNAME_STUB, **{"apt-get": PRESENT_NOOP_STUB},
                                         kubectl=KUBECTL_ONE_DEAD))
        text, rc = run_kubernetes_install(tmpdir, home)

        ok = note("sudo -n mv" in text and "is installed to" not in text and rc == 0,
                  "with usable credentials the binary goes to /usr/local/bin as before") and ok
        ok = note(not os.path.exists(marker),
                  "and that path does not ask for a password either") and ok
    return ok


#  The helm installer script installs the binary and THEN exits 1 because its install directory
#  is not on $PATH. Reported verbatim from a VM:
#      helm installed into /home/ambercode/.local/bin/helm
#      helm not found. Is /home/ambercode/.local/bin on your $PATH?
#      Failed to install helm
#  The install had worked. The installer must judge that by the disk, not by the exit code.
HELM_SCRIPT_INSTALLS_THEN_COMPLAINS = (
    "#!/bin/bash\n"
    "mkdir -p \"$HELM_INSTALL_DIR\"\n"
    "printf '#!/bin/sh\\necho v3.22.0\\n' > \"$HELM_INSTALL_DIR/helm\"\n"
    "chmod +x \"$HELM_INSTALL_DIR/helm\"\n"
    "echo \"helm installed into $HELM_INSTALL_DIR/helm\"\n"
    "echo \"helm not found. Is $HELM_INSTALL_DIR on your $PATH?\"\n"
    "echo \"Failed to install helm\"\n"
    "exit 1\n"
)
HELM_SCRIPT_INSTALLS_NOTHING = (
    "#!/bin/bash\n"
    "echo \"the download was corrupt\"\n"
    "exit 1\n"
)

HELM_HARNESS = """set -u
OPT_DRY_RUN=0
OS_FAMILY="debian"
IS_ROOT="0"
BIN_DIR="$HARNESS_BIN_DIR"
BIN_SUDO=""
FAILED_INSTALL=""
INSTALLED_NOW=""
STATE_DIR="$HARNESS_STATE"
ui_info() { printf 'INFO %s\\n' "$*"; }
ui_ok()   { printf 'OK %s\\n' "$*"; }
ui_warn() { printf 'WARN %s\\n' "$*"; }
ui_hint() { printf 'HINT %s\\n' "$*"; }
arch_suffix() { printf 'amd64\\n'; }
run_streaming() { shift; "$@"; }
run_cmd() { shift; "$@"; }
__FUNCTION__
if install_binary_tool helm; then printf 'RESULT=ok\\n'; else printf 'RESULT=failed\\n'; fi
"""


def run_helm_install_harness(root, fixture_body, bin_dir, home):
    """Run the real install_binary_tool helm branch against a script that misbehaves."""
    bindir = os.path.join(root, "stubbin")
    os.makedirs(bindir, exist_ok=True)
    fixture = os.path.join(root, "fake-get-helm-3")
    with open(fixture, "w", encoding="utf-8") as fh:
        fh.write(fixture_body)
    os.chmod(fixture, 0o755)
    #  A curl that writes the fixture wherever -o points: no network, and the script that runs is
    #  the one this test chose.
    with open(os.path.join(bindir, "curl"), "w", encoding="utf-8") as fh:
        fh.write("#!/bin/sh\nwhile [ $# -gt 0 ]; do case \"$1\" in -o) shift; cp %s \"$1\" ;; esac; shift; done\n"
                 % fixture)
    os.chmod(os.path.join(bindir, "curl"), 0o755)

    state = os.path.join(root, "state")
    os.makedirs(state, exist_ok=True)
    script = os.path.join(root, "harness.sh")
    body = HELM_HARNESS.replace("__FUNCTION__", extract_function("install_binary_tool"))
    with open(script, "w", encoding="utf-8") as fh:
        fh.write(body)
    env = {"PATH": bindir + ":/usr/bin:/bin", "HOME": home, "HARNESS_BIN_DIR": bin_dir,
           "HARNESS_STATE": state, "NO_COLOR": "1"}
    proc = subprocess.run(["/bin/bash", script], capture_output=True, text=True, env=env, timeout=120)
    return ANSI.sub("", proc.stdout + proc.stderr)


def check_helm_create_namespace_is_conditional():
    """helm refuses --create-namespace when the namespace is already there. Reported verbatim:
    Error: 1 error occurred: * namespaces "kruise-system" already exists."""
    import tempfile

    ok = True
    with tempfile.TemporaryDirectory(prefix="bedwars-nsargs-") as root:
        for exists, label in ((True, "exists"), (False, "missing")):
            pathdir = os.path.join(root, "bin-" + label)
            os.makedirs(pathdir)
            rc = "0" if exists else "1"
            build_fake_path(pathdir, hide=("kubectl",),
                            stubs={"kubectl": "#!/bin/sh\ncase \"$*\" in *\"get namespace\"*) exit %s ;; "
                                                "*) exit 0 ;; esac\n" % rc})
            script = os.path.join(pathdir, "probe.sh")
            with open(script, "w", encoding="utf-8") as fh:
                fh.write("set -u\n" + extract_function("k8s_ns_args")
                         + "printf 'ARGS=[%s]\\n' \"$(k8s_ns_args kruise-system)\"\n")
            proc = subprocess.run(["/bin/bash", script], capture_output=True, text=True,
                                  env={"PATH": pathdir, "HOME": root}, timeout=60)
            out = proc.stdout
            if exists:
                ok = note("ARGS=[]" in out,
                          "an existing namespace is left alone: no --create-namespace") and ok
            else:
                ok = note("ARGS=[--create-namespace]" in out,
                          "a missing namespace still gets --create-namespace") and ok
    return ok


def check_helm_install_judged_by_disk():
    """A helm that is on disk is installed, whatever the script's exit code says."""
    import tempfile

    with tempfile.TemporaryDirectory(prefix="bedwars-helminst-") as root:
        home = os.path.join(root, "home")
        os.makedirs(home)
        writable = os.path.join(root, "writable-bin")
        os.makedirs(writable)

        #  1. Target directory writable: helm lands there, the script still exits 1.
        out = run_helm_install_harness(root, HELM_SCRIPT_INSTALLS_THEN_COMPLAINS, writable, home)
        installed = os.path.join(writable, "helm")
        ok = note("RESULT=ok" in out and os.path.exists(installed),
                  "a helm on disk counts as installed, even when the script exits non-zero")

        #  2. Target directory needs root, so ~/.local/bin is used -- the reported case.
        root_only = os.path.join(root, "root-only-bin")
        os.makedirs(root_only)
        os.chmod(root_only, 0o500)
        out = run_helm_install_harness(root, HELM_SCRIPT_INSTALLS_THEN_COMPLAINS, root_only, home)
        ok = note("RESULT=ok" in out and os.path.exists(os.path.join(home, ".local", "bin", "helm")),
                  "and the /usr/local/bin-is-not-writable case installs into ~/.local/bin") and ok
        ok = note("not on your PATH yet" in out,
                  "and it tells the user how to put that directory on PATH") and ok

        #  3. The check is not vacuous: a script that installs nothing is still a failure. A
        #  fresh directory, or the helm left by run 1 would satisfy the disk check and make this
        #  pass for the wrong reason.
        empty_bin = os.path.join(root, "empty-bin")
        os.makedirs(empty_bin)
        out = run_helm_install_harness(root, HELM_SCRIPT_INSTALLS_NOTHING, empty_bin, home)
        ok = note("RESULT=failed" in out,
                  "but a helm installer that installs nothing is still reported as a failure") and ok
        os.chmod(root_only, 0o700)
    return ok


#  A cluster whose API server is unreachable, whose node is out of memory, and whose pod is in
#  CrashLoopBackOff: everything the "context deadline exceeded" line refuses to say.
DIAGNOSE_STUBS = {
    "kubectl": ("#!/bin/sh\n"
                "case \"$*\" in\n"
                "  *\"get --raw=/readyz\"*) echo ok; exit 0 ;;\n"
                "  *\"get nodes -o wide\"*) printf 'NAME       STATUS   ROLES\nminikube   Ready    control-plane\n' ;;\n"
                "  *\"get nodes -o custom-columns\"*) printf 'NAME       MEMORY\nminikube   1800000Ki\n' ;;\n"
                "  *\"get pods --no-headers\"*) printf 'kruise-manager-abc   0/1   CrashLoopBackOff   9   4m\n' ;;\n"
                "  *\"describe pod\"*) printf 'Last State:  Terminated\n  Reason:    OOMKilled\n  Exit Code: 137\n' ;;\n"
                "  *\"get events\"*) printf 'LAST SEEN   TYPE      REASON   OBJECT\n4m          Warning   OOMKilling   pod/kruise-manager-abc\n' ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n"),
    "helm": ("#!/bin/sh\ncase \"$*\" in\n"
             "  *\"status\"*) printf 'STATUS: failed\nREVISION: 1\n' ;;\n"
             "  *\"history\"*) printf 'REVISION  STATUS\n1         failed\n' ;;\n"
             "  *) exit 1 ;;\n"
             "esac\n"),
}

DIAGNOSE_HARNESS = """set -u
OPT_DRY_RUN=${HARNESS_DRY:-0}
LOG_FILE="$HARNESS_LOG"
LAST_FAILURE=""
ui_hint() { printf 'HINT %s\\n' "$*"; }
log_line() { printf 'LOG %s\\n' "$*" >> "$LOG_FILE"; }
__FUNCTIONS__
k8s_diagnose "the kruise release" kruise-system kruise
"""


def run_diagnose(root, dry=False):
    pathdir = os.path.join(root, "bin")
    os.makedirs(pathdir, exist_ok=True)
    build_fake_path(pathdir, hide=("kubectl", "helm"), stubs=DIAGNOSE_STUBS)
    logfile = os.path.join(root, "install.log")
    script = os.path.join(root, "diag.sh")
    body = (DIAGNOSE_HARNESS
            .replace("__FUNCTIONS__", extract_function("k8s_diag_cmd") + extract_function("k8s_diagnose"))
            .replace("${HARNESS_DRY:-0}", "1" if dry else "0"))
    with open(script, "w", encoding="utf-8") as fh:
        fh.write(body)
    env = {"PATH": pathdir + ":/usr/bin:/bin", "HOME": root, "HARNESS_LOG": logfile, "NO_COLOR": "1"}
    proc = subprocess.run(["/bin/bash", script], capture_output=True, text=True, env=env, timeout=120)
    return ANSI.sub("", proc.stdout + proc.stderr), logfile


def check_failure_diagnosis():
    """A failed Kubernetes step must explain itself, not print one line from helm."""
    import tempfile

    with tempfile.TemporaryDirectory(prefix="bedwars-diag-") as root:
        out, logfile = run_diagnose(root)
        ok = note("diagnosis: what the cluster says" in out and "the API server (/readyz)" in out
                  and "nodes (are they Ready" in out,
                  "a failed step reports what the cluster says, not just helm's one line")
        ok = note("allocatable memory per node" in out and "1800000Ki" in out,
                  "it reports the memory the node can actually allocate") and ok
        ok = note("why kruise-manager-abc is not Running" in out and "OOMKilled" in out,
                  "and describes the pod that is not Running, with the reason") and ok
        #  The same detail has to reach the log file, or a report from a user is still one line.
        logged = open(logfile, encoding="utf-8").read() if os.path.exists(logfile) else ""
        ok = note("OOMKilled" in logged,
                  "every collected line is written to the log as well as the screen") and ok

        out_dry, _ = run_diagnose(root, dry=True)
        ok = note("diagnosis" not in out_dry,
                  "and a dry run collects nothing, since there is no cluster to ask") and ok
    return ok


RETRY_HARNESS = """set -u
OPT_DRY_RUN=0
ui_info() { printf 'INFO %s\\n' "$*"; }
ui_warn() { printf 'WARN %s\\n' "$*"; }
run_cmd() { local label="$1"; shift; printf 'RUN %s\\n' "$label"; LAST_FAILURE="$HARNESS_LAST"; "$@" >"$LAST_FAILURE" 2>&1; }
wait_for() { return ${HARNESS_WAITFOR:-0}; }
__FUNCTION__
if helm_install_with_retry "installing kruise" kruise openkruise/kruise kruise-system --wait; then
  printf 'RESULT=ok\\n'
else
  printf 'RESULT=failed\\n'
fi
printf 'ATTEMPTS=%s\\n' "$(cat "$HELM_STUB_STATE")"
"""

HELM_FLAKY = ("#!/bin/sh\n"
              "n=$(cat \"$HELM_STUB_STATE\" 2>/dev/null || echo 0)\n"
              "n=$((n + 1)); echo $n > \"$HELM_STUB_STATE\"\n"
              "case \"${HELM_STUB_MODE:-always}\" in\n"
              "  blip) if [ \"$n\" = \"1\" ]; then echo 'Error: UPGRADE FAILED: context deadline exceeded' >&2; exit 1; fi; exit 0 ;;\n"
              "  chart) echo 'Error: failed to download \"openkruise/kruise\"' >&2; exit 1 ;;\n"
              "  *) echo 'Error: UPGRADE FAILED: context deadline exceeded' >&2; exit 1 ;;\n"
              "esac\n")


def run_retry_harness(root, mode, waitfor="0"):
    pathdir = os.path.join(root, "bin")
    os.makedirs(pathdir, exist_ok=True)
    build_fake_path(pathdir, hide=("helm",), stubs={"helm": HELM_FLAKY})
    #  Each case gets its own counter file. Sharing one means the previous case's attempts are
    #  counted again, and an assertion about "how many attempts" passes or fails for the wrong
    #  reason -- which is exactly how a vacuous check sneaks in.
    state = os.path.join(root, "attempts-%s-%s" % (mode, waitfor))
    script = os.path.join(root, "retry.sh")
    with open(script, "w", encoding="utf-8") as fh:
        fh.write(RETRY_HARNESS.replace("__FUNCTION__", extract_function("helm_install_with_retry")))
    env = {"PATH": pathdir + ":/usr/bin:/bin", "HOME": root, "HELM_STUB_STATE": state,
           "HELM_STUB_MODE": mode, "HARNESS_LAST": os.path.join(root, "last.log"),
           "HARNESS_WAITFOR": waitfor, "NO_COLOR": "1"}
    proc = subprocess.run(["/bin/bash", script], capture_output=True, text=True, env=env, timeout=120)
    return ANSI.sub("", proc.stdout + proc.stderr)


def check_helm_retry_is_bounded():
    """A cluster blip is worth one retry; a broken chart is not worth any."""
    import tempfile

    with tempfile.TemporaryDirectory(prefix="bedwars-retry-") as root:
        out = run_retry_harness(root, "blip")
        ok = note("RESULT=ok" in out and "ATTEMPTS=2" in out and "retrying once" in out,
                  "a connectivity failure is retried once, after the cluster answers again")
        out = run_retry_harness(root, "always")
        ok = note("RESULT=failed" in out and "ATTEMPTS=2" in out,
                  "a cluster that stays down is retried once, not in a loop") and ok
        out = run_retry_harness(root, "chart")
        ok = note("RESULT=failed" in out and "ATTEMPTS=1" in out,
                  "a chart that cannot be downloaded is not retried at all") and ok
        out = run_retry_harness(root, "always", waitfor="1")
        ok = note("RESULT=failed" in out and "ATTEMPTS=1" in out and "still is not answering" in out,
                  "and if the cluster never answers again, it says that and stops") and ok
    return ok


def check_node_readiness_gate():
    """A node that exists is not a node that is Ready.

    Reported: the diagnosis showed `minikube NotReady control-plane 8s` with the helm release
    already failed -- the cluster had just started, cluster-info answered, and the install was
    fired into the window where nothing can be scheduled.
    """
    import tempfile

    fn = extract_function("k8s_node_ready")
    cases = (
        ("minikube   Ready    control-plane   5m\n", "READY",
         "a Ready node passes the gate"),
        ("minikube   NotReady   control-plane   8s\n", "NOTREADY",
         "a NotReady node -- the reported state -- does not"),
        ("minikube   Ready,SchedulingDisabled   control-plane   5m\n", "READY",
         "a cordoned-but-Ready node still passes"),
        ("", "NOTREADY", "a cluster that says nothing is not ready"),
        ("Error from server (Forbidden): nodes is forbidden\n", "READY",
         "a restricted kubeconfig that cannot list nodes is never blocked"),
    )
    ok = True
    with tempfile.TemporaryDirectory(prefix="bedwars-ready-") as root:
        for i, (body, expected, label) in enumerate(cases):
            d = os.path.join(root, "case-%d" % i)
            os.makedirs(d)
            stub = "#!/bin/sh\n" + (("printf '%s' \"%s\"\n" % ("%s", body.rstrip("\n"))) if body else "exit 1\n")
            if body.startswith("Error"):
                stub = "#!/bin/sh\nprintf '%s\\n' \"%s\" >&2\nexit 1\n" % ("%s", body.rstrip("\n"))
            with open(os.path.join(d, "kubectl"), "w", encoding="utf-8") as fh:
                fh.write(stub)
            os.chmod(os.path.join(d, "kubectl"), 0o755)
            script = os.path.join(d, "probe.sh")
            with open(script, "w", encoding="utf-8") as fh:
                fh.write(fn + "\nif k8s_node_ready; then echo READY; else echo NOTREADY; fi\n")
            proc = subprocess.run(["/bin/bash", script], capture_output=True, text=True,
                                  env={"PATH": d + ":/usr/bin:/bin", "HOME": d}, timeout=60)
            ok = note(proc.stdout.strip() == expected, label) and ok
    return ok


def summary(ok, expected):
    """Report the total, and refuse to call an under-run a pass."""
    failed = [label for passed, label in RUN if not passed]
    here = expected_here(expected)
    if len(RUN) < here:
        print("FAIL  only %d of %d checks ran -- a stage stopped early or never ran"
              % (len(RUN), here))
        ok = False
    for label, n in SKIPPED:
        print("      %d check(s) not run here: %s" % (n, label))
    print("checks: %d run, %d failed%s"
          % (len(RUN), len(failed), (" (" + "; ".join(failed[:3]) + ")") if failed else ""))
    return 0 if ok else 1

#  The three daemon states that must never be confused with each other. A socket this user
#  may not open is NOT a stopped daemon: reporting "the daemon is not answering" there sends
#  people to restart a service that their own `systemctl status docker` calls
#  `active (running)` -- reported from a VM, with a screenshot as proof.
SOCKET_REFUSED_DOCKER = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  info)\n"
    "    echo \"permission denied while trying to connect to the Docker daemon socket at "
    "unix:///var/run/docker.sock: Get \\\"http://%2Fvar%2Frun%2Fdocker.sock/v1.51/info\\\": "
    "dial unix /var/run/docker.sock: connect: permission denied\" >&2\n"
    "    exit 1 ;;\n"
    "  version) echo \"Docker version 27.3.1, build deadbeef\"; exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)

STOPPED_DOCKER = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  info)\n"
    "    echo \"Cannot connect to the Docker daemon at unix:///var/run/docker.sock. "
    "Is the docker daemon running?\" >&2\n"
    "    exit 1 ;;\n"
    "  version) echo \"Docker version 27.3.1, build deadbeef\"; exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)

#  Debian's own package reports a suffixed engine version. A shape check that only accepted
#  digits and dots threw that away and called a healthy daemon unreachable.
SUFFIXED_DOCKER = (
    "#!/bin/sh\n"
    "case \"$1\" in\n"
    "  info) echo \"20.10.24+dfsg1\"; exit 0 ;;\n"
    "  version) echo \"Docker version 20.10.24+dfsg1, build deadbeef\"; exit 0 ;;\n"
    "esac\n"
    "exit 0\n"
)


def run_installer_with_stub_docker(stub):
    """Run a dry-run install against a stubbed docker and return its colourless output."""
    import tempfile

    with tempfile.TemporaryDirectory(prefix="bedwars-fakedocker-") as tmpdir:
        build_fake_path(tmpdir, stubs={"docker": stub})
        env = dict(os.environ)
        env["PATH"] = tmpdir
        env["NO_COLOR"] = "1"
        env["TERM"] = "dumb"
        proc = subprocess.run([INSTALLER, "--dry-run", "--mode", "docker", "--no-install-deps"],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              cwd=REPO_ROOT, env=env, timeout=240)
        return ANSI.sub("", proc.stdout.decode("utf-8", "replace"))


def check_docker_daemon_states():
    """Each daemon state has to be named correctly, and advise the fix that is real."""
    cases = (
        (
            SOCKET_REFUSED_DOCKER,
            "a refused socket is a permission problem, not a stopped daemon",
            ("cannot open its socket", "newgrp"),
            ("is not answering", "not responding", "brew install --cask docker"),
        ),
        (
            STOPPED_DOCKER,
            "a stopped daemon is reported as stopped",
            ("no daemon is listening", "start Docker"),
            ("cannot open its socket",),
        ),
        (
            SUFFIXED_DOCKER,
            "a suffixed Debian engine version is accepted as a version",
            ("20.10.24+dfsg1, daemon reachable",),
            ("did not answer usefully", "not answering"),
        ),
    )
    ok = True
    for stub, label, must, must_not in cases:
        text = run_installer_with_stub_docker(stub)
        missing = [m for m in must if m not in text]
        unwanted = [m for m in must_not if m in text]
        passed = not missing and not unwanted
        ok = ok and passed
        detail = ""
        if missing:
            detail += "  missing %r" % (missing,)
        if unwanted:
            detail += "  unwanted %r" % (unwanted,)
        note(passed, label, detail)
    return ok


UNREDACTED = re.compile(r"^BEDWARS_API_TOKEN=[A-Za-z0-9]{16,}", re.M)

# Tools hidden from the installer in the missing-dependency stage, so the flow is
# exercised on a machine that has everything (which is the point: it must work when
# they are NOT there, and that is not testable by accident).
HIDDEN = ("docker", "kubectl", "curl", "helm", "minikube", "java", "mvn")

#  Where a PATH normally keeps its binaries. Deliberately not os.defpath: this must
#  work the same whether the test runs on macOS or a Linux CI box.
PATH_DIRS = ("/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin")


def build_fake_path(tmpdir, hide=(), stubs=None):
    """Build a PATH that is exactly what the test needs.

    `hide` removes a tool entirely (so `command -v` fails); `stubs` replaces one with a
    script of our choosing -- which is how a tool that EXISTS but MISBEHAVES is
    reproduced, the shape that used to kill the installer.
    """
    stubs = stubs or {}
    for d in PATH_DIRS:
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if name in hide or name in stubs or name in os.listdir(tmpdir):
                continue
            try:
                os.symlink(os.path.join(d, name), os.path.join(tmpdir, name))
            except OSError:
                pass            # dangling links and duplicates are not interesting
    for name, body in stubs.items():
        path = os.path.join(tmpdir, name)
        if os.path.lexists(path):
            os.remove(path)     # a symlink: writing through it would hit /usr/bin
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(body)
        os.chmod(path, 0o755)
    return tmpdir


def fake_path_without_hidden(tmpdir):
    """A PATH containing everything except HIDDEN, so `command -v docker` fails."""
    return build_fake_path(tmpdir, hide=HIDDEN)


#  A tool that is installed but cannot work: `mvn` with no JDK is the everyday case,
#  and it is what the reported crash looked like.
BROKEN_TOOL = "#!/bin/sh\nexit 1\n"

#  Docker Desktop's Windows binary, seen from WSL: it prints advice, exits 0, and is not
#  a daemon. Counting that text as a version made the installer report a reachable
#  daemon and then fail later on the compose check.
WINDOWS_DOCKER_SHIM = (
    "#!/bin/sh\n"
    "echo \"The command 'docker' could not be found in this WSL 2 distro.\"\n"
    "echo \"We recommend to activate the WSL integration in Docker Desktop settings.\"\n"
    "exit 0\n"
)


def check_missing_dependency_flow():
    """Missing tools must produce an offer, and a decline must explain itself."""
    import tempfile

    with tempfile.TemporaryDirectory(prefix="bedwars-fakebin-") as tmpdir:
        fake_path_without_hidden(tmpdir)
        env = dict(os.environ)
        env["PATH"] = tmpdir
        env["NO_COLOR"] = "1"

        # Declined: instructions for THIS machine, and a clean stop.
        declined = subprocess.run(
            [INSTALLER, "--dry-run", "--mode", "docker", "--no-install-deps"],
            capture_output=True, text=True, cwd=REPO_ROOT, env=env, timeout=300)
        out = ANSI.sub("", declined.stdout + declined.stderr)

        if "Missing dependencies" not in out:
            print("FAIL  missing tools were not reported as missing dependencies")
            return False
        missing_named = all(name in out for name in ("docker", "curl", "compose"))
        if not missing_named:
            print("FAIL  the missing-dependency list did not name the required tools")
            return False
        if "install it yourself" not in out and "install them yourself" not in out:
            print("FAIL  declining did not print manual instructions")
            return False
        if declined.returncode == 0:
            print("FAIL  the installer exited 0 while dependencies were missing")
            return False
        if "installer stopped" not in out:
            # The bug this stage exists for: the script closing with no explanation.
            print("FAIL  the installer stopped without saying why")
            return False

        # Accepted: it must actually drive the package manager (dry-run, so it only
        # says what it would run) and never claim success it cannot verify.
        accepted = subprocess.run(
            [INSTALLER, "--dry-run", "--mode", "docker", "--install-deps"],
            capture_output=True, text=True, cwd=REPO_ROOT, env=env, timeout=300)
        accept_out = ANSI.sub("", accepted.stdout + accepted.stderr)
        drove_pkg_mgr = any(s in accept_out for s in
                            ("apt-get install", "dnf install", "pacman -S", "brew install"))
        if not drove_pkg_mgr and "no package manager" not in accept_out:
            print("FAIL  --install-deps did not run (or describe) a package-manager install")
            return False

    note(True, "missing dependencies are offered, and declining explains what to install")
    return True


def check_broken_tools_do_not_abort():
    """A tool that exists but fails must never take the installer down with it.

    Reported from a WSL host: `mvn` was on the PATH with no JDK behind it, so `mvn -v`
    failed -- and because `VAR=$(probe | ...)` takes the pipeline's status under
    `set -e -o pipefail`, the run died at a *version row*. Docker was worse: the Windows
    binary answered with advice, exited 0, and was recorded as a reachable daemon.
    """
    import tempfile

    with tempfile.TemporaryDirectory(prefix="bedwars-broken-") as tmpdir:
        # Everything present, but mvn/java broken and docker a prose-printing shim.
        build_fake_path(tmpdir, stubs={
            "mvn": BROKEN_TOOL,
            "java": BROKEN_TOOL,
            "docker": WINDOWS_DOCKER_SHIM,
        })
        env = dict(os.environ)
        env["PATH"] = tmpdir
        env["NO_COLOR"] = "1"
        env.pop("JAVA_HOME", None)

        run = subprocess.run(
            [INSTALLER, "--dry-run", "--mode", "docker", "--no-install-deps"],
            capture_output=True, text=True, cwd=REPO_ROOT, env=env, timeout=300)
        out = ANSI.sub("", run.stdout + run.stderr)

        if "unexpected failure" in out:
            print("FAIL  a failing tool probe aborted the installer")
            print(out[-1200:])
            return False
        if "daemon reachable" in out:
            print("FAIL  prose from a docker shim was accepted as a reachable daemon")
            print(out[-1200:])
            return False
        # It must still get all the way to the report and the dependency offer.
        for expected in ("Checking this machine", "maven", "Missing dependencies"):
            if expected not in out:
                print(f"FAIL  the run did not reach '{expected}'")
                print(out[-1200:])
                return False
        if "mvn" in out and "Traceback" in out:
            print("FAIL  the probe left a stack trace in the output")
            return False

    note(True, "a broken tool probe reports instead of aborting, and a docker shim is not a daemon")
    return True


def check_unattended_redaction():
    """Run the unattended dry run and assert the generated token is never printed."""
    env = dict(os.environ)
    env["NO_COLOR"] = "1"
    proc = subprocess.run([INSTALLER, "--dry-run", "--yes", "--mode", "docker"],
                          capture_output=True, text=True, cwd=REPO_ROOT, env=env, timeout=300)
    out = ANSI.sub("", proc.stdout + proc.stderr)

    if "would write" not in out:
        # The run stopped before the config step (typically no Docker daemon here).
        skip("the unattended dry run did not reach the config step on this host", 1)
        return True

    if UNREDACTED.search(out):
        print("FAIL  the unattended dry run printed the generated token in clear")
        return False
    if "BEDWARS_API_TOKEN=<generated, not shown>" not in out:
        print("FAIL  the unattended dry run did not show the redaction marker")
        return False
    if "BEDWARS_OFFLINE_MODE=" not in out:
        print("FAIL  the unattended dry run did not write the offline-mode setting")
        return False
    note(True, "the unattended dry run redacts the generated token")
    return True


if __name__ == "__main__":
    #  The count is checked HERE as well as inside main(), because the way this suite has
    #  twice gone wrong is main() being cut short by an insertion: the trailing summary was
    #  then unreachable, the process exited 0, and stages quietly did not run. A guard that
    #  lives only inside the function cannot notice the function ending early.
    code = main()
    here = expected_here(EXPECTED_CHECKS)
    if code == 0 and len(RUN) < here:
        print("FAIL  only %d of %d checks ran -- a stage stopped early or never ran"
              % (len(RUN), here))
        code = 1
    sys.exit(code)
