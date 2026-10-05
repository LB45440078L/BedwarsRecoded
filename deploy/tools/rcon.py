#!/usr/bin/env python3
"""Minimal Source-RCON client for driving a running Minecraft server.

Used here to exercise the plugin on a real Spigot server (start/stop matches,
inspect state) without needing an operator in-game.

Usage:
    python3 rcon.py "bw status"                 # one command
    python3 rcon.py --host 127.0.0.1 --port 25575 --password test123 "bw status"
    python3 rcon.py --multi "bw status" "bw start" "bw status"

Reads host/port/password from the server's server.properties when available
(server.properties in the same directory as this script's --server-dir).
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys
import time

LOGIN = 3
COMMAND = 2
RESPONSE = 0


class RconError(RuntimeError):
    pass


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 8.0) -> None:
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self._id = 0
        self.request_id = 0

    def _next_id(self) -> int:
        self._id += 1
        return self._id

    @staticmethod
    def _pack(request_id: int, kind: int, body: str) -> bytes:
        payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _read_exactly(self, sock: socket.socket, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise RconError("connection closed by server")
            buf += chunk
        return buf

    def _read_packet(self, sock: socket.socket) -> tuple[int, int, str]:
        (length,) = struct.unpack("<i", self._read_exactly(sock, 4))
        if length < 10 or length > 4096 * 2:
            raise RconError(f"implausible packet length {length}")
        data = self._read_exactly(sock, length)
        request_id, kind = struct.unpack("<ii", data[:8])
        body = data[8:-2].decode("utf-8", errors="replace")
        return request_id, kind, body

    def command(self, cmd: str) -> str:
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as sock:
            sock.settimeout(self.timeout)

            login_id = self._next_id()
            sock.sendall(self._pack(login_id, LOGIN, self.password))
            resp_id, _, _ = self._read_packet(sock)
            if resp_id == -1:
                raise RconError("authentication failed (check rcon.password)")

            cmd_id = self._next_id()
            sock.sendall(self._pack(cmd_id, COMMAND, cmd))

            # A command can produce several response packets; drain briefly.
            chunks: list[str] = []
            deadline = time.monotonic() + self.timeout
            while time.monotonic() < deadline:
                try:
                    req_id, _, body = self._read_packet(sock)
                except (socket.timeout, RconError):
                    break
                chunks.append(body)
                if req_id == cmd_id and len(chunks) >= 1:
                    # Keep reading only while packets keep arriving promptly.
                    sock.settimeout(0.4)
            return "\n".join(c for c in chunks if c.strip())


def _from_server_properties(server_dir: str) -> dict[str, str]:
    import os

    path = os.path.join(server_dir, "server.properties")
    values: dict[str, str] = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, _, value = line.partition("=")
                    values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description="Source-RCON client")
    parser.add_argument("--host", default=None)
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--password", default=None)
    parser.add_argument("--server-dir", default=".")
    parser.add_argument("--multi", action="store_true", help="run every argument as a command")
    parser.add_argument("commands", nargs="+")
    args = parser.parse_args()

    props = _from_server_properties(args.server_dir)
    host = args.host or "127.0.0.1"
    port = args.port or int(props.get("rcon.port", 25575))
    password = args.password if args.password is not None else props.get("rcon.password", "")
    if not password:
        print("no rcon.password found; pass --password", file=sys.stderr)
        return 2

    rcon = Rcon(host, port, password)
    commands = args.commands if args.multi else [" ".join(args.commands)]
    status = 0
    for cmd in commands:
        try:
            out = rcon.command(cmd)
        except (RconError, OSError) as exc:
            print(f"$ {cmd}\n  !! {exc}")
            status = 1
            continue
        print(f"$ {cmd}")
        for line in (out.splitlines() or [""]):
            print(f"  {line}")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
