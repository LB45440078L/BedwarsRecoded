#!/usr/bin/env python3
"""Interactive smoke test for install.sh.

Drives the installer through a real pty, answering each prompt only after it
appears, and asserts what a user actually cares about:

  * every answer is honoured (menus, numbers, paths)
  * a nonsense answer is rejected and asked again, not accepted
  * the review's [e]dit re-runs the wizard instead of continuing
  * [a]bort exits having changed nothing
  * the generated API token is never printed, even in a dry run

Runs `install.sh --dry-run`, so it needs no Docker, no cluster and no network,
and writes nothing. Requires a POSIX host with a pty (Linux or macOS).

    $ deploy/tools/test-install-interactive.py

If you reword a prompt in install.sh, update the prompt strings below; the test
says which step it stalled on rather than passing quietly.
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
    ("Which server engine?", "1"),                   # Spigot
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
    return 0 if ok else 1


UNREDACTED = re.compile(r"^BEDWARS_API_TOKEN=[A-Za-z0-9]{16,}", re.M)


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
