#!/usr/bin/env python3
"""Live proof that a queued player is actually placed in a match, and that the fleet stops
at the configured maximum.

This reproduces the two reported failures against a *real running controller*:

  1. "I was not being sent to any game at all" - the proxy asked for arena group "any"
     while servers registered under "solo", so nothing ever matched.
  2. "the Docker keeps creating new machines indefinitely... I had set up 2 x 2 maximum,
     yet I ended up with 8" - the configured maximum never reached the controller.

It needs a controller reachable at CONTROLLER_URL (default http://127.0.0.1:8080) and the
docker CLI for the cap check. No Minecraft client, no game image pull: the "game server" in
steps 1-4 is a registration forged over HTTP, which is exactly what a real server sends.

    python3 deploy/tools/test_dispatch_live.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("CONTROLLER_URL", "http://127.0.0.1:8080").rstrip("/")
TOKEN = os.environ.get("BEDWARS_API_TOKEN", "")
GROUP = os.environ.get("BEDWARS_ARENA_GROUP", "solo")
EXPECTED_MAX = int(os.environ.get("BEDWARS_MAX_SERVERS", "2"))
PLAYER = "00000000-0000-0000-0000-0000000000ab"

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str) -> None:
    results.append((ok, label))
    print(("  PASS  " if ok else "  FAIL  ") + label)


def call(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if TOKEN:
        request.add_header("X-Bedwars-Token", TOKEN)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, body_of(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, body_of(error.read().decode())


def body_of(raw: str) -> dict:
    """`/healthz` answers plain `ok`, not JSON: assume nothing about the body."""
    try:
        parsed = json.loads(raw or "{}")
    except json.JSONDecodeError:
        return {"raw": raw.strip()}
    return parsed if isinstance(parsed, dict) else {"value": parsed}


def wait_for_controller(seconds: int = 180) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            status, _ = call("GET", "/healthz")
            if status == 200:
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def queue_body(preferred_group: str | None = None) -> dict:
    """The body the proxy sends: a QueueRequest, field for field.

    Getting this wrong is not harmless - the controller answers 400 for a body its record
    cannot construct, which is correct, but it makes the test look like a dispatch failure.
    """
    body = {"player": PLAYER, "username": "QueueTest", "priority": 0,
            "requestedAtMillis": int(time.time() * 1000)}
    if preferred_group is not None:
        body["preferredGroup"] = preferred_group
    return body


def game_containers() -> int:
    out = subprocess.run(
        ["docker", "ps", "-a", "--filter", "name=bedwars-game", "--format", "{{.Names}}"],
        capture_output=True, text=True).stdout
    return len([line for line in out.splitlines() if line.strip()])


def main() -> int:
    print(f"controller: {BASE}  group: {GROUP}  configured max: {EXPECTED_MAX}")
    if not wait_for_controller():
        check(False, "controller answered /healthz")
        return 1
    check(True, "controller answered /healthz")

    # ---- 0. what the controller believes its limits are (the plumbing check)
    status, infra = call("GET", "/infra")
    check(status == 200, f"GET /infra -> {status}")
    described = json.dumps(infra)
    check(f'"maxServers":{EXPECTED_MAX}' in described.replace(" ", ""),
          f"the controller was given maxServers={EXPECTED_MAX} (found: "
          f"{infra.get('maxServers')}) - a configured cap that never arrives is the bug")
    check(f'"gamesPerServer":{os.environ.get("BEDWARS_GAMES_PER_SERVER", "2")}'
          in described.replace(" ", ""),
          "the controller was given the configured matches-per-server")

    # ---- 1. a server registers under its real group, exactly as a booting server does
    status, _ = call("POST", "/pods/ready", {
        "podId": "game-fake", "serverId": "game-fake", "address": "127.0.0.1:25577",
        "arenaGroup": GROUP, "capacity": 2, "role": "GAME",
    })
    check(status == 200, f"a {GROUP} server registered ({status})")

    # ---- 2. the proxy's request: no group at all (it sends "any")
    status, body = call("POST", "/lobby/queue", queue_body())
    dispatched = body.get("podAddress")
    check(status == 200 and bool(dispatched),
          f"a queued player is sent to a match (status {status}, destination {dispatched!r})")
    if not dispatched:
        print("         controller said:", json.dumps(body)[:300])

    # ---- 3. and with the explicit "any" token the proxy also used
    call("POST", "/pods/ready", {
        "podId": "game-fake", "serverId": "game-fake", "address": "127.0.0.1:25577",
        "arenaGroup": GROUP, "capacity": 2, "role": "GAME"})
    status, body = call("POST", "/lobby/queue", queue_body("any"))
    check(status == 200 and bool(body.get("podAddress")),
          f'a request for group "any" is served by a {GROUP} server (destination '
          f'{body.get("podAddress")!r})')

    # ---- 4. the queue must not accumulate the requests that timed out
    call("POST", "/pods/ended", {"podId": "game-fake"})
    for _ in range(3):
        call("POST", "/lobby/queue", queue_body())
    time.sleep(1)
    status, depth = call("GET", "/queue/depth")
    total = sum(depth.values()) if isinstance(depth, dict) else -1
    check(status == 200 and total == 0,
          f"requests that found no capacity do not stay in the queue (depth {total})")

    # ---- 5. the cap: keep asking for a server and watch the fleet
    print(f"  ....  asking for capacity repeatedly for ~100s; the fleet must stop at "
          f"{EXPECTED_MAX}")
    peak = game_containers()
    deadline = time.time() + 100
    while time.time() < deadline:
        call("POST", "/lobby/queue", queue_body())
        peak = max(peak, game_containers())
        time.sleep(4)
    check(peak <= EXPECTED_MAX,
          f"the fleet never exceeded the configured maximum (peak {peak} containers, "
          f"max {EXPECTED_MAX})")

    passed = sum(1 for ok, _ in results if ok)
    print(f"\n{passed}/{len(results)} passed")
    for ok, label in results:
        if not ok:
            print("  FAILED: " + label)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
