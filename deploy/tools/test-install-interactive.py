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


def main():
    if not os.path.exists(INSTALLER):
        print("FAIL: no install.sh at %s" % INSTALLER)
        return 1

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
        passed = check(text)
        ok = ok and passed
        print(("PASS  " if passed else "FAIL  ") + label)

    # The interactive flow above ends in [a]bort, so it never reaches the step that
    # writes the config. The unattended flow does, and it is where the secret must stay
    # out of the output: a dry run has to be safe to paste into a bug report.
    ok = check_unattended_redaction() and ok
    ok = check_missing_dependency_flow() and ok
    ok = check_broken_tools_do_not_abort() and ok
    return 0 if ok else 1


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

    print("PASS  missing dependencies are offered, and declining explains what to install")
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

    print("PASS  a broken tool probe reports instead of aborting, and a docker shim is not a daemon")
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
        print("SKIP  the unattended dry run did not reach the config step on this host")
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
    print("PASS  the unattended dry run redacts the generated token")
    return True


if __name__ == "__main__":
    sys.exit(main())
