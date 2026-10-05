#!/usr/bin/env python3
"""Minimal Minecraft Server List Ping: confirms a host:port is really a
Minecraft server and reports its version + player counts. Stdlib only."""
import json
import socket
import struct
import sys

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 25565
timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 6.0


def varint(value: int) -> bytes:
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        out += bytes([byte | (0x80 if value else 0)])
        if not value:
            return out


def read_varint(sock) -> int:
    num = shift = 0
    while True:
        byte = sock.recv(1)
        if not byte:
            raise EOFError("connection closed")
        num |= (byte[0] & 0x7F) << shift
        if not byte[0] & 0x80:
            return num
        shift += 7


try:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        # Handshake (protocol -1 = "ask me your version"), then status request.
        payload = varint(0x00) + varint(-1) + varint(len(host)) + host.encode() + struct.pack(">H", port) + varint(1)
        sock.sendall(varint(len(payload)) + payload)
        sock.sendall(varint(1) + varint(0x00))

        read_varint(sock)                       # packet length
        read_varint(sock)                       # packet id
        length = read_varint(sock)
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                break
            data += chunk

        status = json.loads(data.decode("utf-8"))
        version = status.get("version", {})
        players = status.get("players", {})
        print(f"OK  {host}:{port} is a Minecraft server")
        print(f"    version : {version.get('name')} (protocol {version.get('protocol')})")
        print(f"    players : {players.get('online')}/{players.get('max')}")
        motd = status.get("description")
        if isinstance(motd, dict):
            motd = motd.get("text", "")
        if motd:
            print(f"    motd    : {motd}")
except Exception as exc:  # noqa: BLE001 - this is a diagnostic tool
    print(f"FAIL {host}:{port} -> {type(exc).__name__}: {exc}")
    raise SystemExit(1)
